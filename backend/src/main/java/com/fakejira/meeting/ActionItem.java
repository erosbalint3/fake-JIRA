package com.fakejira.meeting;

import com.fakejira.task.Task;
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

/** Something a meeting decided someone should do; becomes a task in one click. */
@Entity
@Table(name = "meeting_actions")
public class ActionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private MeetingNote note;

    @Column(nullable = false, length = 200)
    private String text;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** The task created from this item, once there is one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    protected ActionItem() {
    }

    public ActionItem(MeetingNote note, String text, User assignee) {
        this.note = note;
        this.text = text;
        this.assignee = assignee;
    }

    public Long getId() {
        return id;
    }

    public MeetingNote getNote() {
        return note;
    }

    public String getText() {
        return text;
    }

    public User getAssignee() {
        return assignee;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }
}
