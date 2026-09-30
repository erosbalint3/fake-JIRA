package com.fakejira.project;

import com.fakejira.admin.InviteRepository;
import com.fakejira.board.BoardColumnRepository;
import com.fakejira.common.ApiException;
import com.fakejira.epic.EpicRepository;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.ProjectDtos.CreateProjectRequest;
import com.fakejira.project.ProjectDtos.ProjectResponse;
import com.fakejira.project.ProjectDtos.UpdateProjectRequest;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.task.TaskCleanup;
import com.fakejira.task.SavedFilterRepository;
import com.fakejira.task.TaskRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.audit.AuditLog;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional
public class ProjectService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.fakejira.board.BoardService board;

    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final UserRepository users;
    private final TaskRepository tasks;
    private final SprintRepository sprints;
    private final TaskCleanup cleanup;
    private final NotificationService notifications;
    private final LiveEvents live;
    private final EpicRepository epics;
    private final BoardColumnRepository columns;
    private final SavedFilterRepository filters;
    private final InviteRepository invites;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final ProjectTemplates projectTemplates;

    public ProjectService(ProjectRepository projects, ProjectAccess access, UserRepository users,
                          TaskRepository tasks, SprintRepository sprints, TaskCleanup cleanup,
                          NotificationService notifications, LiveEvents live, EpicRepository epics,
                          BoardColumnRepository columns, SavedFilterRepository filters, InviteRepository invites,
                          ApplicationEventPublisher events, AuditLog audit, ProjectTemplates projectTemplates) {
        this.projectTemplates = projectTemplates;
        this.events = events;
        this.audit = audit;
        this.epics = epics;
        this.columns = columns;
        this.filters = filters;
        this.invites = invites;
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
        return projects.findForMember(user.getId()).stream().map(p -> ProjectResponse.of(p, user)).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(User user, String key) {
        return ProjectResponse.of(access.memberProject(key, user), user);
    }

    public ProjectResponse create(User user, CreateProjectRequest request) {
        String key = request.key().trim().toUpperCase();
        if (projects.existsByKey(key)) {
            throw ApiException.conflict("A project with key " + key + " already exists.");
        }
        Project project = projects.save(new Project(key, request.name().trim(), trim(request.description()), user));
        projectTemplates.apply(project, user, request.template());
        // Create the board columns now rather than on first view, when several requests could race to do it.
        board.ensureDefaults(project);
        audit.record(user, "project.create", key, project.getName() + (request.template() == null ? "" : " (" + request.template() + ")"));
        return ProjectResponse.of(project);
    }

    public ProjectResponse update(User user, String key, UpdateProjectRequest request) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        project.setName(request.name().trim());
        project.setDescription(trim(request.description()));
        if (request.kanban() != null && request.kanban() != project.isKanban()) {
            if (request.kanban() && sprints.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                    .anyMatch(s -> s.getState() == com.fakejira.sprint.SprintState.ACTIVE)) {
                throw ApiException.badRequest("Complete the active sprint before switching to Kanban.");
            }
            project.setKanban(request.kanban());
            audit.record(user, "project.mode", project.getKey(), request.kanban() ? "kanban" : "scrum");
        }
        if (request.color() != null) {
            project.setColor(request.color());
        }
        if (request.autoSchedule() != null) {
            project.setAutoSchedule(request.autoSchedule());
        }
        live.projectChanged(project);
        return ProjectResponse.of(project);
    }

    public ProjectResponse addMember(User user, String key, String login, ProjectDtos.Role role) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        String value = login.trim();
        User member = (value.contains("@") ? users.findByEmailIgnoreCase(value) : users.findByUsernameIgnoreCase(value))
                .orElseThrow(() -> ApiException.notFound("No user called " + value + "."));
        if (project.hasMember(member)) {
            throw ApiException.conflict(member.getUsername() + " is already a member.");
        }
        project.getMembers().add(member);
        if (role == ProjectDtos.Role.VIEWER || role == ProjectDtos.Role.GUEST) {
            project.getViewers().add(member);
        }
        if (role == ProjectDtos.Role.GUEST) {
            project.getGuests().add(member);
        }
        notifications.notify(member, user,
                user.getUsername() + " added you to project " + project.getKey() + " · " + project.getName(), null);
        audit.record(user, "project.member_add", project.getKey(), member.getUsername() + " as " + (role == null ? "MEMBER" : role));
        live.projectChanged(project);
        return ProjectResponse.of(project);
    }

    /** Switches a member between full access and read-only (viewer). */
    public ProjectResponse setRole(User user, String key, Long memberId, ProjectDtos.Role role) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        User member = project.getMembers().stream().filter(m -> m.getId().equals(memberId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That user is not a member of this project."));
        if (project.isOwner(member) || role == null || role == ProjectDtos.Role.OWNER) {
            throw ApiException.badRequest("The owner's role cannot be changed.");
        }
        if ((role == ProjectDtos.Role.VIEWER || role == ProjectDtos.Role.GUEST) && !project.isViewer(member)) {
            project.getViewers().add(member);
            // Viewers and guests cannot own work.
            tasks.unassignInProject(project.getId(), memberId);
            events.publishEvent(new MemberRemoved(project.getId(), memberId));
        } else if (role == ProjectDtos.Role.MEMBER) {
            project.getViewers().removeIf(viewer -> viewer.getId().equals(memberId));
        }
        if (role == ProjectDtos.Role.GUEST) {
            project.getGuests().add(member);
        } else {
            project.getGuests().removeIf(guest -> guest.getId().equals(memberId));
        }
        audit.record(user, "project.role", project.getKey(), member.getUsername() + " → " + role);
        live.projectChanged(project);
        return ProjectResponse.of(project);
    }

    /** The owner hands the project to another member (who gets full access); the old owner stays a member. */
    public ProjectResponse transferOwnership(User user, String key, Long newOwnerId) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        User next = project.getMembers().stream().filter(m -> m.getId().equals(newOwnerId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That user is not a member of this project."));
        project.getViewers().removeIf(viewer -> viewer.getId().equals(newOwnerId));
        project.getGuests().removeIf(guest -> guest.getId().equals(newOwnerId));
        project.setOwner(next);
        audit.record(user, "project.transfer", project.getKey(), "to " + next.getUsername());
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
        project.getViewers().removeIf(viewer -> viewer.getId().equals(memberId));
        project.getGuests().removeIf(guest -> guest.getId().equals(memberId));
        if (!removed) {
            throw ApiException.notFound("That user is not a member of this project.");
        }
        tasks.unassignInProject(project.getId(), memberId);
        events.publishEvent(new MemberRemoved(project.getId(), memberId));
        audit.record(user, leaving ? "project.leave" : "project.member_remove", project.getKey(), "user #" + memberId);
        live.projectChangedFor(project, Set.of(memberId));
    }

    public void delete(User user, String key) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        live.projectChanged(project);
        // Subtasks are deleted together with their parent.
        tasks.findByProjectId(project.getId()).stream().filter(task -> task.getParent() == null).forEach(cleanup::delete);
        events.publishEvent(new ProjectDeleting(project.getId()));
        sprints.deleteAll(sprints.findByProjectIdOrderByCreatedAtAsc(project.getId()));
        var projectEpics = epics.findByProjectIdOrderByCreatedAtAsc(project.getId());
        projectEpics.forEach(epic -> epic.getDependsOn().clear());
        epics.deleteAll(projectEpics);
        columns.deleteAll(columns.findByProjectIdOrderByPositionAscIdAsc(project.getId()));
        filters.deleteForProject(project.getId());
        invites.deleteForProject(project.getId());
        audit.record(user, "project.delete", project.getKey(), project.getName());
        projects.delete(project);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
