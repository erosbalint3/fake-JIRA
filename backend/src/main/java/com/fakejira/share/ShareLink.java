package com.fakejira.share;

import com.fakejira.task.Task;
import com.fakejira.user.User;
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

/** A secret read-only link to one task, for people without an account. */
@Entity
@Table(name = "share_links")
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false, unique = true, length = 48)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false)
    private boolean includeComments;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant expiresAt;

    @Column(nullable = false)
    private int views;

    protected ShareLink() {
    }

    public ShareLink(Task task, String token, User createdBy, boolean includeComments, Instant expiresAt) {
        this.task = task;
        this.token = token;
        this.createdBy = createdBy;
        this.includeComments = includeComments;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getToken() {
        return token;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public boolean isIncludeComments() {
        return includeComments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getViews() {
        return views;
    }

    public void viewed() {
        views++;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
