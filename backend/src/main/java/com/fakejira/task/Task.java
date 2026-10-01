package com.fakejira.task;

import com.fakejira.board.BoardColumn;
import com.fakejira.epic.Epic;
import com.fakejira.release.Release;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

@Entity
// Only changed columns are written, so two people editing different fields at once do not undo each other.
@DynamicUpdate
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

    /** Estimate in story points; optional. */
    private Integer storyPoints;

    /** Set for subtasks. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Task parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "epic_id")
    private Epic epic;

    /** The version this task ships in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id")
    private Release release;

    /** Explicit board column; null means the first column of the task's status. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_column_id")
    private BoardColumn boardColumn;

    /** People who follow this task's notifications in addition to reporter and assignee. */
    @ManyToMany
    @JoinTable(name = "task_watchers",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> watchers = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 10, columnDefinition = "varchar(10) default 'TASK'")
    private TaskType type = TaskType.TASK;

    /** Why the task was closed; only set while it is Done. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Resolution resolution;

    /** Planned start, for the timeline and scheduling. */
    private LocalDate startDate;

    /** Original estimate in minutes (time tracking compares logged time with it). */
    private Integer estimateMinutes;

    /** Archived tasks are hidden from boards, lists and search unless asked for. */
    private Instant archivedAt;

    /** People helping the assignee (pairing); they get the same notifications. */
    @ManyToMany
    @JoinTable(name = "task_helpers",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> helpers = new LinkedHashSet<>();

    @ManyToMany
    @JoinTable(name = "task_components",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "component_id"))
    private Set<com.fakejira.component.ProjectComponent> components = new LinkedHashSet<>();

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

    /** Also called when only related rows (e.g. custom field values) changed. */
    @PreUpdate
    public void touch() {
        updatedAt = Instant.now();
    }

    public String getKey() {
        return project.getKey() + "-" + number;
    }

    /** Moves the task to another project under a new number there. */
    public void moveTo(Project target, int newNumber) {
        this.project = target;
        this.number = newNumber;
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
            resolution = null;
        }
        if (status == TaskStatus.DONE && resolution == null) {
            resolution = Resolution.DONE;
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

    public Integer getStoryPoints() {
        return storyPoints;
    }

    public void setStoryPoints(Integer storyPoints) {
        this.storyPoints = storyPoints;
    }

    public Task getParent() {
        return parent;
    }

    public void setParent(Task parent) {
        this.parent = parent;
    }

    public Epic getEpic() {
        return epic;
    }

    public void setEpic(Epic epic) {
        this.epic = epic;
    }

    public Release getRelease() {
        return release;
    }

    public void setRelease(Release release) {
        this.release = release;
    }

    public BoardColumn getBoardColumn() {
        return boardColumn;
    }

    public void setBoardColumn(BoardColumn boardColumn) {
        this.boardColumn = boardColumn;
    }

    public Set<User> getWatchers() {
        return watchers;
    }

    public boolean isWatchedBy(User user) {
        return watchers.stream().anyMatch(watcher -> watcher.getId().equals(user.getId()));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public TaskType getType() {
        return type;
    }

    public void setType(TaskType type) {
        this.type = type == null ? TaskType.TASK : type;
    }

    public Resolution getResolution() {
        return resolution;
    }

    /** Only meaningful while Done; ignored otherwise. */
    public void setResolution(Resolution resolution) {
        this.resolution = status == TaskStatus.DONE ? (resolution == null ? Resolution.DONE : resolution) : null;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Integer getEstimateMinutes() {
        return estimateMinutes;
    }

    public void setEstimateMinutes(Integer estimateMinutes) {
        this.estimateMinutes = estimateMinutes;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Set<User> getHelpers() {
        return helpers;
    }

    public boolean isHelper(User user) {
        return helpers.stream().anyMatch(h -> h.getId().equals(user.getId()));
    }

    public Set<com.fakejira.component.ProjectComponent> getComponents() {
        return components;
    }
}
