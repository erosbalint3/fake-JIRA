package com.fakejira.calendar;

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

/** The calendar event that mirrors one task's due date in one user's calendar. */
@Entity
@Table(name = "calendar_event_links", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "task_id"}))
public class CalendarEventLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false, length = 200)
    private String eventId;

    /** The due date and title last written to (or read from) the event. */
    private LocalDate lastDue;

    @Column(length = 200)
    private String lastSummary;

    protected CalendarEventLink() {
    }

    public CalendarEventLink(User user, Task task, String eventId) {
        this.user = user;
        this.task = task;
        this.eventId = eventId;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getEventId() {
        return eventId;
    }

    public LocalDate getLastDue() {
        return lastDue;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void remember(LocalDate due, String summary) {
        this.lastDue = due;
        this.lastSummary = summary;
    }
}
