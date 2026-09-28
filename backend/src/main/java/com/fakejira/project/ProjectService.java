package com.fakejira.project;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.ProjectDtos.CreateProjectRequest;
import com.fakejira.project.ProjectDtos.ProjectResponse;
import com.fakejira.project.ProjectDtos.UpdateProjectRequest;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.task.TaskCleanup;
import com.fakejira.task.TaskRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final UserRepository users;
    private final TaskRepository tasks;
    private final SprintRepository sprints;
    private final TaskCleanup cleanup;
    private final NotificationService notifications;
    private final LiveEvents live;

    public ProjectService(ProjectRepository projects, ProjectAccess access, UserRepository users,
                          TaskRepository tasks, SprintRepository sprints, TaskCleanup cleanup,
                          NotificationService notifications, LiveEvents live) {
        this.projects = projects;
        this.access = access;
        this.users = users;
        this.tasks = tasks;
        this.sprints = sprints;
        this.cleanup = cleanup;
        this.notifications = notifications;
        this.live = live;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> mine(User user) {
        return projects.findForMember(user.getId()).stream().map(ProjectResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(User user, String key) {
        return ProjectResponse.of(access.memberProject(key, user));
    }

    public ProjectResponse create(User user, CreateProjectRequest request) {
        String key = request.key().trim().toUpperCase();
        if (projects.existsByKey(key)) {
            throw ApiException.conflict("A project with key " + key + " already exists.");
        }
        Project project = projects.save(new Project(key, request.name().trim(), trim(request.description()), user));
        return ProjectResponse.of(project);
    }

    public ProjectResponse update(User user, String key, UpdateProjectRequest request) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        project.setName(request.name().trim());
        project.setDescription(trim(request.description()));
        live.projectChanged(project);
        return ProjectResponse.of(project);
    }

    public ProjectResponse addMember(User user, String key, String login) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        String value = login.trim();
        User member = (value.contains("@") ? users.findByEmailIgnoreCase(value) : users.findByUsernameIgnoreCase(value))
                .orElseThrow(() -> ApiException.notFound("No user called " + value + "."));
        if (project.hasMember(member)) {
            throw ApiException.conflict(member.getUsername() + " is already a member.");
        }
        project.getMembers().add(member);
        notifications.notify(member, user,
                user.getUsername() + " added you to project " + project.getKey() + " · " + project.getName(), null);
        live.projectChanged(project);
        return ProjectResponse.of(project);
    }

    /** Owners can remove anyone but themselves; members can remove themselves (leave). */
    public void removeMember(User user, String key, Long memberId) {
        Project project = access.memberProject(key, user);
        boolean leaving = user.getId().equals(memberId);
        if (!leaving) {
            access.requireOwner(project, user);
        }
        if (project.getOwner().getId().equals(memberId)) {
            throw ApiException.badRequest("The project owner cannot be removed.");
        }
        boolean removed = project.getMembers().removeIf(member -> member.getId().equals(memberId));
        if (!removed) {
            throw ApiException.notFound("That user is not a member of this project.");
        }
        tasks.unassignInProject(project.getId(), memberId);
        live.projectChangedFor(project, Set.of(memberId));
    }

    public void delete(User user, String key) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        live.projectChanged(project);
        tasks.findByProjectId(project.getId()).forEach(cleanup::delete);
        sprints.deleteAll(sprints.findByProjectIdOrderByCreatedAtAsc(project.getId()));
        projects.delete(project);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
