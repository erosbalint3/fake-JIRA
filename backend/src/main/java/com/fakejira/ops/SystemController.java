package com.fakejira.ops;

import com.fakejira.admin.BackupService;
import com.fakejira.admin.OffsiteBackup;
import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.InboundMail;
import com.fakejira.mail.MailService;
import com.fakejira.task.AttachmentQuota;
import com.fakejira.task.AttachmentRepository;
import com.fakejira.user.User;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The admin System page (health, storage, backups, updates), a public health check and Prometheus metrics. */
@RestController
public class SystemController {

    private static final Instant STARTED = Instant.now();

    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final AttachmentRepository attachments;
    private final AttachmentQuota quota;
    private final BackupService backups;
    private final OffsiteBackup offsite;
    private final UpdateChecker updates;
    private final MailService mail;
    private final InboundMail inbound;
    private final RequestMetrics requests;
    private final AuditLog audit;
    private final String datasourceUrl;
    private final Path dataDir;
    private final String metricsToken;

    public SystemController(CurrentUser currentUser, JdbcTemplate jdbc, EntityManager em, AttachmentRepository attachments,
                            AttachmentQuota quota, BackupService backups, OffsiteBackup offsite, UpdateChecker updates,
                            MailService mail, InboundMail inbound, RequestMetrics requests, AuditLog audit,
                            @Value("${spring.datasource.url}") String datasourceUrl,
                            @Value("${app.storage.dir:./data/attachments}") String storageDir,
                            @Value("${app.metrics.token:}") String metricsToken) {
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.em = em;
        this.attachments = attachments;
        this.quota = quota;
        this.backups = backups;
        this.offsite = offsite;
        this.updates = updates;
        this.mail = mail;
        this.inbound = inbound;
        this.requests = requests;
        this.audit = audit;
        this.datasourceUrl = datasourceUrl;
        this.dataDir = Path.of(storageDir).toAbsolutePath().normalize().getParent();
        this.metricsToken = metricsToken.trim();
    }

