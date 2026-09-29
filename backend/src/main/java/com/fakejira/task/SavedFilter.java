package com.fakejira.task;

import com.fakejira.project.Project;
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

/** A named backlog filter; the query is the backlog URL's search string (e.g. "assignee=me&label=bug"). */
@Entity
@Table(name = "saved_filters")
public class SavedFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "filter_query", nullable = false, length = 500)
    private String query;

    /** Shared filters are visible to every member of the project. */
    @Column(nullable = false)
    private boolean shared;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SavedFilter() {
    }

    public SavedFilter(User owner, Project project, String name, String query, boolean shared) {
        this.owner = owner;
        this.project = project;
        this.name = name;
        this.query = query;
        this.shared = shared;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public String getQuery() {
        return query;
    }

    public boolean isShared() {
        return shared;
    }
}
