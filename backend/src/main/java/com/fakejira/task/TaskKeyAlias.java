package com.fakejira.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A key a task had before it moved to another project; old links keep working. */
@Entity
@Table(name = "task_key_aliases")
public class TaskKeyAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String oldKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    protected TaskKeyAlias() {
    }

    public TaskKeyAlias(String oldKey, Task task) {
        this.oldKey = oldKey;
        this.task = task;
    }

    public Task getTask() {
        return task;
    }
}
