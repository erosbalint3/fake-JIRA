package com.fakejira.admin;

import com.fakejira.audit.AuditLog;
import com.fakejira.auth.PasswordPolicy;
import com.fakejira.auth.TokenService;
import com.fakejira.auth.TwoFactorService;
import com.fakejira.session.SessionService;
import com.fakejira.user.AccountService;
import java.util.Map;
import com.fakejira.admin.BackupService.BackupFile;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.notification.NotificationService;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminController {

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final AppSettings settings;
    private final BackupService backups;
    private final NotificationService notifications;
    private final MailService mail;
    private final AuditLog audit;
    private final TwoFactorService twoFactor;
    private final SessionService sessions;
    private final AccountService accounts;
    private final PasswordPolicy passwordPolicy;

    public AdminController(CurrentUser currentUser, UserRepository users, AppSettings settings, BackupService backups,
                           NotificationService notifications, MailService mail, AuditLog audit,
                           TwoFactorService twoFactor, SessionService sessions, AccountService accounts,
                           PasswordPolicy passwordPolicy) {
        this.mail = mail;
        this.audit = audit;
        this.twoFactor = twoFactor;
        this.sessions = sessions;
        this.accounts = accounts;
        this.passwordPolicy = passwordPolicy;
        this.currentUser = currentUser;
        this.users = users;
        this.settings = settings;
        this.backups = backups;
        this.notifications = notifications;
    }

    public record AdminUser(UserSummary user, boolean admin, AccountStatus status, Instant createdAt,
                            boolean twoFactor, boolean mustChangePassword) {
        static AdminUser of(User user) {
            return new AdminUser(UserSummary.of(user), user.isAdmin(), user.getStatus(), user.getCreatedAt(),
                    user.isTotpEnabled(), user.isMustChangePassword());
        }
    }

    /** siteUrl: the public URL used in emails and invite links; siteUrlConfigured: whether APP_BASE_URL set it. */
    public record Overview(RegistrationMode registrationMode, List<AdminUser> users, String siteUrl,
                           boolean siteUrlConfigured) {
    }

    public record ModeRequest(@NotNull RegistrationMode mode) {
    }

    public record AdminFlagRequest(boolean admin) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Overview overview(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return new Overview(settings.registrationMode(),
                users.findAllByOrderByCreatedAtAsc().stream()
                        .filter(u -> u.getStatus() != AccountStatus.DELETED).map(AdminUser::of).toList(),
                mail.siteUrl(), mail.isBaseUrlConfigured());
    }

    @PutMapping("/registration")
    public Overview setMode(@AuthenticationPrincipal Jwt jwt, @RequestBody @Validated ModeRequest request) {
        User admin = currentUser.admin(jwt);
        settings.setRegistrationMode(request.mode());
        audit.record(admin, "admin.registration_mode", null, request.mode().name());
        return overview(jwt);
    }

    @PostMapping("/users/{id}/approve")
    @Transactional
    public AdminUser approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        user.setStatus(AccountStatus.ACTIVE);
        audit.record(admin, "admin.approve", user.getUsername(), null);
        notifications.notify(user, admin, admin.getUsername() + " approved your account. Welcome to FakeJIRA!", null);
        return AdminUser.of(user);
    }

    /** Rejects (deletes) an account that is still waiting for approval. */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        if (user.getStatus() != AccountStatus.PENDING) {
            throw ApiException.badRequest("Only accounts waiting for approval can be rejected.");
        }
        audit.record(admin, "admin.reject", user.getUsername(), user.getEmail());
        users.delete(user);
    }

    @PutMapping("/users/{id}/admin")
    @Transactional
    public AdminUser setAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                              @RequestBody AdminFlagRequest request) {
        User admin = currentUser.admin(jwt);
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        if (!request.admin() && user.isAdmin() && users.countByAdminTrue() <= 1) {
            throw ApiException.badRequest("There must be at least one admin.");
        }
        user.setAdmin(request.admin());
        audit.record(admin, request.admin() ? "admin.grant" : "admin.revoke", user.getUsername(), null);
        return AdminUser.of(user);
    }

    @GetMapping("/backups")
    public List<BackupFile> backups(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return backups.list();
    }

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.CREATED)
    public BackupFile backupNow(@AuthenticationPrincipal Jwt jwt) {
        User admin = currentUser.admin(jwt);
        BackupFile file = backups.create();
        audit.record(admin, "backup.create", file.name(), null);
        return file;
    }

    @GetMapping("/backups/{name}")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String name) {
        User admin = currentUser.admin(jwt);
        audit.record(admin, "backup.download", name, null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new PathResource(backups.file(name)));
    }

    // ---------------------------------------------------------------- account help

    /** For a lost phone: turns the user's two-factor authentication off. */
    @PostMapping("/users/{id}/reset-2fa")
    @Transactional
    public AdminUser resetTwoFactor(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = activeUser(id);
        twoFactor.reset(admin, user);
        return AdminUser.of(users.findById(id).orElseThrow());
    }

    /** The user must choose a new password at their next visit; their other devices are signed out. */
    @PostMapping("/users/{id}/require-password-change")
    @Transactional
    public AdminUser requirePasswordChange(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = activeUser(id);
        user.setMustChangePassword(true);
        if (!user.getId().equals(admin.getId())) {
            sessions.revokeAll(user.getId(), null);
        }
        audit.record(admin, "admin.require_password_change", user.getUsername(), null);
        return AdminUser.of(user);
    }

    @PostMapping("/users/{id}/sign-out")
    public Map<String, Integer> signOutEverywhere(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = activeUser(id);
        int count = sessions.revokeAll(user.getId(), user.getId().equals(admin.getId())
                ? jwt.getClaimAsString(TokenService.SESSION) : null);
        audit.record(admin, "admin.sign_out", user.getUsername(), count + " sessions");
        return Map.of("signedOut", count);
    }

    /** Deletes an active account (personal data erased, history kept as "Deleted user"). */
    @DeleteMapping("/users/{id}/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        if (admin.getId().equals(id)) {
            throw ApiException.badRequest("Delete your own account from your profile.");
        }
        accounts.delete(admin, activeUser(id));
    }

    @GetMapping("/password-policy")
    public PasswordPolicy.Rules passwordPolicy(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return passwordPolicy.rules();
    }

    @PutMapping("/password-policy")
    public PasswordPolicy.Rules setPasswordPolicy(@AuthenticationPrincipal Jwt jwt, @RequestBody PasswordPolicy.Rules rules) {
        User admin = currentUser.admin(jwt);
        passwordPolicy.setRules(rules);
        PasswordPolicy.Rules saved = passwordPolicy.rules();
        audit.record(admin, "admin.password_policy", null, "min " + saved.minLength() + (saved.upper() ? ", upper" : "")
                + (saved.digit() ? ", digit" : "") + (saved.special() ? ", special" : ""));
        return saved;
    }

    private User activeUser(Long id) {
        return users.findById(id).filter(u -> u.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("User not found."));
    }
}
