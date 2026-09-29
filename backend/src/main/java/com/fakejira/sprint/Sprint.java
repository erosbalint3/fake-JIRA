package com.fakejira.sprint;

import com.fakejira.project.Project;
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

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "sprints")
public class Sprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 500)
    private String goal = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SprintState state = SprintState.PLANNED;

    private LocalDate startDate;

    private LocalDate endDate;

    private Instant completedAt;

    /** Unfinished tasks sent back to the backlog on completion; kept so the burndown stays honest. */
    @Column(nullable = false)
    private int carriedOver;

    /** Story points of the carried-over tasks at completion (for velocity). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int carriedOverPoints;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Sprint() {
    }

    public Sprint(Project project, String name, String goal, LocalDate startDate, LocalDate endDate) {
        this.project = project;
        this.name = name;
        this.goal = goal;
        this.startDate = startDate;
        this.endDate = endDate;
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

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public SprintState getState() {
        return state;
    }

    public void setState(SprintState state) {
        this.state = state;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public int getCarriedOver() {
        return carriedOver;
    }

    public void setCarriedOver(int carriedOver) {
        this.carriedOver = carriedOver;
    }

    public int getCarriedOverPoints() {
        return carriedOverPoints;
    }

    public void setCarriedOverPoints(int carriedOverPoints) {
        this.carriedOverPoints = carriedOverPoints;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
