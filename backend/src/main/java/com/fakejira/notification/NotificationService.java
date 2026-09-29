package com.fakejira.notification;

import com.fakejira.events.LiveEvent;
import com.fakejira.task.Task;
import com.fakejira.user.EmailFrequency;
import com.fakejira.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository notifications, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.events = events;
    }

    /**
     * Notifies {@code recipient} about something {@code actor} did to {@code task}.
     * Nobody is notified about their own actions.
     */
    public void notify(User recipient, User actor, Task task, String action) {
        notify(recipient, actor, actor.getUsername() + " " + action + " " + task.getKey() + " · " + task.getTitle(),
                task.getId());
    }

    public void notify(User recipient, User actor, String message, Long taskId) {
        if (recipient == null || recipient.getId().equals(actor.getId())) {
            return;
        }
        if (message.length() > 300) {
            message = message.substring(0, 297) + "...";
        }
        notifications.save(new Notification(recipient, message, taskId));
        events.publishEvent(new LiveEvent(Set.of(recipient.getId()), "notification", Map.of()));
        events.publishEvent(new NotificationCreated(recipient.getId(), recipient.getEmail(),
                recipient.getEmailFrequency() == EmailFrequency.INSTANT, message, taskId));
    }
}
