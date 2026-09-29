package com.fakejira.task;

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
import java.time.LocalDate;

@Entity
@Table(name = "time_entries")
public class TimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int minutes;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false, length = 200)
    private String note = "";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TimeEntry() {
    }

    public TimeEntry(Task task, User user, int minutes, LocalDate workDate, String note) {
        this.task = task;
        this.user = user;
        this.minutes = minutes;
        this.workDate = workDate;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public User getUser() {
        return user;
    }

    public int getMinutes() {
        return minutes;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
