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
import jakarta.persistence.UniqueConstraint;

/** One member's rating of one category. Only ever reported in aggregate. */
@Entity
@Table(name = "health_votes", uniqueConstraints = @UniqueConstraint(columnNames = {"check_id", "user_id", "category"}))
public class HealthVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id", nullable = false)
    private HealthCheck check;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 60)
    private String category;

    /** 1 red, 2 amber, 3 green. */
    @Column(nullable = false)
    private int score;

    /** -1 getting worse, 0 stable, 1 improving. */
    @Column(nullable = false)
    private int trend;

    protected HealthVote() {
    }

    public HealthVote(HealthCheck check, User user, String category) {
        this.check = check;
        this.user = user;
        this.category = category;
    }

    public HealthCheck getCheck() {
        return check;
    }

    public User getUser() {
        return user;
    }

    public String getCategory() {
        return category;
    }

    public int getScore() {
        return score;
    }

    public int getTrend() {
        return trend;
    }

    public void set(int score, int trend) {
        this.score = score;
        this.trend = trend;
    }
}
