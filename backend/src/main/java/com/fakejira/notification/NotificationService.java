package com.fakejira.notification;

import com.fakejira.task.Task;
import com.fakejira.user.User;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Notifies {@code recipient} about something {@code actor} did to {@code task}.
     * Nobody is notified about their own actions.
     */
    public void notify(User recipient, User actor, Task task, String action) {
        if (recipient == null || recipient.getId().equals(actor.getId())) {
            return;
        }
        String message = actor.getUsername() + " " + action + " " + task.getKey() + " · " + task.getTitle();
        if (message.length() > 300) {
            message = message.substring(0, 297) + "...";
        }
        notifications.save(new Notification(recipient, message, task.getId()));
    }
}
