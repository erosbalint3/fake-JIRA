package com.fakejira.task;

import com.fakejira.common.CurrentUser;
import com.fakejira.task.TaskDtos.CommentRequest;
import com.fakejira.task.TaskDtos.CommentResponse;
import com.fakejira.task.TaskDtos.StatusRequest;
import com.fakejira.task.TaskDtos.TaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
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
    private final CurrentUser currentUser;

    public TaskController(TaskService taskService, CurrentUser currentUser) {
        this.taskService = taskService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(defaultValue = "ALL") TaskScope scope,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) TaskPriority priority,
                                   @RequestParam(required = false) TaskStatus status) {
        return taskService.search(currentUser.from(jwt), scope, q, priority, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TaskRequest request) {
        return taskService.create(currentUser.from(jwt), request);
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable Long id) {
        return taskService.get(id);
    }

    @PutMapping("/{id}")
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody TaskRequest request) {
        return taskService.update(currentUser.from(jwt), id, request);
    }

    @PatchMapping("/{id}/status")
    public TaskResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody StatusRequest request) {
        return taskService.changeStatus(currentUser.from(jwt), id, request.status());
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

    @GetMapping("/{id}/comments")
    public List<CommentResponse> comments(@PathVariable Long id) {
        return taskService.comments(id);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                      @Valid @RequestBody CommentRequest request) {
        return taskService.addComment(currentUser.from(jwt), id, request);
    }
}
