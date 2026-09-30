package com.fakejira.notification;

import com.fakejira.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(nullable = false, length = 300)
    private String message;

    /** Plain id rather than a foreign key so notifications survive task deletion. */
    private Long taskId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Hidden from the inbox until then; it comes back unread. */
    private Instant snoozedUntil;

    /** When it was last (re)delivered; set when a snooze ends so it sorts to the top. Null means createdAt. */
    private Instant activeAt;

    /** Triaged as done: kept in the Done tab but out of the inbox. */
    private Instant archivedAt;

    protected Notification() {
    }

    public Notification(User recipient, String message, Long taskId) {
        this.recipient = recipient;
        this.message = message;
        this.taskId = taskId;
    }

    public Long getId() {
        return id;
    }

    public User getRecipient() {
        return recipient;
    }

    public String getMessage() {
        return message;
    }

    public Long getTaskId() {
        return taskId;
    }

    public boolean isRead() {
        return read;
    }

    public void markRead() {
        this.read = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSnoozedUntil() {
        return snoozedUntil;
    }

    public Instant getActiveAt() {
        return activeAt == null ? createdAt : activeAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void snooze(Instant until) {
        this.snoozedUntil = until;
        this.archivedAt = null;
        this.read = true;
    }

    /** Ends a snooze: back in the inbox, on top and unread. */
    public void wake(Instant now) {
        this.snoozedUntil = null;
        this.activeAt = now;
        this.read = false;
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.snoozedUntil = null;
        this.read = true;
    }

    public void unarchive() {
        this.archivedAt = null;
    }
}
