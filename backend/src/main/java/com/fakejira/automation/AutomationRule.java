package com.fakejira.automation;

import com.fakejira.project.Project;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * "When [trigger] and the task matches [condition], do [actions]". Rules act with their creator's permissions;
 * scheduled rules check their condition every few minutes (SLA escalation, stale-task cleanup).
 */
@Entity
@Table(name = "automation_rules")
public class AutomationRule {

    public enum Trigger { CREATED, UPDATED, STATUS_CHANGED, ASSIGNED, COMMENTED, SCHEDULED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private Trigger trigger;

    /** For STATUS_CHANGED: only when the task moves to this status (null = any). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskStatus triggerStatus;

    /** Optional FQL the task must match ("type = bug AND priority >= high"); required for scheduled rules. */
    @Column(name = "condition_query", nullable = false, length = 1000)
    private String condition = "";

    /** JSON list of actions: [{"type": "assign", "value": "least_loaded"}, …]. */
    @Column(nullable = false, length = 4000)
    private String actions = "[]";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastRunAt;

    @Column(nullable = false)
    private int runCount;

    @Column(length = 300)
    private String lastError;

    /** Next position for round-robin assignment. */
    @Column(nullable = false)
    private int roundRobin;

    protected AutomationRule() {
    }

    public AutomationRule(Project project, User owner) {
        this.project = project;
        this.owner = owner;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public User getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public void setTrigger(Trigger trigger) {
        this.trigger = trigger;
    }

    public TaskStatus getTriggerStatus() {
        return triggerStatus;
    }

    public void setTriggerStatus(TaskStatus triggerStatus) {
        this.triggerStatus = triggerStatus;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getActions() {
        return actions;
    }

    public void setActions(String actions) {
        this.actions = actions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public int getRunCount() {
        return runCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void ran(String error) {
        lastRunAt = Instant.now();
        runCount++;
        lastError = error == null ? null : error.length() > 300 ? error.substring(0, 299) + "…" : error;
    }

    public int nextRoundRobin(int size) {
        int index = Math.floorMod(roundRobin, size);
        roundRobin = index + 1;
        return index;
    }
}
