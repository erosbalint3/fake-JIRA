package com.fakejira.personal;

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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Private Markdown notes on a task, visible only to their author. */
@Entity
@Table(name = "personal_notes", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "task_id"}))
public class PersonalNote {

    public static final int MAX_BODY = 10000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false, length = MAX_BODY)
    private String body = "";

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected PersonalNote() {
    }

    public PersonalNote(User user, Task task) {
        this.user = user;
        this.task = task;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
