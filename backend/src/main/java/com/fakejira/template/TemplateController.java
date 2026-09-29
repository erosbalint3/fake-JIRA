package com.fakejira.template;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.util.List;

/** Task templates per project; members use them when creating tasks, editors manage them. */
@RestController
public class TemplateController {

    private static final int MAX_TEMPLATES = 30;

    private final TaskTemplateRepository templates;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public TemplateController(TaskTemplateRepository templates, ProjectAccess access, CurrentUser currentUser,
                              LiveEvents live) {
        this.templates = templates;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record TemplateRequest(
            @NotBlank(message = "Name is required") @Size(max = 60, message = "Name must be at most 60 characters") String name,
            TaskType type,
            @Size(max = 120, message = "Title must be at most 120 characters") String title,
            @Size(max = 5000, message = "Description must be at most 5000 characters") String description,
            TaskPriority priority,
            @Size(max = 10, message = "At most 10 labels") List<@Size(max = 30) String> labels,
            @Size(max = 50, message = "At most 50 checklist items") List<@Size(max = 200) String> checklist,
            @Min(0) @Max(100) Integer storyPoints) {
    }

    public record TemplateResponse(Long id, String name, TaskType type, String title, String description,
                                   TaskPriority priority, List<String> labels, List<String> checklist, Integer storyPoints) {
        static TemplateResponse of(TaskTemplate t) {
            return new TemplateResponse(t.getId(), t.getName(), t.getType(), t.getTitle(), t.getDescription(),
                    t.getPriority(), t.getLabels(), t.getChecklist(), t.getStoryPoints());
        }
    }

    @GetMapping("/api/projects/{key}/templates")
    @Transactional(readOnly = true)
    public List<TemplateResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return templates.findByProjectIdOrderByNameAsc(project.getId()).stream().map(TemplateResponse::of).toList();
    }

    @PostMapping("/api/projects/{key}/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TemplateResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                   @Valid @RequestBody TemplateRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        if (templates.findByProjectIdOrderByNameAsc(project.getId()).size() >= MAX_TEMPLATES) {
            throw ApiException.badRequest("A project can have at most " + MAX_TEMPLATES + " templates.");
        }
        TaskTemplate template = new TaskTemplate(project);
        apply(template, request);
        templates.save(template);
        live.projectChanged(project);
        return TemplateResponse.of(template);
    }

    @PutMapping("/api/templates/{id}")
    @Transactional
    public TemplateResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                   @Valid @RequestBody TemplateRequest request) {
        TaskTemplate template = editable(id, currentUser.from(jwt));
        apply(template, request);
        live.projectChanged(template.getProject());
        return TemplateResponse.of(template);
    }

    @DeleteMapping("/api/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        TaskTemplate template = editable(id, currentUser.from(jwt));
        templates.delete(template);
        live.projectChanged(template.getProject());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        templates.deleteAll(templates.findByProjectIdOrderByNameAsc(event.projectId()));
    }

    private static void apply(TaskTemplate template, TemplateRequest r) {
        template.update(r.name().trim(), r.type(), r.title() == null ? "" : r.title().trim(),
                r.description() == null ? "" : r.description().trim(), r.priority(),
                r.labels() == null ? List.of() : r.labels().stream().map(l -> l.trim().toLowerCase())
                        .filter(l -> !l.isEmpty() && !l.contains(",")).distinct().toList(),
                r.checklist() == null ? List.of() : r.checklist().stream().map(String::trim).filter(l -> !l.isEmpty()).toList(),
                r.storyPoints());
    }

    private TaskTemplate editable(Long id, User user) {
        TaskTemplate template = templates.findById(id).orElseThrow(() -> ApiException.notFound("Template not found."));
        access.requireEditor(access.memberProjectById(template.getProject().getId()), user);
        return template;
    }
}
