package com.fakejira.integration;

import com.fakejira.task.Task;
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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A commit or pull request on GitHub that mentions a task key. */
@Entity
@Table(name = "dev_links", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "kind", "external_id"}))
public class DevLink {

    public enum Kind { COMMIT, PULL_REQUEST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** Commit SHA, or "owner/repo#number" for pull requests. */
    @Column(name = "external_id", nullable = false, length = 150)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false, length = 300)
    private String title;

    /** For pull requests: open, closed or merged. */
    @Column(length = 20)
    private String state;

    @Column(length = 80)
    private String author;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected DevLink() {
    }

    public DevLink(Task task, Kind kind, String externalId) {
        this.task = task;
        this.kind = kind;
        this.externalId = externalId;
    }

    public void update(String url, String title, String state, String author) {
        this.url = url;
        this.title = title.length() > 300 ? title.substring(0, 297) + "..." : title;
        this.state = state;
        this.author = author;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public Kind getKind() {
        return kind;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getState() {
        return state;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
