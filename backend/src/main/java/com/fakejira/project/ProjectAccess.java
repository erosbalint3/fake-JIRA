package com.fakejira.project;

import com.fakejira.common.ApiException;
import com.fakejira.user.User;
import org.springframework.stereotype.Component;

/**
 * Central place for "can this user see / manage this project" checks.
 * Non-members get 404 rather than 403 so project keys are not leaked.
 */
@Component
public class ProjectAccess {

    private final ProjectRepository projects;
    private final RoleAssignmentRepository assignments;

    public ProjectAccess(ProjectRepository projects, RoleAssignmentRepository assignments) {
        this.projects = projects;
        this.assignments = assignments;
    }

    /**
     * What the user may do in the project: everything for the owner and for members without a custom role,
     * the role's permissions for members with one, nothing for viewers (guests may comment).
     */
    public java.util.Set<Permission> permissions(Project project, User user) {
        if (!project.hasMember(user)) {
            return java.util.EnumSet.noneOf(Permission.class);
        }
        if (project.isOwner(user)) {
            return java.util.EnumSet.allOf(Permission.class);
        }
        if (project.isGuest(user)) {
            return java.util.EnumSet.of(Permission.COMMENT);
        }
        if (project.isViewer(user)) {
            return java.util.EnumSet.noneOf(Permission.class);
        }
        return assignments.findByProjectIdAndUserId(project.getId(), user.getId())
                .map(a -> a.getRole().getPermissions())
                .orElseGet(() -> java.util.EnumSet.allOf(Permission.class));
    }

    public boolean allows(Project project, User user, Permission permission) {
        return permissions(project, user).contains(permission);
    }

    /** Like {@link #requireEditor}, and the member's custom role (if any) must include {@code permission}. */
    public void require(Project project, User user, Permission permission) {
        requireEditor(project, user);
        if (!allows(project, user, permission)) {
            throw ApiException.forbidden("Your role in " + project.getKey() + " does not allow this ("
                    + permission.label().toLowerCase(java.util.Locale.ROOT) + ").");
        }
    }

    /** Internal comments are for the team: not guests or viewers, and not roles without the permission. */
    public boolean canSeeInternal(Project project, User user) {
        return project.hasMember(user) && !project.isViewer(user) && allows(project, user, Permission.VIEW_INTERNAL);
    }

    public Project memberProject(String key, User user) {
        Project project = projects.findByKey(key.toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Project " + key + " does not exist."));
        requireMember(project, user);
        return project;
    }

    public Project memberProjectById(Long id) {
        return projects.findById(id).orElseThrow(() -> ApiException.notFound("Project not found."));
    }

    public void requireMember(Project project, User user) {
        if (!project.hasMember(user)) {
            throw ApiException.notFound("Project " + project.getKey() + " does not exist.");
        }
    }

    /** Members who are viewers get a clear 403 when they try to change something. */
    public void requireEditor(Project project, User user) {
        requireMember(project, user);
        if (!project.canEdit(user)) {
            throw ApiException.forbidden("You have read-only access to " + project.getKey() + ".");
        }
    }

    public Project editorProject(String key, User user) {
        Project project = memberProject(key, user);
        requireEditor(project, user);
        return project;
    }

    public void requireOwner(Project project, User user) {
        requireMember(project, user);
        if (!project.isOwner(user)) {
            throw ApiException.forbidden("Only the project owner can do that.");
        }
    }
}
