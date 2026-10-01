package com.fakejira.personal;

import com.fakejira.task.Task;
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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/** A task someone picked to work on on a given day (their Today list). */
@Entity
@Table(name = "today_picks", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "task_id", "pick_day"}))
public class TodayPick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    // "day" is a reserved word in H2.
    @Column(name = "pick_day", nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private int position;

    protected TodayPick() {
    }

    public TodayPick(User user, Task task, LocalDate day, int position) {
        this.user = user;
        this.task = task;
        this.day = day;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public LocalDate getDay() {
        return day;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
