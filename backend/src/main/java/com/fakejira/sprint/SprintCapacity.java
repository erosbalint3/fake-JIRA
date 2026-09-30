package com.fakejira.sprint;

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

import java.math.BigDecimal;

/** How much one person can work in one sprint: hours per day and extra days off (besides their time off). */
@Entity
@Table(name = "sprint_capacity")
public class SprintCapacity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    private Sprint sprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, precision = 4, scale = 1)
    private BigDecimal hoursPerDay;

    @Column(nullable = false)
    private int daysOff;

    protected SprintCapacity() {
    }

    public SprintCapacity(Sprint sprint, User user, BigDecimal hoursPerDay, int daysOff) {
        this.sprint = sprint;
        this.user = user;
        this.hoursPerDay = hoursPerDay;
        this.daysOff = daysOff;
    }

    public User getUser() {
        return user;
    }

    public BigDecimal getHoursPerDay() {
        return hoursPerDay;
    }

    public void setHoursPerDay(BigDecimal hoursPerDay) {
        this.hoursPerDay = hoursPerDay;
    }

    public int getDaysOff() {
        return daysOff;
    }

    public void setDaysOff(int daysOff) {
        this.daysOff = daysOff;
    }
}
