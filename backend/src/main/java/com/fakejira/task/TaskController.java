package com.fakejira.task;

import com.fakejira.common.CurrentUser;
import com.fakejira.task.TaskDtos.ActivityResponse;
import com.fakejira.task.TaskDtos.AssigneeRequest;
import com.fakejira.task.TaskDtos.BulkRequest;
import com.fakejira.task.TaskDtos.ChecklistItemRequest;
import com.fakejira.task.TaskDtos.ChecklistItemResponse;
import com.fakejira.task.TaskDtos.ChecklistUpdateRequest;
import com.fakejira.task.TaskDtos.ColumnRequest;
import com.fakejira.task.TaskDtos.CommentRequest;
import com.fakejira.task.TaskDtos.CommentResponse;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.DevLinkResponse;
import com.fakejira.task.TaskDtos.LinkRequest;
import com.fakejira.task.TaskDtos.LinkResponse;
import com.fakejira.task.TaskDtos.SprintRequest;
import com.fakejira.task.TaskDtos.StatusRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskDtos.TimeEntryResponse;
import com.fakejira.task.TaskDtos.TimeRequest;
import com.fakejira.task.TaskDtos.UpdateTaskRequest;
import com.fakejira.task.TaskDtos.WatchersResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskDetailsService details;
    private final CurrentUser currentUser;

    public TaskController(TaskService taskService, TaskDetailsService details, CurrentUser currentUser) {
        this.taskService = taskService;
        this.details = details;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(required = false) String project,
                                   @RequestParam(defaultValue = "ALL") TaskScope scope,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) TaskPriority priority,
                                   @RequestParam(required = false) TaskStatus status,
                                   @RequestParam(required = false) String label,
                                   @RequestParam(required = false) String sprint,
                                   @RequestParam(required = false) String assignee,
                                   @RequestParam(required = false) String epic) {
        return taskService.search(currentUser.from(jwt),
                new TaskFilter(project, scope, q, priority, status, label, sprint, assignee, epic));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(currentUser.from(jwt), request);
    }

    @PostMapping("/bulk")
    public List<TaskResponse> bulk(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BulkRequest request) {
        return taskService.bulk(currentUser.from(jwt), request);
    }

    @GetMapping("/key/{key}")
    public TaskResponse byKey(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return taskService.byKey(currentUser.from(jwt), key.toUpperCase());
    }

    @GetMapping("/{id}")
    public TaskResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return taskService.get(currentUser.from(jwt), id);
    }

    @PutMapping("/{id}")
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(currentUser.from(jwt), id, request);
    }

    @PatchMapping("/{id}/status")
    public TaskResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody StatusRequest request) {
        return taskService.changeStatus(currentUser.from(jwt), id, request.status());
    }

    @PatchMapping("/{id}/column")
    public TaskResponse moveToColumn(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody ColumnRequest request) {
        return taskService.moveToColumn(currentUser.from(jwt), id, request.columnId());
    }

    @PutMapping("/{id}/assignee")
    public TaskResponse assign(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @RequestBody AssigneeRequest request) {
        return taskService.assign(currentUser.from(jwt), id, request.assigneeId());
    }

    @PutMapping("/{id}/sprint")
    public TaskResponse moveToSprint(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestBody SprintRequest request) {
        return taskService.moveToSprint(currentUser.from(jwt), id, request.sprintId());
    }

    @PostMapping("/{id}/accept")
    public TaskResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return taskService.accept(currentUser.from(jwt), id);
    }

    @PostMapping("/{id}/release")
    public TaskResponse release(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return taskService.release(currentUser.from(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        taskService.delete(currentUser.from(jwt), id);
    }

    @GetMapping("/{id}/subtasks")
    public List<TaskResponse> subtasks(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return taskService.subtasks(currentUser.from(jwt), id);
    }

    @GetMapping("/{id}/comments")
    public List<CommentResponse> comments(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.comments(currentUser.from(jwt), id);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                      @Valid @RequestBody CommentRequest request) {
        return details.addComment(currentUser.from(jwt), id, request.body());
    }

    @GetMapping("/{id}/checklist")
    public List<ChecklistItemResponse> checklist(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.checklist(currentUser.from(jwt), id);
    }

    @PostMapping("/{id}/checklist")
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistItemResponse addChecklistItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                  @Valid @RequestBody ChecklistItemRequest request) {
        return details.addChecklistItem(currentUser.from(jwt), id, request.text());
    }

    @PatchMapping("/{id}/checklist/{itemId}")
    public ChecklistItemResponse updateChecklistItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                     @PathVariable Long itemId,
                                                     @Valid @RequestBody ChecklistUpdateRequest request) {
        return details.updateChecklistItem(currentUser.from(jwt), id, itemId, request);
    }

    @DeleteMapping("/{id}/checklist/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChecklistItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @PathVariable Long itemId) {
        details.deleteChecklistItem(currentUser.from(jwt), id, itemId);
    }

    @GetMapping("/{id}/activity")
    public List<ActivityResponse> activity(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.activity(currentUser.from(jwt), id);
    }

    @GetMapping("/{id}/links")
    public List<LinkResponse> links(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.links(currentUser.from(jwt), id);
    }

    @PostMapping("/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse addLink(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                @Valid @RequestBody LinkRequest request) {
        return details.addLink(currentUser.from(jwt), id, request);
    }

    @DeleteMapping("/{id}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long linkId) {
        details.deleteLink(currentUser.from(jwt), id, linkId);
    }

    @GetMapping("/{id}/time")
    public List<TimeEntryResponse> time(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.time(currentUser.from(jwt), id);
    }

    @PostMapping("/{id}/time")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeEntryResponse logTime(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody TimeRequest request) {
        return details.logTime(currentUser.from(jwt), id, request);
    }

    @DeleteMapping("/{id}/time/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTime(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long entryId) {
        details.deleteTime(currentUser.from(jwt), id, entryId);
    }

    @GetMapping("/{id}/watchers")
    public WatchersResponse watchers(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.watchers(currentUser.from(jwt), id);
    }

    @PutMapping("/{id}/watch")
    public WatchersResponse watch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.watch(currentUser.from(jwt), id, true);
    }

    @DeleteMapping("/{id}/watch")
    public WatchersResponse unwatch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.watch(currentUser.from(jwt), id, false);
    }

    @GetMapping("/{id}/dev")
    public List<DevLinkResponse> devLinks(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return details.devLinks(currentUser.from(jwt), id);
    }
}
