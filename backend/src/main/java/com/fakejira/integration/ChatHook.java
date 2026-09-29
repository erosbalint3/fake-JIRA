package com.fakejira.integration;

import com.fakejira.project.Project;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** A Slack, Discord or Slack-compatible incoming webhook that gets project updates. */
@Entity
@Table(name = "chat_hooks")
public class ChatHook {

    public enum Kind { SLACK, DISCORD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(nullable = false, length = 1000)
    private String url;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "chat_hook_events", joinColumns = @JoinColumn(name = "hook_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "event", length = 20)
    private Set<ChatEventType> events = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastDeliveryAt;

    @Column(length = 200)
    private String lastError;

    protected ChatHook() {
    }

    public ChatHook(Project project, Kind kind, String url, Set<ChatEventType> events) {
        this.project = project;
        this.kind = kind;
        this.url = url;
        this.events = new HashSet<>(events);
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public Kind getKind() {
        return kind;
    }

    public String getUrl() {
        return url;
    }

    public Set<ChatEventType> getEvents() {
        return events.isEmpty() ? EnumSet.noneOf(ChatEventType.class) : EnumSet.copyOf(events);
    }

    public void setEvents(Set<ChatEventType> events) {
        this.events.clear();
        this.events.addAll(events);
    }

    public Instant getLastDeliveryAt() {
        return lastDeliveryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void delivered(String error) {
        this.lastDeliveryAt = Instant.now();
        this.lastError = error == null ? null : error.length() > 200 ? error.substring(0, 199) + "…" : error;
    }
}
