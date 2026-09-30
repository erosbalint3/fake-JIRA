package com.fakejira.ops;

import com.fakejira.admin.AppSettings;
import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskCleanup;
import com.fakejira.task.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Data retention: archive finished work, delete long-archived tasks (with their comments, files and history),
 * and clear old notifications and audit entries. Each rule is off (0) unless an admin sets a number of days.
 * Runs nightly; admins can preview the effect and run it at once.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    static final String KEY = "retention.policy";

    /** Days; 0 keeps things forever (audit entries default to APP_AUDIT_RETENTION_DAYS). */
    public record Policy(int archiveDoneAfterDays, int deleteArchivedAfterDays, int deleteNotificationsAfterDays,
                         int deleteAuditAfterDays) {
    }

    /** How many items the policy affects (preview) or affected (run). */
    public record Counts(int tasksArchived, int tasksDeleted, long notificationsDeleted, long auditEntriesDeleted) {
    }

    private final AppSettings settings;
    private final ObjectMapper json;
    private final TaskRepository tasks;
    private final TaskCleanup cleanup;
    private final NotificationRepository notifications;
    private final AuditLog audit;
    private final LiveEvents live;
    private final TransactionTemplate tx;

    public RetentionService(AppSettings settings, ObjectMapper json, TaskRepository tasks, TaskCleanup cleanup,
                            NotificationRepository notifications, AuditLog audit, LiveEvents live, TransactionTemplate tx) {
        this.settings = settings;
        this.json = json;
        this.tasks = tasks;
        this.cleanup = cleanup;
        this.notifications = notifications;
        this.audit = audit;
        this.live = live;
        this.tx = tx;
    }

    public Policy policy() {
        return settings.get(KEY).map(text -> {
            try {
                return json.readValue(text, Policy.class);
            } catch (JsonProcessingException e) {
                return null;
            }
        }).orElse(new Policy(0, 0, 0, audit.defaultRetentionDays()));
    }

    public Policy save(Policy policy) {
        for (int days : new int[]{policy.archiveDoneAfterDays(), policy.deleteArchivedAfterDays(),
                policy.deleteNotificationsAfterDays(), policy.deleteAuditAfterDays()}) {
            if (days < 0 || days > 36500) {
                throw ApiException.badRequest("Use 0 (keep) or a number of days up to 100 years.");
            }
        }
        if (policy.deleteArchivedAfterDays() > 0 && policy.deleteArchivedAfterDays() < 30) {
            throw ApiException.field("deleteArchivedAfterDays", "Keep archived tasks for at least 30 days before deleting them.");
        }
        if (policy.deleteAuditAfterDays() > 0 && policy.deleteAuditAfterDays() < 30) {
            throw ApiException.field("deleteAuditAfterDays", "Keep the audit log for at least 30 days.");
        }
        try {
            settings.put(KEY, json.writeValueAsString(policy));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return policy;
    }

    public Counts preview() {
        Policy policy = policy();
        Instant now = Instant.now();
        return tx.execute(status -> new Counts(
                policy.archiveDoneAfterDays() > 0 ? tasks.doneBefore(now.minus(days(policy.archiveDoneAfterDays()))).size() : 0,
                policy.deleteArchivedAfterDays() > 0 ? tasks.archivedBefore(now.minus(days(policy.deleteArchivedAfterDays()))).size() : 0,
                policy.deleteNotificationsAfterDays() > 0
                        ? notifications.countOlderThan(now.minus(days(policy.deleteNotificationsAfterDays()))) : 0,
                policy.deleteAuditAfterDays() > 0 ? audit.countOlderThan(policy.deleteAuditAfterDays()) : 0));
    }

    @Scheduled(cron = "${app.retention.cron:0 40 2 * * *}")
    public void nightly() {
        try {
            Counts counts = run();
            if (counts.tasksArchived() + counts.tasksDeleted() + counts.notificationsDeleted() + counts.auditEntriesDeleted() > 0) {
                log.info("Retention: {}", counts);
            }
        } catch (RuntimeException e) {
            log.error("Retention run failed", e);
        }
    }

    public synchronized Counts run() {
        Policy policy = policy();
        Instant now = Instant.now();
        int archived = 0;
        if (policy.archiveDoneAfterDays() > 0) {
            Instant before = now.minus(days(policy.archiveDoneAfterDays()));
            Integer count = tx.execute(status -> {
                List<Task> done = tasks.doneBefore(before);
                done.forEach(t -> t.setArchivedAt(now));
                return done.size();
            });
            archived = count == null ? 0 : count;
        }
        int deleted = 0;
        if (policy.deleteArchivedAfterDays() > 0) {
            Instant before = now.minus(days(policy.deleteArchivedAfterDays()));
            List<Long> ids = tx.execute(status -> tasks.archivedBefore(before).stream().map(Task::getId).toList());
            for (Long id : ids == null ? List.<Long>of() : ids) {
                // One transaction per task, so one problem does not stop the rest.
                Boolean done = tx.execute(status -> tasks.findById(id).map(task -> {
                    var project = task.getProject();
                    cleanup.delete(task);
                    live.taskDeleted(project, id);
                    return true;
                }).orElse(false));
                if (Boolean.TRUE.equals(done)) deleted++;
            }
        }
        long notificationsDeleted = 0;
        if (policy.deleteNotificationsAfterDays() > 0) {
            Instant before = now.minus(days(policy.deleteNotificationsAfterDays()));
            Integer count = tx.execute(status -> notifications.deleteOlderThan(before));
            notificationsDeleted = count == null ? 0 : count;
        }
        long auditDeleted = policy.deleteAuditAfterDays() > 0 ? audit.prune(policy.deleteAuditAfterDays()) : 0;
        Counts counts = new Counts(archived, deleted, notificationsDeleted, auditDeleted);
        if (archived + deleted + notificationsDeleted + auditDeleted > 0) {
            audit.record(null, "retention", "retention.run", null, "archived " + archived + ", deleted " + deleted
                    + " tasks, " + notificationsDeleted + " notifications, " + auditDeleted + " audit entries");
        }
        return counts;
    }

    private static Duration days(int days) {
        return Duration.ofDays(days);
    }
}
