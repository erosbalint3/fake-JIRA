package com.fakejira.dashboard;

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

import java.time.Instant;

/** A personal page of widgets (query results, charts, activity…); the layout is stored as JSON. */
@Entity
@Table(name = "dashboards")
public class Dashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 60)
    private String name;

    /** JSON array of widgets: {"type": "...", "title": "...", ...}. */
    @Column(nullable = false, length = 20000)
    private String widgets = "[]";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Dashboard() {
    }

    public Dashboard(User owner, String name, String widgets) {
        this.owner = owner;
        this.name = name;
        this.widgets = widgets;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWidgets() {
        return widgets;
    }

    public void setWidgets(String widgets) {
        this.widgets = widgets;
    }
}
