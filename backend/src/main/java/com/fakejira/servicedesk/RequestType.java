package com.fakejira.servicedesk;

import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A kind of request people can file on the portal ("Report a bug", "Ask a question"), with its form. */
@Entity
@Table(name = "request_types")
public class RequestType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 300)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskType taskType = TaskType.TASK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    /** The form's extra fields as JSON: [{id, label, kind, required, options, help}]. */
    @Column(nullable = false, length = 10000)
    private String fields = "[]";

    @Column(nullable = false)
    private int position;

    protected RequestType() {
    }

    public RequestType(Long projectId, int position) {
        this.projectId = projectId;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public String getFields() {
        return fields;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void update(String name, String description, TaskType taskType, TaskPriority priority, String fields) {
        this.name = name;
        this.description = description;
        this.taskType = taskType;
        this.priority = priority;
        this.fields = fields;
    }
}
