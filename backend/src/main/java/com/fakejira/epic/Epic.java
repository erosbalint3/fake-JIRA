package com.fakejira.epic;

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

/** A larger goal grouping several tasks, shown on the roadmap. */
@Entity
@Table(name = "epics")
public class Epic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description = "";

    /** Index into the fixed categorical chart palette (0-7), assigned in creation order. */
    @Column(nullable = false)
    private int colorIndex;

    private LocalDate startDate;

    private LocalDate dueDate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Epic() {
    }

    public Epic(Project project, String name, String description, int colorIndex, LocalDate startDate, LocalDate dueDate) {
        this.project = project;
        this.name = name;
        this.description = description;
        this.colorIndex = colorIndex;
        this.startDate = startDate;
        this.dueDate = dueDate;
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

    public int getColorIndex() {
        return colorIndex;
    }

    public void setColorIndex(int colorIndex) {
        this.colorIndex = colorIndex;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
