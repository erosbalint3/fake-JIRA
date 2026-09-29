package com.fakejira.audit;

import com.fakejira.config.RateLimitFilter;
import com.fakejira.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;

/**
 * Records security-relevant actions for admins. Each entry is written in its own transaction, so it is kept
 * even when the surrounding request fails (for example a rejected sign-in).
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final AuditEventRepository events;
    private final RateLimitFilter clientIps;
    private final int retentionDays;

    public AuditLog(AuditEventRepository events, RateLimitFilter clientIps,
                    @Value("${app.audit.retention-days:365}") int retentionDays) {
        this.events = events;
        this.clientIps = clientIps;
        this.retentionDays = retentionDays;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User actor, String action, String target, String details) {
        record(actor == null ? null : actor.getId(), actor == null ? null : actor.getUsername(), action, target, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorName, String action, String target, String details) {
        events.save(new AuditEvent(actorId, cut(actorName, 80), action, cut(target, 200), cut(details, 1000), currentIp()));
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return cut(clientIps.clientIp(request), 64);
        }
        return null;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void prune() {
        int removed = events.deleteOlderThan(Instant.now().minus(Duration.ofDays(retentionDays)));
        if (removed > 0) {
            log.info("Removed {} audit entries older than {} days", removed, retentionDays);
        }
    }
}
