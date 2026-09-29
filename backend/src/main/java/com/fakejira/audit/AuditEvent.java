package com.fakejira.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** One security-relevant action: who did what, to what, from where. */
@Entity
@Table(name = "audit_events", indexes = @Index(name = "idx_audit_created", columnList = "createdAt"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Null for anonymous actions such as a failed sign-in. */
    private Long actorId;

    @Column(length = 80)
    private String actorName;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(length = 200)
    private String target;

    @Column(length = 1000)
    private String details;

    @Column(length = 64)
    private String ip;

    protected AuditEvent() {
    }

    public AuditEvent(Long actorId, String actorName, String action, String target, String details, String ip) {
        this.actorId = actorId;
        this.actorName = actorName;
        this.action = action;
        this.target = target;
        this.details = details;
        this.ip = ip;
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetails() {
        return details;
    }

    public String getIp() {
        return ip;
    }
}
