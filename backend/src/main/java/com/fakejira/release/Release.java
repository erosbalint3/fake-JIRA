package com.fakejira.release;

import com.fakejira.project.Project;
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
import java.time.LocalDate;

/** A version of the product (fix version); tasks are assigned to the release they ship in. */
@Entity
@Table(name = "releases")
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description = "";

    private LocalDate releaseDate;

    @Column(nullable = false)
    private boolean released;

    private Instant releasedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Release() {
    }

    public Release(Project project, String name, String description, LocalDate releaseDate) {
        this.project = project;
        this.name = name;
        this.description = description;
        this.releaseDate = releaseDate;
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

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(LocalDate releaseDate) {
        this.releaseDate = releaseDate;
    }

    public boolean isReleased() {
        return released;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void markReleased(boolean released) {
        this.released = released;
        this.releasedAt = released ? Instant.now() : null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
