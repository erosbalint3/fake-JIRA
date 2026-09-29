package com.fakejira.audit;

import com.fakejira.common.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Admins browse the audit log, newest first, filtered by action or free text. */
@RestController
public class AuditController {

    private static final int PAGE_SIZE = 50;

    private final AuditEventRepository events;
    private final CurrentUser currentUser;

    public AuditController(AuditEventRepository events, CurrentUser currentUser) {
        this.events = events;
        this.currentUser = currentUser;
    }

    public record AuditEntry(Long id, Instant createdAt, String actor, String action, String target, String details,
                             String ip) {
        static AuditEntry of(AuditEvent e) {
            return new AuditEntry(e.getId(), e.getCreatedAt(), e.getActorName(), e.getAction(), e.getTarget(),
                    e.getDetails(), e.getIp());
        }
    }

    public record AuditPage(List<AuditEntry> items, long total, int page, int pages, List<String> actions) {
    }

    @GetMapping("/api/admin/audit")
    @Transactional(readOnly = true)
    public AuditPage list(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(required = false) String action,
                          @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page) {
        currentUser.admin(jwt);
        String filter = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<AuditEvent> result = events.search(action == null || action.isBlank() ? null : action, filter,
                PageRequest.of(Math.max(0, page), PAGE_SIZE));
        return new AuditPage(result.map(AuditEntry::of).getContent(), result.getTotalElements(), result.getNumber(),
                result.getTotalPages(), events.actions());
    }
}
