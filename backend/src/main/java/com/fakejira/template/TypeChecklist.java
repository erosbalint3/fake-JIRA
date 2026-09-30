package com.fakejira.template;

import com.fakejira.project.Project;
import com.fakejira.task.TaskType;
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

import java.util.Arrays;
import java.util.List;

/** Checklist items every new task of one type starts with, e.g. bugs: repro steps, fix, test, release notes. */
@Entity
@Table(name = "type_checklists")
public class TypeChecklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 10)
    private TaskType type;

    /** One item per line. */
    @Column(nullable = false, length = 4000)
    private String items = "";

    protected TypeChecklist() {
    }

    public TypeChecklist(Project project, TaskType type) {
        this.project = project;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public TaskType getType() {
        return type;
    }

    public List<String> getItems() {
        return Arrays.stream(items.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void setItems(List<String> list) {
        this.items = String.join("\n", list);
    }
}
