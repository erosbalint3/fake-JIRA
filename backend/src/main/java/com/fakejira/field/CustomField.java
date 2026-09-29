package com.fakejira.field;

import com.fakejira.project.Project;
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

/** A project-specific task field, e.g. "Customer" (text) or "Severity" (one of a list). */
@Entity
@Table(name = "custom_fields")
public class CustomField {

    public enum Type { TEXT, NUMBER, SELECT, DATE, CHECKBOX, URL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 40)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false, length = 10)
    private Type type;

    /** Choices for SELECT fields, one per line. */
    @Column(nullable = false, length = 1000)
    private String options = "";

    @Column(nullable = false)
    private int position;

    protected CustomField() {
    }

    public CustomField(Project project, String name, Type type, List<String> options, int position) {
        this.project = project;
        this.name = name;
        this.type = type;
        setOptions(options);
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public List<String> getOptions() {
        return options.isBlank() ? List.of() : Arrays.stream(options.split("\n")).filter(o -> !o.isBlank()).toList();
    }

    public void setOptions(List<String> values) {
        this.options = values == null ? "" : String.join("\n", values.stream().map(String::trim).filter(o -> !o.isEmpty()).distinct().toList());
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
