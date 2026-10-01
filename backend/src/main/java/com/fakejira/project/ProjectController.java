package com.fakejira.project;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.ProjectDtos.AddMemberRequest;
import com.fakejira.project.ProjectDtos.CreateProjectRequest;
import com.fakejira.project.ProjectDtos.ProjectResponse;
import com.fakejira.project.ProjectDtos.UpdateProjectRequest;
import com.fakejira.task.TaskRepository;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectAccess access;
    private final TaskRepository tasks;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projectService, ProjectAccess access, TaskRepository tasks,
                             CurrentUser currentUser) {
        this.projectService = projectService;
        this.access = access;
        this.tasks = tasks;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ProjectResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return projectService.mine(currentUser.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(currentUser.from(jwt), request);
    }

    @GetMapping("/{key}")
    public ProjectResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return projectService.get(currentUser.from(jwt), key);
    }

    public record IconRequest(String icon) {
    }

    /** Sets or clears (empty) the project's emoji; owners only. */
    @PutMapping("/{key}/icon")
    public ProjectResponse setIcon(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @RequestBody IconRequest request) {
        return projectService.setIcon(currentUser.from(jwt), key, request.icon());
    }

    @PutMapping("/{key}")
    public ProjectResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(currentUser.from(jwt), key, request);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        projectService.delete(currentUser.from(jwt), key);
    }

    @PostMapping("/{key}/members")
    public ProjectResponse addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                     @Valid @RequestBody AddMemberRequest request) {
        return projectService.addMember(currentUser.from(jwt), key, request.login(), request.role());
    }

    @PutMapping("/{key}/members/{userId}/role")
    public ProjectResponse setRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable Long userId,
                                   @RequestBody ProjectDtos.RoleRequest request) {
        return projectService.setRole(currentUser.from(jwt), key, userId, request.role());
    }

    @PutMapping("/{key}/owner")
    public ProjectResponse transferOwnership(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                             @jakarta.validation.Valid @RequestBody ProjectDtos.OwnerRequest request) {
        return projectService.transferOwnership(currentUser.from(jwt), key, request.userId());
    }

    @DeleteMapping("/{key}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable Long userId) {
        projectService.removeMember(currentUser.from(jwt), key, userId);
    }

    @GetMapping("/{key}/labels")
    @Transactional(readOnly = true)
    public List<String> labels(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return tasks.labelsInProject(project.getId());
    }
}
