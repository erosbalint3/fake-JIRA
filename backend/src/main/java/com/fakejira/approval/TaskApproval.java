package com.fakejira.approval;

import com.fakejira.task.Task;
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

import java.time.Instant;

/** A sign-off someone asked a named person for; a column can require all of a task's approvals first. */
@Entity
@Table(name = "task_approvals")
public class TaskApproval {

    public enum State { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approver_id", nullable = false)
    private User approver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private State state = State.PENDING;

    /** What the requester asked, then the approver's note once decided. */
    @Column(nullable = false, length = 500)
    private String request = "";

    @Column(length = 500)
    private String decisionNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant decidedAt;

    protected TaskApproval() {
    }

    public TaskApproval(Task task, User approver, User requestedBy, String request) {
        this.task = task;
        this.approver = approver;
        this.requestedBy = requestedBy;
        this.request = request == null ? "" : request;
    }

    public void decide(boolean approve, String note) {
        this.state = approve ? State.APPROVED : State.REJECTED;
        this.decisionNote = note == null || note.isBlank() ? null : note.trim();
        this.decidedAt = Instant.now();
    }

    /** Asks again after a rejection or after the work changed. */
    public void reopen() {
        this.state = State.PENDING;
        this.decisionNote = null;
        this.decidedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public User getApprover() {
        return approver;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public State getState() {
        return state;
    }

    public String getRequest() {
        return request;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
