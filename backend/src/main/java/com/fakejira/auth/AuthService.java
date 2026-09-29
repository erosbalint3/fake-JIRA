package com.fakejira.auth;

import com.fakejira.admin.AppSettings;
import com.fakejira.admin.Invite;
import com.fakejira.admin.InviteRepository;
import com.fakejira.admin.RegistrationMode;
import com.fakejira.audit.AuditLog;
import com.fakejira.auth.AuthDtos.AuthResponse;
import com.fakejira.auth.AuthDtos.InviteInfo;
import com.fakejira.auth.AuthDtos.LoginRequest;
import com.fakejira.auth.AuthDtos.RegisterRequest;
import com.fakejira.common.ApiException;
import com.fakejira.config.RateLimitFilter;
import com.fakejira.notification.NotificationService;
import com.fakejira.session.SessionService;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AuthService {

    static final String TWO_FACTOR = "2fa";
    private static final int MAX_CODE_ATTEMPTS = 5;

    /** Result of creating an account: the user and whether an admin still has to approve it. */
    public record NewAccount(User user, boolean pending) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AppSettings settings;
    private final InviteRepository invites;
    private final NotificationService notifications;
    private final RateLimitFilter rateLimit;
    private final SessionService sessions;
    private final TwoFactorService twoFactor;
    private final PasswordPolicy policy;
    private final AuditLog audit;
    private final Map<String, AtomicInteger> codeAttempts = new ConcurrentHashMap<>();

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens, AppSettings settings,
                       InviteRepository invites, NotificationService notifications, RateLimitFilter rateLimit,
                       SessionService sessions, TwoFactorService twoFactor, PasswordPolicy policy, AuditLog audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.settings = settings;
        this.invites = invites;
        this.notifications = notifications;
        this.rateLimit = rateLimit;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        policy.check(request.password(), "password");
        NewAccount account = createAccount(request.username().trim(), request.email().trim().toLowerCase(),
                passwordEncoder.encode(request.password()), true, request.inviteCode());
        if (account.pending()) {
            return AuthResponse.pending(account.user());
        }
        return signIn(account.user(), "password");
    }

    /**
     * Creates an account under the sign-up rules: the first account is the admin; invite-only mode needs an
     * invite; approval mode leaves the account pending unless invited.
     */
    @Transactional
    public NewAccount createAccount(String username, String email, String passwordHash, boolean passwordSet,
                                    String inviteCode) {
        RegistrationMode mode = settings.registrationMode();
        boolean firstUser = users.count() == 0;

        Invite invite = null;
        if (inviteCode != null && !inviteCode.isBlank()) {
            invite = usableInvite(inviteCode);
        }
        if (mode == RegistrationMode.INVITE && invite == null && !firstUser) {
            throw ApiException.forbidden("Sign-up is invite-only. Ask an admin or project owner for an invite link.");
        }
        if (invite != null && invite.getEmail() != null && !invite.getEmail().equalsIgnoreCase(email)) {
            throw ApiException.badRequest("This invite is for " + invite.getEmail() + ".");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "That username is already taken.",
                    Map.of("username", "That username is already taken."));
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with that email already exists.",
                    Map.of("email", "An account with that email already exists."));
        }

        User user = new User(username, email, passwordHash);
        user.setPasswordSet(passwordSet);
        // The very first account administers the instance. Invited people skip approval.
        user.setAdmin(firstUser);
        boolean needsApproval = mode == RegistrationMode.APPROVAL && invite == null && !firstUser;
        user.setStatus(needsApproval ? AccountStatus.PENDING : AccountStatus.ACTIVE);
        users.save(user);

        if (invite != null) {
            invite.markUsed(user);
            if (invite.getProject() != null) {
                invite.getProject().getMembers().add(user);
            }
        }
        audit.record(user, "account.register", user.getUsername(),
                (needsApproval ? "pending approval" : "active") + (invite != null ? ", invited" : ""));
        if (needsApproval) {
            for (User admin : users.findByAdminTrue()) {
                notifications.notify(admin, user, user.getUsername() + " signed up and is waiting for approval", null);
            }
        }
        return new NewAccount(user, needsApproval);
    }

    public AuthResponse login(LoginRequest request) {
        String login = request.login().trim();
        rateLimit.checkLoginAccount(login);
        User user = (login.contains("@")
                ? users.findByEmailIgnoreCase(login)
                : users.findByUsernameIgnoreCase(login))
                .filter(candidate -> candidate.getStatus() != AccountStatus.DELETED && candidate.isPasswordSet())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            audit.record(null, login, "login.failure", login, "wrong username/email or password");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username/email or password.");
        }
        return afterFirstStep(user, "password");
    }

    /** After the password (or Google/GitHub) check: either signs in, or asks for the 2FA code. */
    public AuthResponse afterFirstStep(User user, String method) {
        if (user.getStatus() == AccountStatus.PENDING) {
            throw ApiException.forbidden("Your account is waiting for an admin to approve it.");
        }
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This account no longer exists.");
        }
        if (user.isTotpEnabled()) {
            return AuthResponse.secondStep(tokens.challenge(user, TWO_FACTOR, method));
        }
        return signIn(user, method);
    }

    /** The second step: a code from the authenticator app, or a recovery code. */
    @Transactional
    public AuthResponse loginSecondStep(String challenge, String code) {
        Jwt jwt = tokens.readChallenge(challenge, TWO_FACTOR).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "This sign-in step expired. Please sign in again."));
        if (codeAttempts.size() > 10_000) {
            codeAttempts.clear(); // challenges expire after 5 minutes anyway
        }
        AtomicInteger attempts = codeAttempts.computeIfAbsent(jwt.getId(), id -> new AtomicInteger());
        if (attempts.incrementAndGet() > MAX_CODE_ATTEMPTS) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Too many wrong codes. Please sign in again.");
        }
        User user = users.findById(Long.valueOf(jwt.getSubject()))
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in again."));
        if (!twoFactor.verify(user, code)) {
            audit.record(user.getId(), user.getUsername(), "login.2fa_failure", user.getUsername(), null);
            throw ApiException.field("code", "That code is not right.");
        }
        codeAttempts.remove(jwt.getId());
        return signIn(user, jwt.getClaimAsString("method"));
    }

    /** Starts a session on this device and returns its token. */
    public AuthResponse signIn(User user, String method) {
        String sessionId = sessions.start(user, method).getId();
        audit.record(user, "login.success", user.getUsername(), method);
        return AuthResponse.signedIn(tokens.issue(user, sessionId), user);
    }

    @Transactional(readOnly = true)
    public InviteInfo inviteInfo(String code) {
        String mode = settings.registrationMode().name();
        return invites.findByCode(code.trim())
                .filter(invite -> invite.isUsable(Instant.now()))
                .map(invite -> new InviteInfo(true, invite.getEmail(),
                        invite.getProject() == null ? null : invite.getProject().getName(), mode))
                .orElse(new InviteInfo(false, null, null, mode));
    }

    private Invite usableInvite(String code) {
        return invites.findByCode(code.trim())
                .filter(invite -> invite.isUsable(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("This invite link is invalid, used or expired."));
    }
}
