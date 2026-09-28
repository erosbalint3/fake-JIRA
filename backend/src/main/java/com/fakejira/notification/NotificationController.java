package com.fakejira.notification;

import com.fakejira.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public record NotificationResponse(Long id, String message, Long taskId, boolean read, Instant createdAt) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.getId(), n.getMessage(), n.getTaskId(), n.isRead(), n.getCreatedAt());
        }
    }

    public record NotificationList(long unread, List<NotificationResponse> items) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public NotificationList list(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        List<NotificationResponse> items = notifications.findTop100ByRecipientIdOrderByCreatedAtDesc(userId)
                .stream().map(NotificationResponse::of).toList();
        return new NotificationList(notifications.countByRecipientIdAndReadFalse(userId), items);
    }

    @GetMapping("/unread-count")
    public long unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return notifications.countByRecipientIdAndReadFalse(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        notifications.findByIdAndRecipientId(id, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> ApiException.notFound("Notification not found."))
                .markRead();
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(Long.valueOf(jwt.getSubject()));
    }
}
