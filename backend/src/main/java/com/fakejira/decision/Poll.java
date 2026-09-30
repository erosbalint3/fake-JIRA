package com.fakejira.decision;

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
import java.util.Arrays;
import java.util.List;

/** A quick vote on a task ("Which layout?"); closing it can record the result as a decision. */
@Entity
@Table(name = "polls")
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false, length = 200)
    private String question;

    /** One option per line. */
    @Column(nullable = false, length = 1200)
    private String options;

    /** Whether people may pick more than one option. */
    @Column(nullable = false)
    private boolean multiple;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant closedAt;

    protected Poll() {
    }

    public Poll(Task task, String question, List<String> options, boolean multiple, User createdBy) {
        this.task = task;
        this.question = question;
        this.options = String.join("\n", options);
        this.multiple = multiple;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getOptions() {
        return Arrays.asList(options.split("\n"));
    }

    public boolean isMultiple() {
        return multiple;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void close() {
        this.closedAt = Instant.now();
    }
}
