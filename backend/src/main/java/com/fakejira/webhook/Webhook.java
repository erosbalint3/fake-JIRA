package com.fakejira.webhook;

import com.fakejira.project.Project;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** An outgoing webhook: FakeJIRA POSTs signed JSON about task events to this URL. */
@Entity
@Table(name = "webhooks")
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 500)
    private String url;

    /** HMAC-SHA256 key for the X-FakeJIRA-Signature header. */
    @Column(nullable = false, length = 64)
    private String secret;

    /** Event wire names (task.created, comment.created…); empty means all. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "webhook_events", joinColumns = @JoinColumn(name = "webhook_id"))
    @Column(name = "event", length = 40)
    private Set<String> events = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastDeliveryAt;

    private Integer lastStatus;

    @Column(length = 300)
    private String lastError;

    protected Webhook() {
    }

    public Webhook(Project project, String url, String secret) {
        this.project = project;
        this.url = url;
        this.secret = secret;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Set<String> getEvents() {
        return events;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastDeliveryAt() {
        return lastDeliveryAt;
    }

    public Integer getLastStatus() {
        return lastStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean wants(String event) {
        return enabled && (events.isEmpty() || events.contains(event));
    }

    public void delivered(Integer status, String error) {
        lastDeliveryAt = Instant.now();
        lastStatus = status;
        lastError = error == null ? null : error.length() > 300 ? error.substring(0, 299) + "…" : error;
    }
}
