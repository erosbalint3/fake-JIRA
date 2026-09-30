package com.fakejira.integration;

import com.fakejira.task.Task;
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

/** The latest result of one CI job or pipeline for a task (matched by the task key in the branch or commit). */
@Entity
@Table(name = "build_statuses", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "source", "name"}))
public class BuildStatus {

    /** pending, running, success, failure or cancelled. */
    public static final java.util.List<String> STATES = java.util.List.of("pending", "running", "success", "failure", "cancelled");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    /** GitHub, GitLab, Gitea or CI. */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 12)
    private String state;

    @Column(length = 500)
    private String url;

    /** Branch or ref the build ran on. */
    @Column(length = 200)
    private String ref;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected BuildStatus() {
    }

    public BuildStatus(Task task, String source, String name) {
        this.task = task;
        this.source = source;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getSource() {
        return source;
    }

    public String getName() {
        return name;
    }

    public String getState() {
        return state;
    }

    public String getUrl() {
        return url;
    }

    public String getRef() {
        return ref;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String state, String url, String ref) {
        this.state = state;
        this.url = url;
        this.ref = ref;
        this.updatedAt = Instant.now();
    }
}
