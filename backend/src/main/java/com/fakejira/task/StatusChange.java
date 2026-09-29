package com.fakejira.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** One status transition, for flow and cycle-time reports. {@code fromStatus} is null when the task was created. */
@Entity
@Table(name = "status_changes", indexes = {
        @Index(name = "idx_status_change_project", columnList = "projectId,changedAt"),
        @Index(name = "idx_status_change_task", columnList = "taskId")})
public class StatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus toStatus;

    @Column(nullable = false)
    private Instant changedAt = Instant.now();

    protected StatusChange() {
    }

    public StatusChange(Long taskId, Long projectId, TaskStatus fromStatus, TaskStatus toStatus) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public TaskStatus getFromStatus() {
        return fromStatus;
    }

    public TaskStatus getToStatus() {
        return toStatus;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
