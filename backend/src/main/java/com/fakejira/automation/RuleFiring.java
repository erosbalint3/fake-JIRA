package com.fakejira.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A scheduled rule already acted on this task; it fires again only after the task stopped matching. */
@Entity
@Table(name = "automation_firings", uniqueConstraints = @UniqueConstraint(columnNames = {"rule_id", "task_id"}))
public class RuleFiring {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Instant firedAt = Instant.now();

    protected RuleFiring() {
    }

    public RuleFiring(Long ruleId, Long taskId) {
        this.ruleId = ruleId;
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
