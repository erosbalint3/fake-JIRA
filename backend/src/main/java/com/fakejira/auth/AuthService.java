package com.fakejira.auth;

import com.fakejira.admin.AppSettings;
import com.fakejira.admin.Invite;
import com.fakejira.admin.InviteRepository;
import com.fakejira.admin.RegistrationMode;
import com.fakejira.auth.AuthDtos.AuthResponse;
import com.fakejira.auth.AuthDtos.InviteInfo;
import com.fakejira.auth.AuthDtos.LoginRequest;
import com.fakejira.auth.AuthDtos.RegisterRequest;
import com.fakejira.common.ApiException;
import com.fakejira.config.RateLimitFilter;
import com.fakejira.notification.NotificationService;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AppSettings settings;
    private final InviteRepository invites;
    private final NotificationService notifications;
    private final RateLimitFilter rateLimit;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens, AppSettings settings,
                       InviteRepository invites, NotificationService notifications, RateLimitFilter rateLimit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.settings = settings;
        this.invites = invites;
        this.notifications = notifications;
        this.rateLimit = rateLimit;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();
        RegistrationMode mode = settings.registrationMode();
        boolean firstUser = users.count() == 0;

        Invite invite = null;
        if (request.inviteCode() != null && !request.inviteCode().isBlank()) {
            invite = usableInvite(request.inviteCode());
        }
        if (mode == RegistrationMode.INVITE && invite == null && !firstUser) {
            throw ApiException.forbidden("Sign-up is invite-only. Ask an admin or project owner for an invite link.");
        }
        if (invite != null && invite.getEmail() != null && !invite.getEmail().equalsIgnoreCase(email)) {
            throw ApiException.badRequest("This invite is for " + invite.getEmail() + ".");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("That username is already taken.");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with that email already exists.");
        }

        User user = new User(username, email, passwordEncoder.encode(request.password()));
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
        if (needsApproval) {
            for (User admin : users.findByAdminTrue()) {
                notifications.notify(admin, user, user.getUsername() + " signed up and is waiting for approval", null);
            }
            return new AuthResponse(null, UserSummary.of(user), true, false);
        }
        return new AuthResponse(tokens.issue(user), UserSummary.of(user), false, user.isAdmin());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String login = request.login().trim();
        rateLimit.checkLoginAccount(login);
        User user = (login.contains("@")
                ? users.findByEmailIgnoreCase(login)
                : users.findByUsernameIgnoreCase(login))
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username/email or password."));
        if (user.getStatus() == AccountStatus.PENDING) {
            throw ApiException.forbidden("Your account is waiting for an admin to approve it.");
        }
        return new AuthResponse(tokens.issue(user), UserSummary.of(user), false, user.isAdmin());
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
