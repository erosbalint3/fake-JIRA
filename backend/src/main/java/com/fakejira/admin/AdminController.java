package com.fakejira.admin;

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

    public AdminController(CurrentUser currentUser, UserRepository users, AppSettings settings, BackupService backups,
                           NotificationService notifications, MailService mail) {
        this.mail = mail;
        this.currentUser = currentUser;
        this.users = users;
        this.settings = settings;
        this.backups = backups;
        this.notifications = notifications;
    }

    public record AdminUser(UserSummary user, boolean admin, AccountStatus status, Instant createdAt) {
        static AdminUser of(User user) {
            return new AdminUser(UserSummary.of(user), user.isAdmin(), user.getStatus(), user.getCreatedAt());
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
                users.findAllByOrderByCreatedAtAsc().stream().map(AdminUser::of).toList(),
                mail.siteUrl(), mail.isBaseUrlConfigured());
    }

    @PutMapping("/registration")
    public Overview setMode(@AuthenticationPrincipal Jwt jwt, @RequestBody @Validated ModeRequest request) {
        currentUser.admin(jwt);
        settings.setRegistrationMode(request.mode());
        return overview(jwt);
    }

    @PostMapping("/users/{id}/approve")
    @Transactional
    public AdminUser approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        user.setStatus(AccountStatus.ACTIVE);
        notifications.notify(user, admin, admin.getUsername() + " approved your account. Welcome to FakeJIRA!", null);
        return AdminUser.of(user);
    }

    /** Rejects (deletes) an account that is still waiting for approval. */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        currentUser.admin(jwt);
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        if (user.getStatus() != AccountStatus.PENDING) {
            throw ApiException.badRequest("Only accounts waiting for approval can be rejected.");
        }
        users.delete(user);
    }

    @PutMapping("/users/{id}/admin")
    @Transactional
    public AdminUser setAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                              @RequestBody AdminFlagRequest request) {
        currentUser.admin(jwt);
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        if (!request.admin() && user.isAdmin() && users.countByAdminTrue() <= 1) {
            throw ApiException.badRequest("There must be at least one admin.");
        }
        user.setAdmin(request.admin());
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
        currentUser.admin(jwt);
        return backups.create();
    }

    @GetMapping("/backups/{name}")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String name) {
        currentUser.admin(jwt);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new PathResource(backups.file(name)));
    }
}
