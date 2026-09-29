package com.fakejira.task;

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

/** One line in a task's history, e.g. "changed status from To do to In progress". */
@Entity
@Table(name = "task_activity")
public class TaskActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Column(nullable = false, length = 300)
    private String message;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Text before and after a description change, for showing what changed. */
    @Column(length = 5000)
    private String beforeText;

    @Column(length = 5000)
    private String afterText;

    protected TaskActivity() {
    }

    public TaskActivity(Task task, User actor, String message, String beforeText, String afterText) {
        this(task, actor, message);
        this.beforeText = beforeText;
        this.afterText = afterText;
    }

    public String getBeforeText() {
        return beforeText;
    }

    public String getAfterText() {
        return afterText;
    }

    public TaskActivity(Task task, User actor, String message) {
        this.task = task;
        this.actor = actor;
        this.message = message.length() > 300 ? message.substring(0, 297) + "..." : message;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public User getActor() {
        return actor;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
