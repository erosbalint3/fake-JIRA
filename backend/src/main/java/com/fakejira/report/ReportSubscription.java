package com.fakejira.report;

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

/**
 * A report emailed on a schedule. {@code kind}: "filter" (target is an FQL query), "project" (a project key:
 * status, overdue, aging work, SLA and forecast) or "dashboard" (a dashboard id).
 */
@Entity
@Table(name = "report_subscriptions")
public class ReportSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 1000)
    private String target;

    @Column(nullable = false, length = 120)
    private String title;

    /** DAILY or WEEKLY. */
    @Column(nullable = false, length = 10)
    private String frequency;

    /** 1 = Monday … 7 = Sunday; used by weekly reports. */
    @Column(nullable = false)
    private int weekday = 1;

    /** Local hour (in the owner's time zone) to send at. */
    // "hour" is a reserved word in H2.
    @Column(name = "send_hour", nullable = false)
    private int hour = 8;

    private Instant lastSentAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ReportSubscription() {
    }

    public ReportSubscription(User user, String kind, String target) {
        this.user = user;
        this.kind = kind;
        this.target = target;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getKind() {
        return kind;
    }

    public String getTarget() {
        return target;
    }

    public String getTitle() {
        return title;
    }

    public String getFrequency() {
        return frequency;
    }

    public int getWeekday() {
        return weekday;
    }

    public int getHour() {
        return hour;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Instant lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setSchedule(String title, String frequency, int weekday, int hour) {
        this.title = title;
        this.frequency = frequency;
        this.weekday = weekday;
        this.hour = hour;
    }
}
