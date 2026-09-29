package com.fakejira.planning;

import com.fakejira.sprint.Sprint;
import com.fakejira.user.User;
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
import java.util.HashSet;
import java.util.Set;

/** A card on a sprint retrospective board. */
@Entity
@Table(name = "retro_items")
public class RetroItem {

    public enum Kind { WENT_WELL, TO_IMPROVE, ACTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    private Sprint sprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, length = 500)
    private String text;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "retro_votes", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "user_id")
    private Set<Long> votes = new HashSet<>();

    /** The backlog task created from an action item. */
    private Long taskId;

    @Column(length = 30)
    private String taskKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RetroItem() {
    }

    public RetroItem(Sprint sprint, User author, Kind kind, String text) {
        this.sprint = sprint;
        this.author = author;
        this.kind = kind;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Sprint getSprint() {
        return sprint;
    }

    public User getAuthor() {
        return author;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Set<Long> getVotes() {
        return votes;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public void linkTask(Long taskId, String taskKey) {
        this.taskId = taskId;
        this.taskKey = taskKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
