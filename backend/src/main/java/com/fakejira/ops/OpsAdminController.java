package com.fakejira.ops;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.CurrentUser;
import com.fakejira.task.AttachmentStorage;
import com.fakejira.user.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin operations: data retention, attachment encryption, restoring backups, and health alerts. */
@RestController
@RequestMapping("/api/admin")
public class OpsAdminController {

    public record RetentionResponse(RetentionService.Policy policy, RetentionService.Counts preview) {
    }

    public record HealthResponse(List<HealthMonitor.Check> checks, HealthMonitor.Thresholds thresholds) {
    }

    public record RestoreRequest(String password) {
    }

    private final CurrentUser currentUser;
    private final RetentionService retention;
    private final AttachmentStorage storage;
    private final RestoreService restore;
    private final HealthMonitor health;
    private final AuditLog audit;

    public OpsAdminController(CurrentUser currentUser, RetentionService retention, AttachmentStorage storage,
                              RestoreService restore, HealthMonitor health, AuditLog audit) {
        this.currentUser = currentUser;
        this.retention = retention;
        this.storage = storage;
        this.restore = restore;
        this.health = health;
        this.audit = audit;
    }

    // ---- Retention ---------------------------------------------------------------------------------------------------

    @GetMapping("/retention")
    public RetentionResponse retention(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return new RetentionResponse(retention.policy(), retention.preview());
    }

    @PutMapping("/retention")
    public RetentionResponse saveRetention(@AuthenticationPrincipal Jwt jwt, @RequestBody RetentionService.Policy body) {
        User admin = currentUser.admin(jwt);
        RetentionService.Policy saved = retention.save(body);
        audit.record(admin, "admin.retention", null, saved.toString());
        return new RetentionResponse(saved, retention.preview());
    }

    @PostMapping("/retention/run")
    public RetentionService.Counts runRetention(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return retention.run();
    }

    // ---- Encryption --------------------------------------------------------------------------------------------------

    @GetMapping("/encryption")
    public AttachmentStorage.EncryptionStatus encryption(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return storage.status();
    }

    @PostMapping("/encryption/encrypt-all")
    public Map<String, Object> encryptAll(@AuthenticationPrincipal Jwt jwt) {
        User admin = currentUser.admin(jwt);
        int changed = storage.encryptAll();
        audit.record(admin, "admin.encrypt_files", null, changed + " files");
        return Map.of("changed", changed, "status", storage.status());
    }

    // ---- Restore -----------------------------------------------------------------------------------------------------

    @GetMapping("/restore")
    public RestoreService.Status restoreStatus(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return restore.status();
    }

    @PostMapping("/backups/{name}/restore")
    public Map<String, String> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable String name,
                                       @RequestBody RestoreRequest body) {
        User admin = currentUser.admin(jwt);
        String safety = restore.schedule(admin, name, body == null ? null : body.password());
        return Map.of("safetyBackup", safety, "message",
                "The server is restarting to restore " + name + ". This takes about a minute.");
    }

    // ---- Health ------------------------------------------------------------------------------------------------------

    @GetMapping("/health")
    public HealthResponse health(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return new HealthResponse(health.current(), health.thresholds());
    }

    @PostMapping("/health/check")
    public HealthResponse checkNow(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        return new HealthResponse(health.check(), health.thresholds());
    }

    @PutMapping("/health")
    public HealthResponse saveThresholds(@AuthenticationPrincipal Jwt jwt, @RequestBody HealthMonitor.Thresholds body) {
        User admin = currentUser.admin(jwt);
        health.save(body);
        audit.record(admin, "admin.health_thresholds", null, body.toString());
        return new HealthResponse(health.check(), health.thresholds());
    }
}
