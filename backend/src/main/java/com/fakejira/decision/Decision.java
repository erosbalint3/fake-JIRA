package com.fakejira.decision;

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

/** An entry in a project's decision log, optionally made on a task. */
@Entity
@Table(name = "decisions")
public class Decision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @Column(nullable = false, length = 500)
    private String text;

    /** Why, alternatives considered, etc. */
    @Column(nullable = false, length = 2000)
    private String context = "";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_by_id", nullable = false)
    private User decidedBy;

    @Column(nullable = false, updatable = false)
    private Instant decidedAt = Instant.now();

    protected Decision() {
    }

    public Decision(Project project, Task task, String text, String context, User decidedBy) {
        this.project = project;
        this.task = task;
        this.text = text;
        this.context = context == null ? "" : context;
        this.decidedBy = decidedBy;
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

    public void setTask(Task task) {
        this.task = task;
    }

    public String getText() {
        return text;
    }

    public String getContext() {
        return context;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
