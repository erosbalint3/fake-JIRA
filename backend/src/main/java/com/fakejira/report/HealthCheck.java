package com.fakejira.report;

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
import java.util.Arrays;
import java.util.List;

/** A team health check: members rate each area green/amber/red and say where it is heading; votes are anonymous. */
@Entity
@Table(name = "health_checks")
public class HealthCheck {

    public static final List<String> DEFAULT_CATEGORIES = List.of(
            "Delivering value", "Speed", "Easy to release", "Health of the codebase", "Learning", "Fun",
            "Teamwork", "Support", "Mission", "Suitable process");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 120)
    private String title;

    /** Category names, one per line. */
    @Column(nullable = false, length = 2000)
    private String categories;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant closedAt;

    protected HealthCheck() {
    }

    public HealthCheck(Project project, String title, List<String> categories, User createdBy) {
        this.project = project;
        this.title = title;
        this.categories = String.join("\n", categories);
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getCategories() {
        return Arrays.stream(categories.split("\n")).filter(c -> !c.isBlank()).toList();
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
