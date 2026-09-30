package com.fakejira.ops;

import com.fakejira.admin.AppSettings;
import com.fakejira.admin.BackupService;
import com.fakejira.admin.OffsiteBackup;
import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.mail.MailService;
import com.fakejira.notification.NotificationService;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the server and tells admins (notification and email) when something needs attention: low disk space,
 * missing or stale backups, failing off-site uploads, a burst of server errors, or memory pressure. Admins hear once
 * when a check fails and once when it recovers. Runs every 10 minutes; thresholds are adjustable.
 */
@Service
public class HealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);
    static final String KEY = "health.thresholds";

    public record Thresholds(int minFreeDiskMb, int maxBackupAgeHours, int maxServerErrors, int maxHeapPercent,
                             boolean alerts) {
    }

    /** One check's current state; {@code since}: when it last changed. */
    public record Check(String id, String label, boolean ok, String detail, Instant since) {
    }

    private record State(boolean ok, Instant since) {
    }

    private final AppSettings settings;
    private final ObjectMapper json;
    private final BackupService backups;
    private final OffsiteBackup offsite;
    private final RequestMetrics metrics;
    private final UserRepository users;
    private final NotificationService notifications;
    private final MailService mail;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final Path dataDir;
    private final boolean backupsScheduled;
    private final Instant started = Instant.now();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Check> latest = new LinkedHashMap<>();
    private long lastErrorCount = -1;

    public HealthMonitor(AppSettings settings, ObjectMapper json, BackupService backups, OffsiteBackup offsite,
                         RequestMetrics metrics, UserRepository users, NotificationService notifications, MailService mail,
                         AuditLog audit, TransactionTemplate tx,
                         @Value("${app.storage.dir:./data/attachments}") String storageDir,
                         @Value("${app.backup.cron:0 30 3 * * *}") String backupCron) {
        this.settings = settings;
        this.json = json;
        this.backups = backups;
        this.offsite = offsite;
        this.metrics = metrics;
        this.users = users;
        this.notifications = notifications;
        this.mail = mail;
        this.audit = audit;
        this.tx = tx;
        this.dataDir = Path.of(storageDir).toAbsolutePath().normalize().getParent();
        this.backupsScheduled = !"-".equals(backupCron.trim());
    }

    public Thresholds thresholds() {
        return settings.get(KEY).map(text -> {
            try {
                return json.readValue(text, Thresholds.class);
            } catch (JsonProcessingException e) {
                return null;
            }
        }).orElse(new Thresholds(1024, 36, 25, 90, true));
    }

    public Thresholds save(Thresholds t) {
        if (t.minFreeDiskMb() < 0 || t.maxBackupAgeHours() < 1 || t.maxServerErrors() < 1
                || t.maxHeapPercent() < 50 || t.maxHeapPercent() > 99) {
            throw ApiException.badRequest("Check the thresholds: free disk ≥ 0 MB, backup age ≥ 1 hour, errors ≥ 1, memory 50–99%.");
        }
        try {
            settings.put(KEY, json.writeValueAsString(t));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    public synchronized List<Check> current() {
        if (latest.isEmpty()) {
            check();
        }
        return new ArrayList<>(latest.values());
    }

    @Scheduled(fixedDelayString = "${app.health.interval-ms:600000}", initialDelayString = "${app.health.initial-delay-ms:120000}")
    public void scheduled() {
        try {
            check();
        } catch (RuntimeException e) {
            log.error("Health check failed", e);
        }
    }

    /** Runs every check now, alerting admins about changes. */
    public synchronized List<Check> check() {
        Thresholds t = thresholds();
        List<Check> results = new ArrayList<>();
        results.add(disk(t));
        results.add(backup(t));
        if (offsite.isConfigured()) {
            OffsiteBackup.Status status = offsite.status();
            boolean ok = status.lastError() == null || status.lastError().isBlank();
            results.add(new Check("offsite", "Off-site backup", ok, ok ? (status.lastUploadAt() == null ? "No upload yet"
                    : "Last upload " + status.lastUploadAt()) : "Upload failed: " + status.lastError(), null));
        }
        results.add(errors(t));
        results.add(memory(t));
        for (Check check : results) {
            State before = states.get(check.id());
            Instant since = before == null || before.ok() != check.ok() ? Instant.now() : before.since();
            states.put(check.id(), new State(check.ok(), since));
            Check stored = new Check(check.id(), check.label(), check.ok(), check.detail(), since);
            latest.put(check.id(), stored);
            boolean changed = before == null ? !check.ok() : before.ok() != check.ok();
            if (changed) {
                alert(stored, t);
            }
        }
        return results.stream().map(c -> latest.get(c.id())).toList();
    }

    private Check disk(Thresholds t) {
        try {
            Path dir = dataDir != null && Files.exists(dataDir) ? dataDir : Path.of(".").toAbsolutePath();
            FileStore store = Files.getFileStore(dir);
            long freeMb = store.getUsableSpace() / (1024 * 1024);
            long totalMb = Math.max(1, store.getTotalSpace() / (1024 * 1024));
            boolean ok = freeMb >= t.minFreeDiskMb();
            return new Check("disk", "Disk space", ok, String.format("%,d MB free of %,d MB (%d%%)", freeMb, totalMb,
                    freeMb * 100 / totalMb), null);
        } catch (IOException e) {
            return new Check("disk", "Disk space", false, "Could not read disk space: " + e.getMessage(), null);
        }
    }

    private Check backup(Thresholds t) {
        if (!backupsScheduled) {
            return new Check("backup", "Backups", true, "Automatic backups are turned off", null);
        }
        Instant newest = backups.list().stream().map(BackupService.BackupFile::createdAt).max(Instant::compareTo).orElse(null);
        Duration limit = Duration.ofHours(t.maxBackupAgeHours());
        if (newest == null) {
            boolean grace = started.plus(limit).isAfter(Instant.now());
            return new Check("backup", "Backups", grace, grace ? "No backup yet" : "No backup has been made", null);
        }
        boolean ok = newest.plus(limit).isAfter(Instant.now());
        return new Check("backup", "Backups", ok, "Newest backup " + newest, null);
    }

    private Check errors(Thresholds t) {
        long total = metrics.counts().entrySet().stream().filter(e -> e.getKey().endsWith(" 5xx"))
                .mapToLong(e -> e.getValue().sum()).sum();
        long recent = lastErrorCount < 0 ? 0 : total - lastErrorCount;
        lastErrorCount = total;
        boolean ok = recent < t.maxServerErrors();
        return new Check("errors", "Server errors", ok, recent + " server errors since the last check", null);
    }

    private Check memory(Thresholds t) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        int percent = (int) (heap.getUsed() * 100 / Math.max(1, max));
        return new Check("memory", "Memory", percent < t.maxHeapPercent(), percent + "% of " + (max / (1024 * 1024)) + " MB in use", null);
    }

    private void alert(Check check, Thresholds t) {
        String message = check.ok() ? "Resolved: " + check.label() + " is healthy again (" + check.detail() + ")."
                : "Needs attention: " + check.label() + " — " + check.detail() + ".";
        log.warn("Health: {}", message);
        audit.record(null, "health", check.ok() ? "health.recovered" : "health.alert", check.id(), check.detail());
        if (!t.alerts()) {
            return;
        }
        List<User> admins = tx.execute(status -> users.findAllByOrderByCreatedAtAsc().stream()
                .filter(u -> u.isAdmin() && u.getStatus() == AccountStatus.ACTIVE).toList());
        for (User admin : admins == null ? List.<User>of() : admins) {
            notifications.notifySelf(admin, message, null);
            mail.send(admin.getEmail(), (check.ok() ? "[Resolved] " : "[Alert] ") + "FakeJIRA: " + check.label(),
                    message + "\n\nServer status: " + mail.link("/admin") + "\n");
        }
    }

    /** For tests: forget the error baseline and past states. */
    synchronized void reset() {
        states.clear();
        latest.clear();
        lastErrorCount = -1;
    }
}
