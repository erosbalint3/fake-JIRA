package com.fakejira.user;

import com.fakejira.audit.AuditLog;
import com.fakejira.auth.PasswordPolicy;
import com.fakejira.auth.TokenService;
import com.fakejira.auth.TwoFactorService;
import com.fakejira.auth.oauth.UserIdentityRepository;
import com.fakejira.session.SessionService;
import com.fakejira.auth.AuthDtos.ChangePasswordRequest;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.push.PushSubscriptionRepository;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final CurrentUser currentUser;
    private final TaskRepository tasks;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final PushSubscriptionRepository push;
    private final PasswordPolicy policy;
    private final SessionService sessions;
    private final AuditLog audit;
    private final TwoFactorService twoFactor;
    private final UserIdentityRepository identities;

    public ProfileController(CurrentUser currentUser, TaskRepository tasks, UserRepository users,
                             PasswordEncoder passwordEncoder, MailService mail, PushSubscriptionRepository push,
                             PasswordPolicy policy, SessionService sessions, AuditLog audit, TwoFactorService twoFactor,
                             UserIdentityRepository identities) {
        this.policy = policy;
        this.sessions = sessions;
        this.audit = audit;
        this.twoFactor = twoFactor;
        this.identities = identities;
        this.push = push;
        this.currentUser = currentUser;
        this.tasks = tasks;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
    }

    public record Stats(long assigned, long inProgress, long done, long reported) {
    }

    public record ProfileResponse(UserSummary user, Instant memberSince, Stats stats, boolean admin,
                                  EmailFrequency emailFrequency, boolean emailAvailable, int pushDevices,
                                  boolean twoFactorEnabled, int recoveryCodesLeft, boolean passwordSet,
                                  boolean mustChangePassword, java.util.List<String> identities, Away away,
                                  boolean calendarFeed) {
    }

    /** Out-of-office settings; all null when none are set. */
    public record Away(java.time.LocalDate from, java.time.LocalDate until, String message) {
    }

    /** Only non-null fields change. An empty display name removes it. */
    public record SettingsRequest(EmailFrequency emailFrequency,
                                  @jakarta.validation.constraints.Size(max = 60, message = "Display name must be at most 60 characters")
                                  String displayName) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        Long id = user.getId();
        Stats stats = new Stats(
                tasks.countByAssigneeId(id),
                tasks.countByAssigneeIdAndStatus(id, TaskStatus.IN_PROGRESS),
                tasks.countByAssigneeIdAndStatus(id, TaskStatus.DONE),
                tasks.countByReporterId(id));
        return new ProfileResponse(UserSummary.of(user), user.getCreatedAt(), stats, user.isAdmin(),
                user.getEmailFrequency(), mail.isEnabled(), (int) push.countByUserId(user.getId()),
                user.isTotpEnabled(), twoFactor.remainingRecoveryCodes(user), user.isPasswordSet(),
                user.isMustChangePassword(), identities.providersFor(user.getId()),
                new Away(user.getAwayFrom(), user.getAwayUntil(), user.getAwayMessage()), user.getCalendarToken() != null);
    }

    @PutMapping("/settings")
    @Transactional
    public ProfileResponse updateSettings(@AuthenticationPrincipal Jwt jwt,
                                          @jakarta.validation.Valid @RequestBody SettingsRequest request) {
        User user = currentUser.from(jwt);
        if (request.emailFrequency() != null) {
            user.setEmailFrequency(request.emailFrequency());
            if (user.getEmailFrequency() == EmailFrequency.DAILY || user.getEmailFrequency() == EmailFrequency.WEEKLY) {
                // Start the digest from now rather than replaying older notifications.
                user.setLastDigestAt(Instant.now());
            }
        }
        if (request.displayName() != null) {
            String name = request.displayName().trim();
            user.setDisplayName(name.isEmpty() ? null : name);
        }
        users.save(user);
        return profile(jwt);
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        User user = currentUser.from(jwt);
        if (user.isPasswordSet() && (request.currentPassword() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash()))) {
            throw ApiException.field("currentPassword", "Your current password is incorrect.");
        }
        policy.check(request.newPassword(), "newPassword");
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordSet(true);
        user.setMustChangePassword(false);
        users.save(user);
        // Other devices signed in with the old password are signed out.
        sessions.revokeAll(user.getId(), jwt.getClaimAsString(TokenService.SESSION));
        audit.record(user, "password.change", user.getUsername(), null);
    }
}
