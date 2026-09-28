package com.fakejira.task;

import com.fakejira.project.Project;
import com.fakejira.sprint.Sprint;
import com.fakejira.user.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable at the database level only so pre-projects databases can be upgraded in place
    // (see LegacyDataMigration); every task gets a project.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    /** Sequence number within the project; the key is PROJECT-number. */
    @Column(name = "task_number")
    private Integer number;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 5000)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.TODO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sprint_id")
    private Sprint sprint;

    private LocalDate dueDate;

    @ElementCollection
    @CollectionTable(name = "task_labels", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "label", length = 30)
    private Set<String> labels = new TreeSet<>();

    /** When the task last entered DONE; drives the sprint burndown. */
    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Task() {
    }

    public Task(Project project, int number, String title, String description, TaskPriority priority, User reporter) {
        this.project = project;
        this.number = number;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.reporter = reporter;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getKey() {
        return project.getKey() + "-" + number;
    }

    public boolean isReporter(User user) {
        return reporter.getId().equals(user.getId());
    }

    public boolean isAssignee(User user) {
        return assignee != null && assignee.getId().equals(user.getId());
    }

    public void setStatus(TaskStatus status) {
        if (status == TaskStatus.DONE && this.status != TaskStatus.DONE) {
            completedAt = Instant.now();
        } else if (status != TaskStatus.DONE) {
            completedAt = null;
        }
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public Integer getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public User getReporter() {
        return reporter;
    }

    public User getAssignee() {
        return assignee;
    }

    public void setAssignee(User assignee) {
        this.assignee = assignee;
    }

    public Sprint getSprint() {
        return sprint;
    }

    public void setSprint(Sprint sprint) {
        this.sprint = sprint;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Set<String> getLabels() {
        return labels;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
