package com.fakejira.planning;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** A planning-poker estimation round for one task: votes stay hidden until revealed. */
@Entity
@Table(name = "poker_rounds")
public class PokerRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long taskId;

    @Column(nullable = false)
    private boolean revealed;

    @Column(length = 40)
    private String startedBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "poker_votes", joinColumns = @JoinColumn(name = "round_id"))
    @MapKeyColumn(name = "user_id")
    @Column(name = "vote_value", length = 5)
    private Map<Long, String> votes = new HashMap<>();

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    protected PokerRound() {
    }

    public PokerRound(Long taskId, String startedBy) {
        this.taskId = taskId;
        this.startedBy = startedBy;
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public boolean isRevealed() {
        return revealed;
    }

    public void setRevealed(boolean revealed) {
        this.revealed = revealed;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public Map<Long, String> getVotes() {
        return votes;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    /** Starts over: hides and clears all votes. */
    public void restart(String by) {
        votes.clear();
        revealed = false;
        startedBy = by;
        startedAt = Instant.now();
    }
}
