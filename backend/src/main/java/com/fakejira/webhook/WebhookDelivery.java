package com.fakejira.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One delivery attempt (kept for the last few per webhook, for debugging). */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long webhookId;

    @Column(nullable = false, length = 40)
    private String event;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    private Integer status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private long durationMs;

    @Column(length = 300)
    private String error;

    @Column(nullable = false, length = 8000)
    private String payload;

    protected WebhookDelivery() {
    }

    public WebhookDelivery(Long webhookId, String event, Integer status, int attempts, long durationMs, String error,
                           String payload) {
        this.webhookId = webhookId;
        this.event = event;
        this.status = status;
        this.attempts = attempts;
        this.durationMs = durationMs;
        this.error = error == null ? null : error.length() > 300 ? error.substring(0, 299) + "…" : error;
        this.payload = payload.length() > 8000 ? payload.substring(0, 8000) : payload;
    }

    public Long getId() {
        return id;
    }

    public String getEvent() {
        return event;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Integer getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getError() {
        return error;
    }

    public String getPayload() {
        return payload;
    }
}
