package com.fakejira.project;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.user.AccountService;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Custom project roles: the owner defines permission sets and gives them to members. */
@RestController
@Transactional
public class CustomRoleController {

    static final int MAX_ROLES = 20;

    public record PermissionInfo(Permission id, String label) {
    }

    public record RoleResponse(Long id, String name, String description, Set<Permission> permissions, List<Long> memberIds) {
    }

    public record RolesResponse(List<RoleResponse> roles, List<PermissionInfo> permissions) {
    }

    public record RoleRequest(@NotBlank(message = "Name is required") @Size(max = 40, message = "At most 40 characters") String name,
                              @Size(max = 200, message = "At most 200 characters") String description,
                              Set<Permission> permissions) {
    }

    public record AssignRequest(Long roleId) {
    }

    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final CustomRoleRepository roles;
    private final RoleAssignmentRepository assignments;
    private final AuditLog audit;
    private final LiveEvents live;

    public CustomRoleController(ProjectAccess access, CurrentUser currentUser, CustomRoleRepository roles,
                                RoleAssignmentRepository assignments, AuditLog audit, LiveEvents live) {
        this.access = access;
        this.currentUser = currentUser;
        this.roles = roles;
        this.assignments = assignments;
        this.audit = audit;
        this.live = live;
    }

    @GetMapping("/api/projects/{key}/roles")
    @Transactional(readOnly = true)
    public RolesResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        Map<Long, List<Long>> members = assignments.findByProjectId(project.getId()).stream()
                .collect(Collectors.groupingBy(a -> a.getRole().getId(), Collectors.mapping(RoleAssignment::getUserId, Collectors.toList())));
        return new RolesResponse(roles.findByProjectIdOrderByNameAsc(project.getId()).stream()
                .map(r -> new RoleResponse(r.getId(), r.getName(), r.getDescription(), r.getPermissions(),
                        members.getOrDefault(r.getId(), List.of()))).toList(),
                Arrays.stream(Permission.values()).map(p -> new PermissionInfo(p, p.label())).toList());
    }

    /** What the signed-in person may do here, so the app can hide what they cannot use. */
    @GetMapping("/api/projects/{key}/my-permissions")
    @Transactional(readOnly = true)
    public Set<Permission> mine(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        return access.permissions(access.memberProject(key, user), user);
    }

    @PostMapping("/api/projects/{key}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody RoleRequest body) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        List<CustomRole> existing = roles.findByProjectIdOrderByNameAsc(project.getId());
        if (existing.size() >= MAX_ROLES) {
            throw ApiException.badRequest("A project can have at most " + MAX_ROLES + " roles.");
        }
        checkName(existing, body.name(), null);
        CustomRole role = roles.save(new CustomRole(project, body.name().trim(), trim(body.description()), permissions(body)));
        audit.record(user, "project.role_create", project.getKey(), role.getName() + ": " + role.getPermissions());
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(), role.getPermissions(), List.of());
    }

    @PutMapping("/api/projects/{key}/roles/{id}")
    public RoleResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable Long id,
                               @Valid @RequestBody RoleRequest body) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        CustomRole role = role(project, id);
        checkName(roles.findByProjectIdOrderByNameAsc(project.getId()), body.name(), id);
        role.update(body.name().trim(), trim(body.description()), permissions(body));
        audit.record(user, "project.role_update", project.getKey(), role.getName() + ": " + role.getPermissions());
        live.projectChanged(project);
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(), role.getPermissions(),
                assignments.findByProjectId(project.getId()).stream().filter(a -> a.getRole().getId().equals(id))
                        .map(RoleAssignment::getUserId).toList());
    }

    @DeleteMapping("/api/projects/{key}/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        CustomRole role = role(project, id);
        assignments.deleteForRole(role.getId());
        roles.delete(role);
        audit.record(user, "project.role_delete", project.getKey(), role.getName());
        live.projectChanged(project);
    }

    /** Gives a member a custom role, or (roleId null) back full member rights. */
    @PutMapping("/api/projects/{key}/members/{userId}/custom-role")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void assign(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable Long userId,
                       @RequestBody AssignRequest body) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        User member = project.getMembers().stream().filter(m -> m.getId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That user is not a member of this project."));
        if (project.isOwner(member)) {
            throw ApiException.badRequest("The owner always has every permission.");
        }
        if (body.roleId() == null) {
            assignments.deleteFor(project.getId(), userId);
            audit.record(user, "project.role_assign", project.getKey(), member.getUsername() + " → member");
        } else {
            if (project.isViewer(member)) {
                throw ApiException.badRequest("Make them a member first; viewers and guests are read-only.");
            }
            CustomRole role = role(project, body.roleId());
            assignments.findByProjectIdAndUserId(project.getId(), userId)
                    .ifPresentOrElse(a -> a.setRole(role), () -> assignments.save(new RoleAssignment(project.getId(), userId, role)));
            audit.record(user, "project.role_assign", project.getKey(), member.getUsername() + " → " + role.getName());
        }
        live.projectChanged(project);
    }

    private CustomRole role(Project project, Long id) {
        return roles.findById(id).filter(r -> r.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> ApiException.notFound("Role not found"));
    }

    private static void checkName(List<CustomRole> existing, String name, Long self) {
        if (existing.stream().anyMatch(r -> !r.getId().equals(self) && r.getName().equalsIgnoreCase(name.trim()))) {
            throw ApiException.field("name", "There is already a role with that name.");
        }
    }

    private static Set<Permission> permissions(RoleRequest body) {
        return body.permissions() == null || body.permissions().isEmpty() ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(body.permissions());
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    @EventListener
    public void onMemberRemoved(MemberRemoved event) {
        assignments.deleteFor(event.projectId(), event.userId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        assignments.deleteForProject(event.projectId());
        roles.deleteForProject(event.projectId());
    }

    @EventListener
    public void onUserDeleting(AccountService.UserDeleting event) {
        assignments.deleteForUser(event.userId());
    }
}
