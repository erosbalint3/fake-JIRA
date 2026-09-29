package com.fakejira.template;

import com.fakejira.project.Project;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskType;
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

import java.util.Arrays;
import java.util.List;

/** A reusable starting point for new tasks, e.g. "Bug report" with Steps / Expected / Actual. */
@Entity
@Table(name = "task_templates")
public class TaskTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskType type = TaskType.TASK;

    /** Pre-filled title, e.g. "Bug: ". */
    @Column(nullable = false, length = 120)
    private String title = "";

    @Column(nullable = false, length = 5000)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    /** Comma-separated. */
    @Column(nullable = false, length = 400)
    private String labels = "";

    /** One checklist item per line. */
    @Column(nullable = false, length = 4000)
    private String checklist = "";

    private Integer storyPoints;

    protected TaskTemplate() {
    }

    public TaskTemplate(Project project) {
        this.project = project;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public TaskType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public List<String> getLabels() {
        return labels.isBlank() ? List.of() : Arrays.stream(labels.split(",")).map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    public List<String> getChecklist() {
        return checklist.isBlank() ? List.of() : Arrays.stream(checklist.split("\n")).map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    public Integer getStoryPoints() {
        return storyPoints;
    }

    public void update(String name, TaskType type, String title, String description, TaskPriority priority,
                       List<String> labels, List<String> checklist, Integer storyPoints) {
        this.name = name;
        this.type = type == null ? TaskType.TASK : type;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.priority = priority == null ? TaskPriority.MEDIUM : priority;
        this.labels = labels == null ? "" : String.join(",", labels);
        this.checklist = checklist == null ? "" : String.join("\n", checklist);
        this.storyPoints = storyPoints;
    }
}
