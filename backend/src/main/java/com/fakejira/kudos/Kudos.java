package com.fakejira.kudos;

import com.fakejira.project.Project;
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

/** A thank-you to a teammate for work on a finished task. */
@Entity
@Table(name = "kudos")
public class Kudos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_id", nullable = false)
    private User from;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_id", nullable = false)
    private User to;

    @Column(nullable = false, length = 280)
    private String message;

    @Column(length = 8)
    private String emoji;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Kudos() {
    }

    public Kudos(Project project, Task task, User from, User to, String message, String emoji) {
        this.project = project;
        this.task = task;
        this.from = from;
        this.to = to;
        this.message = message;
        this.emoji = emoji;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public Task getTask() {
        return task;
    }

    public User getFrom() {
        return from;
    }

    public User getTo() {
        return to;
    }

    public String getMessage() {
        return message;
    }

    public String getEmoji() {
        return emoji;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
