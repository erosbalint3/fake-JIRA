package com.fakejira.component;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskEvent;
import com.fakejira.task.TaskEvents;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Project components: editors manage them; tasks can belong to several. */
@RestController
public class ComponentController {

    static final int MAX_COMPONENTS = 30;

    private final ProjectComponentRepository components;
    private final ProjectAccess access;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final LiveEvents live;
    private final TaskEvents taskEvents;

    public ComponentController(ProjectComponentRepository components, ProjectAccess access, TaskSupport taskSupport,
                               CurrentUser currentUser, LiveEvents live, TaskEvents taskEvents) {
        this.components = components;
        this.access = access;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.live = live;
        this.taskEvents = taskEvents;
    }

    public record ComponentRequest(
            @NotBlank(message = "Name is required") @Size(max = 40, message = "Name must be at most 40 characters") String name,
            @Size(max = 300, message = "Description must be at most 300 characters") String description,
            Long leadId) {
    }

    public record ComponentResponse(Long id, String name, String description, UserSummary lead, long taskCount) {
    }

    public record TaskComponentsRequest(@NotNull @Size(max = 10, message = "At most 10 components") List<Long> ids) {
    }

    @GetMapping("/api/projects/{key}/components")
    @Transactional(readOnly = true)
    public List<ComponentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return components.findByProjectIdOrderByNameAsc(project.getId()).stream().map(this::response).toList();
    }

    @PostMapping("/api/projects/{key}/components")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ComponentResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                    @Valid @RequestBody ComponentRequest request) {
        Project project = access.editorProject(key, currentUser.from(jwt));
        List<ProjectComponent> existing = components.findByProjectIdOrderByNameAsc(project.getId());
        if (existing.size() >= MAX_COMPONENTS) {
            throw ApiException.badRequest("A project can have at most " + MAX_COMPONENTS + " components.");
        }
        String name = request.name().trim();
        checkName(existing, name, null);
        ProjectComponent component = new ProjectComponent(project, name);
        apply(component, request);
        components.save(component);
        live.projectChanged(project);
        return response(component);
    }

    @PutMapping("/api/components/{id}")
    @Transactional
    public ComponentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody ComponentRequest request) {
        ProjectComponent component = editable(id, currentUser.from(jwt));
        String name = request.name().trim();
        checkName(components.findByProjectIdOrderByNameAsc(component.getProject().getId()), name, component.getId());
        component.setName(name);
        apply(component, request);
        live.projectChanged(component.getProject());
        return response(component);
    }

    @DeleteMapping("/api/components/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        ProjectComponent component = editable(id, currentUser.from(jwt));
        components.detachTasks(component.getId());
        components.delete(component);
        live.projectChanged(component.getProject());
    }

    /** Replaces the task's components. */
    @PutMapping("/api/tasks/{id}/components")
    @Transactional
    public TaskResponse setForTask(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                   @Valid @RequestBody TaskComponentsRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        Set<ProjectComponent> next = resolve(task.getProject(), request.ids());
        Set<Long> before = ids(task.getComponents());
        Set<Long> after = ids(next);
        if (!before.equals(after)) {
            task.getComponents().clear();
            task.getComponents().addAll(next);
            task.touch();
            taskSupport.record(task, user, next.isEmpty() ? "removed all components"
                    : "set components to " + String.join(", ", next.stream().map(ProjectComponent::getName).toList()));
            live.taskChanged(task);
            taskEvents.publish(TaskEvent.Kind.UPDATED, task, user, "changes", "components");
        }
        return taskSupport.response(task);
    }

    /** Components by id, all of which must belong to {@code project}. */
    public Set<ProjectComponent> resolve(Project project, List<Long> ids) {
        Set<ProjectComponent> result = new LinkedHashSet<>();
        if (ids == null) {
            return result;
        }
        for (Long componentId : new LinkedHashSet<>(ids)) {
            ProjectComponent component = components.findById(componentId)
                    .filter(c -> c.getProject().getId().equals(project.getId()))
                    .orElseThrow(() -> ApiException.badRequest("That component does not belong to " + project.getKey() + "."));
            result.add(component);
        }
        return result;
    }

    private void apply(ProjectComponent component, ComponentRequest request) {
        component.setDescription(request.description() == null ? "" : request.description().trim());
        if (request.leadId() == null) {
            component.setLead(null);
        } else {
            Project project = component.getProject();
            User lead = project.getMembers().stream().filter(m -> m.getId().equals(request.leadId())).findFirst()
                    .orElseThrow(() -> ApiException.field("leadId", "The lead must be a member of " + project.getKey() + "."));
            if (project.isViewer(lead)) {
                throw ApiException.field("leadId", lead.getUsername() + " has read-only access and cannot lead a component.");
            }
            component.setLead(lead);
        }
    }

    private ComponentResponse response(ProjectComponent c) {
        return new ComponentResponse(c.getId(), c.getName(), c.getDescription(), UserSummary.of(c.getLead()),
                c.getId() == null ? 0 : components.countTasks(c.getId()));
    }

    private ProjectComponent editable(Long id, User user) {
        ProjectComponent component = components.findById(id).orElseThrow(() -> ApiException.notFound("Component not found."));
        access.requireEditor(component.getProject(), user);
        return component;
    }

    private static void checkName(List<ProjectComponent> existing, String name, Long self) {
        boolean taken = existing.stream().anyMatch(c -> !c.getId().equals(self) && c.getName().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.field("name", "There is already a component called " + name + ".");
        }
    }

    private static Set<Long> ids(Set<ProjectComponent> set) {
        Set<Long> result = new LinkedHashSet<>();
        set.forEach(c -> result.add(c.getId()));
        return result;
    }

    @EventListener
    public void onMemberRemoved(com.fakejira.project.MemberRemoved event) {
        components.clearLead(event.projectId(), event.userId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        List<ProjectComponent> list = new ArrayList<>(components.findByProjectIdOrderByNameAsc(event.projectId()));
        list.forEach(c -> components.detachTasks(c.getId()));
        components.deleteAll(list);
    }
}
