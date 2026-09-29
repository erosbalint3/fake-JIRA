package com.fakejira.apitoken;

import com.fakejira.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** A personal access token for scripts and integrations ("Authorization: Bearer fjt_…"). Only its hash is stored. */
@Entity
@Table(name = "api_tokens")
public class ApiToken {

    public enum Scope { READ, WRITE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 60)
    private String name;

    /** The first characters, to recognise the token in lists. */
    @Column(nullable = false, length = 16)
    private String prefix;

    @Column(nullable = false, unique = true, length = 64)
    private String hash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Scope scope;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastUsedAt;

    private Instant expiresAt;

    protected ApiToken() {
    }

    public ApiToken(User user, String name, String prefix, String hash, Scope scope, Instant expiresAt) {
        this.user = user;
        this.name = name;
        this.prefix = prefix;
        this.hash = hash;
        this.scope = scope;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public Scope getScope() {
        return scope;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void used() {
        lastUsedAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