    public static String version() {
        String v = SystemController.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    public record Database(String product, String version, String url, Long sizeBytes, boolean ok) {
    }

    public record Disk(String path, long freeBytes, long totalBytes) {
    }

    public record Counts(long users, long projects, long tasks, long openTasks, long comments, long attachments,
                         long attachmentBytes) {
    }

    public record Jvm(String java, long heapUsedBytes, long heapMaxBytes, int threads, int processors) {
    }

    public record Features(boolean mail, boolean inboundMail, boolean offsiteBackups, boolean metrics) {
    }

    public record SystemInfo(String version, Instant startedAt, long uptimeSeconds, Database database, Disk disk,
                             Counts counts, Jvm jvm, Features features, List<BackupService.BackupFile> backups,
                             OffsiteBackup.Status offsite, UpdateChecker.Update update, AttachmentQuota.Limits quota,
                             List<ProjectUsage> storageByProject) {
    }

    public record ProjectUsage(String key, String name, long bytes, long files) {
    }

    public record QuotaRequest(long projectMb, long totalMb) {
    }

    /** Public liveness/readiness check for load balancers and uptime monitors. */
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            db = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", db ? "UP" : "DOWN");
        body.put("database", db ? "UP" : "DOWN");
        body.put("version", version());
        return ResponseEntity.status(db ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @GetMapping("/api/admin/system")
    @Transactional(readOnly = true)
    public SystemInfo system(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        Runtime runtime = Runtime.getRuntime();
        File disk = dataDir.toFile();
        List<ProjectUsage> usage = new ArrayList<>();
        for (Object[] row : attachments.usageByProject()) {
            usage.add(new ProjectUsage((String) row[0], (String) row[1], ((Number) row[2]).longValue(), ((Number) row[3]).longValue()));
        }
        return new SystemInfo(version(), STARTED, Duration.between(STARTED, Instant.now()).toSeconds(), database(),
                new Disk(dataDir.toString(), disk.getUsableSpace(), disk.getTotalSpace()), counts(),
                new Jvm(System.getProperty("java.version"), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                        Thread.activeCount(), runtime.availableProcessors()),
                new Features(mail.isEnabled(), inbound.isEnabled(), offsite.isConfigured(), !metricsToken.isEmpty()),
                backups.list().stream().limit(5).toList(), offsite.status(), updates.status(), quota.limits(), usage);
    }

    @PutMapping("/api/admin/quotas")
    public AttachmentQuota.Limits setQuotas(@AuthenticationPrincipal Jwt jwt, @RequestBody QuotaRequest request) {
        User admin = currentUser.admin(jwt);
        if (request.projectMb() < 0 || request.totalMb() < 0 || request.projectMb() > 10_000_000 || request.totalMb() > 10_000_000) {
            throw ApiException.badRequest("Limits must be between 0 (unlimited) and 10,000,000 MB.");
        }
        quota.setLimits(request.projectMb(), request.totalMb());
        audit.record(admin, "admin.quotas", null, request.projectMb() + " MB per project, " + request.totalMb() + " MB total");
        return quota.limits();
    }

    /** Copies the newest backup off-site right now (to test the settings). */
    @PostMapping("/api/admin/offsite/upload")
    public OffsiteBackup.Status uploadNow(@AuthenticationPrincipal Jwt jwt) {
        User admin = currentUser.admin(jwt);
        if (!offsite.isConfigured()) {
            throw ApiException.badRequest("Off-site backups are not configured. Set APP_BACKUP_S3_* or APP_BACKUP_WEBDAV_URL.");
        }
        List<BackupService.BackupFile> list = backups.list();
        BackupService.BackupFile latest = list.isEmpty() ? backups.create() : list.get(0);
        offsite.upload(backups.file(latest.name()));
        audit.record(admin, "backup.offsite", latest.name(), offsite.status().lastError());
        return offsite.status();
    }

    @PostMapping("/api/admin/update-check")
    public UpdateChecker.Update checkNow(@AuthenticationPrincipal Jwt jwt) {
        currentUser.admin(jwt);
        updates.check();
        return updates.status();
    }

    /**
     * Prometheus text format. Requires APP_METRICS_TOKEN, sent as "Authorization: Bearer &lt;token&gt;"
     * (scrape_configs: authorization: credentials: …).
     */
    @GetMapping(value = "/api/metrics", produces = "text/plain; version=0.0.4")
    @Transactional(readOnly = true)
    public ResponseEntity<String> metrics(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (metricsToken.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String presented = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (!MessageDigest.isEqual(metricsToken.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Counts c = counts();
        Runtime runtime = Runtime.getRuntime();
        StringBuilder out = new StringBuilder();
        gauge(out, "fakejira_info", "Build information", Map.of("version", version()), 1);
        gauge(out, "fakejira_uptime_seconds", "Seconds since start", null, Duration.between(STARTED, Instant.now()).toSeconds());
        gauge(out, "fakejira_users", "Active user accounts", null, c.users());
        gauge(out, "fakejira_projects", "Projects", null, c.projects());
        gauge(out, "fakejira_tasks", "Tasks", Map.of("state", "open"), c.openTasks());
        sample(out, "fakejira_tasks", Map.of("state", "done"), c.tasks() - c.openTasks());
        gauge(out, "fakejira_comments", "Comments", null, c.comments());
        gauge(out, "fakejira_attachment_bytes", "Bytes of stored attachments", null, c.attachmentBytes());
        List<BackupService.BackupFile> list = backups.list();
        gauge(out, "fakejira_last_backup_timestamp_seconds", "When the newest backup was written", null,
                list.isEmpty() ? 0 : list.get(0).createdAt().getEpochSecond());
        OffsiteBackup.Status off = offsite.status();
        gauge(out, "fakejira_offsite_backup_ok", "1 when the last off-site copy succeeded", null,
                off.lastUploadAt() == null ? 0 : off.lastError() == null ? 1 : 0);
        gauge(out, "fakejira_jvm_heap_used_bytes", "JVM heap in use", null, runtime.totalMemory() - runtime.freeMemory());
        gauge(out, "fakejira_jvm_heap_max_bytes", "JVM heap limit", null, runtime.maxMemory());
        gauge(out, "fakejira_jvm_threads", "Live threads", null, ManagementFactory.getThreadMXBean().getThreadCount());
        File disk = dataDir.toFile();
        gauge(out, "fakejira_disk_free_bytes", "Free space where data is stored", null, disk.getUsableSpace());
        out.append("# HELP fakejira_http_requests_total API requests by method and status class\n");
        out.append("# TYPE fakejira_http_requests_total counter\n");
        requests.counts().forEach((key, count) -> {
            String[] parts = key.split(" ");
            sample(out, "fakejira_http_requests_total", Map.of("method", parts[0], "status", parts[1]), count.sum());
        });
        out.append("# HELP fakejira_http_request_duration_seconds_sum Total time spent answering API requests\n");
        out.append("# TYPE fakejira_http_request_duration_seconds_sum counter\n");
        out.append("fakejira_http_request_duration_seconds_sum ").append(requests.totalDurationMs() / 1000.0).append('\n');
        gauge(out, "fakejira_http_requests_in_flight", "API requests being answered", null, requests.inFlight());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8")).body(out.toString());
    }

    private Counts counts() {
        long users = count("select count(u) from User u where u.status = com.fakejira.user.AccountStatus.ACTIVE");
        long projects = count("select count(p) from Project p");
        long tasks = count("select count(t) from Task t");
        long open = count("select count(t) from Task t where t.status <> com.fakejira.task.TaskStatus.DONE");
        long comments = count("select count(c) from Comment c");
        long files = count("select count(a) from Attachment a");
        return new Counts(users, projects, tasks, open, comments, files, attachments.totalBytes());
    }

    private long count(String jpql) {
        return em.createQuery(jpql, Long.class).getSingleResult();
    }

    private Database database() {
        try {
            return jdbc.execute((Connection connection) -> {
                var meta = connection.getMetaData();
                Long size = null;
                String product = meta.getDatabaseProductName();
                if (product.toLowerCase().contains("postgres")) {
                    size = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
                } else if (datasourceUrl.startsWith("jdbc:h2:file:")) {
                    Path file = Path.of(datasourceUrl.substring("jdbc:h2:file:".length()).split(";")[0] + ".mv.db");
                    size = Files.exists(file) ? file.toFile().length() : null;
                }
                return new Database(product, meta.getDatabaseProductVersion(), datasourceUrl.split(";")[0].replaceAll("password=[^&]*", "password=***"),
                        size, true);
            });
        } catch (RuntimeException e) {
            return new Database("?", "?", datasourceUrl.split(";")[0], null, false);
        }
    }

    private static void gauge(StringBuilder out, String name, String help, Map<String, String> labels, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
        sample(out, name, labels, value);
    }

    private static void sample(StringBuilder out, String name, Map<String, String> labels, long value) {
        out.append(name);
        if (labels != null && !labels.isEmpty()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(labels).entrySet()) {
                out.append(first ? "" : ",").append(e.getKey()).append("=\"")
                        .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                first = false;
            }
            out.append('}');
        }
        out.append(' ').append(value).append('\n');
    }
}
