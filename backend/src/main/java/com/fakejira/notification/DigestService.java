package com.fakejira.notification;

import com.fakejira.mail.MailService;
import com.fakejira.user.EmailFrequency;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Sends one summary email per user instead of one email per event (daily or weekly on Mondays). */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    static final int MAX_ITEMS = 50;

    private final UserRepository users;
    private final NotificationRepository notifications;
    private final MailService mail;

    public DigestService(UserRepository users, NotificationRepository notifications, MailService mail) {
        this.users = users;
        this.notifications = notifications;
        this.mail = mail;
    }

    @Scheduled(cron = "${app.digest.cron:0 0 7 * * *}")
    public void scheduled() {
        if (!mail.isEnabled()) {
            return;
        }
        int sent = send(LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY);
        if (sent > 0) {
            log.info("Sent {} digest email(s)", sent);
        }
    }

    /** Returns how many digests were sent. Weekly digests are included when {@code includeWeekly}. */
    @Transactional
    public int send(boolean includeWeekly) {
        int sent = 0;
        sent += sendFor(EmailFrequency.DAILY, Duration.ofDays(1));
        if (includeWeekly) {
            sent += sendFor(EmailFrequency.WEEKLY, Duration.ofDays(7));
        }
        return sent;
    }

    private int sendFor(EmailFrequency frequency, Duration period) {
        int sent = 0;
        Instant now = Instant.now();
        for (User user : users.findByEmailFrequency(frequency)) {
            Instant since = user.getLastDigestAt() == null ? now.minus(period) : user.getLastDigestAt();
            List<Notification> items = notifications.findByRecipientIdAndCreatedAtAfterOrderByCreatedAtAsc(user.getId(), since);
            user.setLastDigestAt(now);
            if (items.isEmpty()) {
                continue;
            }
            StringBuilder body = new StringBuilder("Hi ").append(user.getName()).append(",\n\n")
                    .append("Here is what happened in FakeJIRA ")
                    .append(frequency == EmailFrequency.DAILY ? "since yesterday" : "this past week").append(":\n\n");
            items.stream().limit(MAX_ITEMS).forEach(n -> body.append("• ").append(n.getMessage())
                    .append(n.getTaskId() == null ? "" : "\n  " + mail.link("/tasks/" + n.getTaskId())).append("\n"));
            if (items.size() > MAX_ITEMS) {
                body.append("… and ").append(items.size() - MAX_ITEMS).append(" more.\n");
            }
            body.append("\nAll notifications: ").append(mail.link("/notifications"))
                    .append("\n\nChange how often you get these emails in your FakeJIRA profile.");
            mail.send(user.getEmail(), "[FakeJIRA] Your " + (frequency == EmailFrequency.DAILY ? "daily" : "weekly")
                    + " digest: " + items.size() + " update" + (items.size() == 1 ? "" : "s"), body.toString());
            sent++;
        }
        return sent;
    }
}
