package com.fakejira.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** A browser/network combination someone has signed in from, so sign-ins from somewhere new can be flagged. */
@Entity
@Table(name = "known_devices", indexes = @Index(name = "idx_known_devices_user", columnList = "user_id"))
public class KnownDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Hash of the network (/24 or /48) and the browser and operating system. */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(nullable = false)
    private Instant firstSeen = Instant.now();

    @Column(nullable = false)
    private Instant lastSeen = Instant.now();

    protected KnownDevice() {
    }

    public KnownDevice(Long userId, String fingerprint, String label) {
        this.userId = userId;
        this.fingerprint = fingerprint;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getLabel() {
        return label;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void seen() {
        lastSeen = Instant.now();
    }
}
