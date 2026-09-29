package com.fakejira.sprint;

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

/** A task added to or removed from a sprint while it was running (scope change). */
@Entity
@Table(name = "sprint_changes")
public class SprintChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    private Sprint sprint;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 30)
    private String taskKey;

    @Column(nullable = false, length = 120)
    private String taskTitle;

    private Integer points;

    /** True when added to the sprint, false when removed. */
    @Column(nullable = false)
    private boolean added;

    @Column(nullable = false)
    private Instant changedAt = Instant.now();

    @Column(length = 40)
    private String actor;

    /** Removed because the sprint ended unfinished (not a scope change). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean carried;

    protected SprintChange() {
    }

    public static SprintChange carriedOver(Sprint sprint, Long taskId, String taskKey, String taskTitle, Integer points) {
        SprintChange change = new SprintChange(sprint, taskId, taskKey, taskTitle, points, false, null);
        change.carried = true;
        return change;
    }

    public boolean isCarried() {
        return carried;
    }

    public SprintChange(Sprint sprint, Long taskId, String taskKey, String taskTitle, Integer points, boolean added,
                        String actor) {
        this.sprint = sprint;
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.taskTitle = taskTitle;
        this.points = points;
        this.added = added;
        this.actor = actor;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public Integer getPoints() {
        return points;
    }

    public boolean isAdded() {
        return added;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getActor() {
        return actor;
    }
}
