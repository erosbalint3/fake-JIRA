package com.fakejira.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A task someone opened recently (one row per person and task). */
@Entity
@Table(name = "recent_views", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "task_id"}))
public class RecentView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Instant viewedAt = Instant.now();

    protected RecentView() {
    }

    public RecentView(Long userId, Long taskId) {
        this.userId = userId;
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Instant getViewedAt() {
        return viewedAt;
    }

    public void touch() {
        viewedAt = Instant.now();
    }
}
