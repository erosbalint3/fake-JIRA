package com.fakejira.task;

import com.fakejira.user.User;
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

/** "source {type} target", e.g. WR-1 blocks WR-4. */
@Entity
@Table(name = "task_links", uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "target_id", "link_type"}))
public class TaskLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Task source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private Task target;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 20)
    private LinkType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TaskLink() {
    }

    public TaskLink(Task source, Task target, LinkType type, User createdBy) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Task getSource() {
        return source;
    }

    public Task getTarget() {
        return target;
    }

    public LinkType getType() {
        return type;
    }
}
