package com.fakejira.task;

import com.fakejira.admin.AppSettings;
import com.fakejira.common.ApiException;
import com.fakejira.project.Project;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Optional limits on attachment storage, per project and for the whole server (0 = unlimited). */
@Component
public class AttachmentQuota {

    static final String PROJECT_MB = "quota.project.mb";
    static final String TOTAL_MB = "quota.total.mb";
    private static final long MB = 1024L * 1024L;

    private final AppSettings settings;
    private final AttachmentRepository attachments;

    public AttachmentQuota(AppSettings settings, AttachmentRepository attachments) {
        this.settings = settings;
        this.attachments = attachments;
    }

    public record Limits(long projectMb, long totalMb) {
    }

    public Limits limits() {
        return new Limits(number(PROJECT_MB), number(TOTAL_MB));
    }

    public void setLimits(long projectMb, long totalMb) {
        settings.put(PROJECT_MB, String.valueOf(Math.max(0, projectMb)));
        settings.put(TOTAL_MB, String.valueOf(Math.max(0, totalMb)));
    }

    /** Throws when adding {@code bytes} to {@code project} would go over a limit. */
    public void check(Project project, long bytes) {
        Limits limits = limits();
        if (limits.projectMb() > 0) {
            long used = attachments.bytesInProject(project.getId());
            if (used + bytes > limits.projectMb() * MB) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, project.getKey() + " has used " + mb(used) + " of its "
                        + limits.projectMb() + " MB for attachments. Delete old files or ask an admin to raise the limit.");
            }
        }
        if (limits.totalMb() > 0) {
            long used = attachments.totalBytes();
            if (used + bytes > limits.totalMb() * MB) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The server's attachment storage is full ("
                        + mb(used) + " of " + limits.totalMb() + " MB). Ask an admin to free space or raise the limit.");
            }
        }
    }

    private long number(String key) {
        try {
            return Long.parseLong(settings.get(key).orElse("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (double) MB);
    }
}
