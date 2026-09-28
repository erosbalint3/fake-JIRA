package com.fakejira.notification;

import com.fakejira.mail.MailService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class NotificationMailer {

    private final MailService mail;

    public NotificationMailer(MailService mail) {
        this.mail = mail;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNotification(NotificationCreated event) {
        if (!event.emailEnabled() || !mail.isEnabled()) {
            return;
        }
        String link = event.taskId() == null ? mail.link("/notifications") : mail.link("/tasks/" + event.taskId());
        String body = event.message() + "\n\nOpen in FakeJIRA: " + link
                + "\n\nYou are receiving this because email notifications are turned on in your FakeJIRA profile.";
        mail.send(event.email(), "[FakeJIRA] " + event.message(), body);
    }
}
