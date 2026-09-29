package com.fakejira.notification;

import com.fakejira.mail.MailService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class NotificationMailer {

    private final MailService mail;
    private final com.fakejira.mail.InboundMail inbound;

    public NotificationMailer(MailService mail, com.fakejira.mail.InboundMail inbound) {
        this.mail = mail;
        this.inbound = inbound;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNotification(NotificationCreated event) {
        if (!event.emailEnabled() || !mail.isEnabled()) {
            return;
        }
        String link = event.taskId() == null ? mail.link("/notifications") : mail.link("/tasks/" + event.taskId());
        String replyTo = inbound.replyAddress(event.taskId(), event.recipientId()).orElse(null);
        String body = event.message() + "\n\nOpen in FakeJIRA: " + link
                + (replyTo == null ? "" : "\n\nReply to this email to add a comment.")
                + "\n\nYou are receiving this because email notifications are turned on in your FakeJIRA profile.";
        mail.send(event.email(), "[FakeJIRA] " + event.message(), body, replyTo);
    }
}
