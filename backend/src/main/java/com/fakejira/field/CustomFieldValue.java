package com.fakejira.field;

import com.fakejira.task.Task;
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

/** A task's value for a custom field, stored as text (numbers, dates and booleans in a normal form). */
@Entity
@Table(name = "custom_field_values", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "field_id"}))
public class CustomFieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "field_id", nullable = false)
    private CustomField field;

    @Column(name = "field_value", nullable = false, length = 500)
    private String value;

    protected CustomFieldValue() {
    }

    public CustomFieldValue(Task task, CustomField field, String value) {
        this.task = task;
        this.field = field;
        this.value = value;
    }

    public Task getTask() {
        return task;
    }

    public CustomField getField() {
        return field;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
