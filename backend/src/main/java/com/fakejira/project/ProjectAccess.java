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

    public ProjectAccess(ProjectRepository projects) {
        this.projects = projects;
    }

    public Project memberProject(String key, User user) {
        Project project = projects.findByKey(key.toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Project " + key + " does not exist."));
        requireMember(project, user);
        return project;
    }

    public void requireMember(Project project, User user) {
        if (!project.hasMember(user)) {
            throw ApiException.notFound("Project " + project.getKey() + " does not exist.");
        }
    }

    public void requireOwner(Project project, User user) {
        requireMember(project, user);
        if (!project.isOwner(user)) {
            throw ApiException.forbidden("Only the project owner can do that.");
        }
    }
}
