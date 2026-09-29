package com.fakejira.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One execution of a rule on a task (the rule's audit log). {@code taskId} marks scheduled rules' firings too. */
@Entity
@Table(name = "automation_runs")
public class RuleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ruleId;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 30)
    private String taskKey;

    @Column(nullable = false)
    private Instant ranAt = Instant.now();

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false, length = 300)
    private String message;

    protected RuleRun() {
    }

    public RuleRun(Long ruleId, Long taskId, String taskKey, boolean success, String message) {
        this.ruleId = ruleId;
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.success = success;
        this.message = message.length() > 300 ? message.substring(0, 299) + "…" : message;
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public Instant getRanAt() {
        return ranAt;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
