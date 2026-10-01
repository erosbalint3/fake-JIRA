package com.fakejira.goal;

import com.fakejira.epic.Epic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/** A measurable result of a goal: either a number updated by hand, or the progress of linked epics. */
@Entity
@Table(name = "key_results")
public class KeyResult {

    public enum Kind { MANUAL, EPICS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(precision = 14, scale = 2)
    private BigDecimal startValue;

    @Column(precision = 14, scale = 2)
    private BigDecimal target;

    @Column(name = "current_value", precision = 14, scale = 2)
    private BigDecimal current;

    @Column(length = 20)
    private String unit;

    @ManyToMany
    @JoinTable(name = "key_result_epics",
            joinColumns = @JoinColumn(name = "key_result_id"),
            inverseJoinColumns = @JoinColumn(name = "epic_id"))
    private Set<Epic> epics = new LinkedHashSet<>();

    @Column(nullable = false)
    private int position;

    protected KeyResult() {
    }

    public KeyResult(Goal goal, String title, Kind kind, int position) {
        this.goal = goal;
        this.title = title;
        this.kind = kind;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Goal getGoal() {
        return goal;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public BigDecimal getStartValue() {
        return startValue;
    }

    public void setStartValue(BigDecimal startValue) {
        this.startValue = startValue;
    }

    public BigDecimal getTarget() {
        return target;
    }

    public void setTarget(BigDecimal target) {
        this.target = target;
    }

    public BigDecimal getCurrent() {
        return current;
    }

    public void setCurrent(BigDecimal current) {
        this.current = current;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Set<Epic> getEpics() {
        return epics;
    }

    public int getPosition() {
        return position;
    }
}
