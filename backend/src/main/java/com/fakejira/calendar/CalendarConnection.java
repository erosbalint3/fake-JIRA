package com.fakejira.calendar;

import com.fakejira.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** A user's link to their Google Calendar, for two-way syncing of their tasks' due dates. */
@Entity
@Table(name = "calendar_connections")
public class CalendarConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 600)
    private String refreshToken;

    @Column(length = 2500)
    private String accessToken;

    private Instant accessExpiresAt;

    @Column(nullable = false, length = 200)
    private String calendarId = "primary";

    private Instant lastSyncAt;

    @Column(length = 300)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CalendarConnection() {
    }

    public CalendarConnection(User user, String refreshToken) {
        this.user = user;
        this.refreshToken = refreshToken;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccess(String token, Instant expiresAt) {
        this.accessToken = token;
        this.accessExpiresAt = expiresAt;
    }

    public String getCalendarId() {
        return calendarId;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void synced(Instant at, String error) {
        this.lastSyncAt = at;
        this.lastError = error == null ? null : error.length() > 300 ? error.substring(0, 299) : error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
