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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A stopwatch on a task; at most one per user. Stopping it logs the time. */
@Entity
@Table(name = "running_timers", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class RunningTimer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false)
    private Instant startedAt;

    /** When the "still running?" reminder went out, so it is sent once. */
    private Instant remindedAt;

    protected RunningTimer() {
    }

    public RunningTimer(User user, Task task, Instant startedAt) {
        this.user = user;
        this.task = task;
        this.startedAt = startedAt;
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

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getRemindedAt() {
        return remindedAt;
    }

    public void setRemindedAt(Instant remindedAt) {
        this.remindedAt = remindedAt;
    }
}
