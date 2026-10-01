package com.fakejira.template;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.TaskType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default checklists per task type, applied when a task is created without its own checklist. */
@RestController
@Transactional
public class TypeChecklistController {

    private final TypeChecklistRepository checklists;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public TypeChecklistController(TypeChecklistRepository checklists, ProjectAccess access, CurrentUser currentUser,
                                   LiveEvents live) {
        this.checklists = checklists;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record ItemsRequest(@NotNull @Size(max = 30, message = "At most 30 items")
                               List<@Size(max = 200, message = "Items are at most 200 characters") String> items) {
    }

    /** Every type, with an empty list when none is set. */
    @GetMapping("/api/projects/{key}/type-checklists")
    @Transactional(readOnly = true)
    public Map<TaskType, List<String>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        Map<TaskType, List<String>> result = new LinkedHashMap<>();
        for (TaskType type : TaskType.values()) {
            result.put(type, List.of());
        }
        checklists.findByProjectId(project.getId()).forEach(c -> result.put(c.getType(), c.getItems()));
        return result;
    }

    @PutMapping("/api/projects/{key}/type-checklists/{type}")
    public List<String> save(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable TaskType type,
                             @Valid @RequestBody ItemsRequest request) {
        Project project = access.editorProject(key, currentUser.from(jwt));
        List<String> items = new ArrayList<>();
        for (String item : request.items()) {
            if (item != null && !item.isBlank()) {
                if (item.contains("\n")) {
                    throw ApiException.badRequest("Checklist items are one line each.");
                }
                items.add(item.trim());
            }
        }
        TypeChecklist checklist = checklists.findByProjectIdAndType(project.getId(), type).orElse(null);
        if (items.isEmpty()) {
            if (checklist != null) {
                checklists.delete(checklist);
            }
        } else {
            if (checklist == null) {
                checklist = checklists.save(new TypeChecklist(project, type));
            }
            checklist.setItems(items);
        }
        live.projectChanged(project);
        return items;
    }

    /** Items for a new task of this type, or empty. */
    public List<String> itemsFor(Project project, TaskType type) {
        return checklists.findByProjectIdAndType(project.getId(), type == null ? TaskType.TASK : type)
                .map(TypeChecklist::getItems).orElse(List.of());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        checklists.deleteForProject(event.projectId());
    }
}
