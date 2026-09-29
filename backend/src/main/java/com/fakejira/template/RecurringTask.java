package com.fakejira.template;

import com.fakejira.project.Project;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskType;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;

/** Creates a task on a schedule, e.g. "Every Monday: deploy review". */
@Entity
@Table(name = "recurring_tasks")
public class RecurringTask {

    public enum Frequency { DAILY, WEEKDAYS, WEEKLY, MONTHLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 5000)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskType type = TaskType.TASK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Column(nullable = false, length = 400)
    private String labels = "";

    @Column(nullable = false, length = 4000)
    private String checklist = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Frequency frequency = Frequency.WEEKLY;

    /** 1 = Monday … 7 = Sunday, for weekly schedules. */
    private int dayOfWeek = 1;

    /** 1-28, for monthly schedules. */
    private int dayOfMonth = 1;

    /** Days until the created task is due; null for no due date. */
    private Integer dueInDays;

    @Column(nullable = false)
    private LocalDate nextRun;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 20)
    private String lastTaskKey;

    protected RecurringTask() {
    }

    public RecurringTask(Project project, User createdBy) {
        this.project = project;
        this.createdBy = createdBy;
    }

    /** The first scheduled day on or after {@code from}. */
    public LocalDate firstRunOnOrAfter(LocalDate from) {
        return switch (frequency) {
            case DAILY -> from;
            case WEEKDAYS -> from.getDayOfWeek().getValue() >= 6 ? from.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) : from;
            case WEEKLY -> from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)));
            case MONTHLY -> from.getDayOfMonth() <= dayOfMonth ? from.withDayOfMonth(dayOfMonth)
                    : from.plusMonths(1).withDayOfMonth(dayOfMonth);
        };
    }

    public void advance() {
        nextRun = firstRunOnOrAfter(nextRun.plusDays(1));
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TaskType getType() {
        return type;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public List<String> getLabels() {
        return labels.isBlank() ? List.of() : Arrays.stream(labels.split(",")).map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    public List<String> getChecklist() {
        return checklist.isBlank() ? List.of() : Arrays.stream(checklist.split("\n")).map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    public User getAssignee() {
        return assignee;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public int getDayOfMonth() {
        return dayOfMonth;
    }

    public Integer getDueInDays() {
        return dueInDays;
    }

    public LocalDate getNextRun() {
        return nextRun;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getLastTaskKey() {
        return lastTaskKey;
    }

    public void setLastTaskKey(String lastTaskKey) {
        this.lastTaskKey = lastTaskKey;
    }

    public void setAssignee(User assignee) {
        this.assignee = assignee;
    }

    public void update(String title, String description, TaskType type, TaskPriority priority, List<String> labels,
                       List<String> checklist, Frequency frequency, int dayOfWeek, int dayOfMonth, Integer dueInDays,
                       LocalDate today) {
        this.title = title;
        this.description = description == null ? "" : description;
        this.type = type == null ? TaskType.TASK : type;
        this.priority = priority == null ? TaskPriority.MEDIUM : priority;
        this.labels = labels == null ? "" : String.join(",", labels);
        this.checklist = checklist == null ? "" : String.join("\n", checklist);
        this.frequency = frequency == null ? Frequency.WEEKLY : frequency;
        this.dayOfWeek = Math.max(1, Math.min(7, dayOfWeek));
        this.dayOfMonth = Math.max(1, Math.min(28, dayOfMonth));
        this.dueInDays = dueInDays;
        this.nextRun = firstRunOnOrAfter(today);
    }
}
