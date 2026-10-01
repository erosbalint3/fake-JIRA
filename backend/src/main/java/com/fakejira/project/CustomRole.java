package com.fakejira.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** A named set of permissions the project owner defines, e.g. "Contractor" or "QA". */
@Entity
@Table(name = "custom_roles")
public class CustomRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 200)
    private String description = "";

    /** Comma-separated {@link Permission} names. */
    @Column(nullable = false, length = 500)
    private String permissions = "";

    protected CustomRole() {
    }

    public CustomRole(Project project, String name, String description, Set<Permission> permissions) {
        this.project = project;
        update(name, description, permissions);
    }

    public void update(String name, String description, Set<Permission> permissions) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.permissions = permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
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

    public String getDescription() {
        return description;
    }

    public Set<Permission> getPermissions() {
        Set<Permission> set = EnumSet.noneOf(Permission.class);
        Arrays.stream(permissions.split(",")).filter(s -> !s.isBlank()).forEach(name -> {
            try {
                set.add(Permission.valueOf(name));
            } catch (IllegalArgumentException e) {
                // a permission from a newer version; ignore
            }
        });
        return set;
    }
}
