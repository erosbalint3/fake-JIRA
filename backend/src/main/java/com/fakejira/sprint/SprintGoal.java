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

/** One checkable goal of a sprint; the review shows how many were met. */
@Entity
@Table(name = "sprint_goals")
public class SprintGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    private Sprint sprint;

    @Column(nullable = false, length = 200)
    private String text;

    @Column(nullable = false)
    private boolean done;

    @Column(nullable = false)
    private int position;

    protected SprintGoal() {
    }

    public SprintGoal(Sprint sprint, String text, int position) {
        this.sprint = sprint;
        this.text = text;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Sprint getSprint() {
        return sprint;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public int getPosition() {
        return position;
    }
}
