package com.fakejira.personal;

import com.fakejira.task.Task;
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

/** "Remind me about this": delivered to the owner as a notification at {@code remindAt}. */
@Entity
@Table(name = "reminders")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Optional: a reminder can be about nothing in particular. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @Column(nullable = false, length = 200)
    private String note = "";

    @Column(nullable = false)
    private Instant remindAt;

    private Instant sentAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Reminder() {
    }

    public Reminder(User user, Task task, String note, Instant remindAt) {
        this.user = user;
        this.task = task;
        this.note = note;
        this.remindAt = remindAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Task getTask() {
        return task;
    }

    public String getNote() {
        return note;
    }

    public Instant getRemindAt() {
        return remindAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void markSent(Instant at) {
        this.sentAt = at;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
