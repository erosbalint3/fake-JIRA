package com.fakejira.report;

import com.fakejira.task.TaskPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Service-level targets for one priority in a project: time to first response and to resolution. */
@Entity
@Table(name = "sla_policies", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "priority"}))
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority;

    /** Hours until someone other than the reporter comments or work starts; null for no target. */
    private Integer responseHours;

    /** Hours until the task is done; null for no target. */
    private Integer resolveHours;

    protected SlaPolicy() {
    }

    public SlaPolicy(Long projectId, TaskPriority priority) {
        this.projectId = projectId;
        this.priority = priority;
    }

    public Long getProjectId() {
        return projectId;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public Integer getResponseHours() {
        return responseHours;
    }

    public Integer getResolveHours() {
        return resolveHours;
    }

    public void setTargets(Integer responseHours, Integer resolveHours) {
        this.responseHours = responseHours;
        this.resolveHours = resolveHours;
    }
}
