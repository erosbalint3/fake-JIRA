package com.fakejira.webhook;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.integration.ChatSender;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * REST hooks for automation platforms (Zapier, n8n, Make): subscribe a URL to an event, unsubscribe it, and fetch
 * sample payloads; plus a polling trigger for new tasks. Authenticate with a personal API token.
 */
@RestController
@Transactional
public class RestHookController {

    private final WebhookRepository webhooks;
    private final WebhookService service;
    private final ChatSender urls;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final ObjectMapper json;

    public RestHookController(WebhookRepository webhooks, WebhookService service, ChatSender urls, ProjectAccess access,
                              CurrentUser currentUser, TaskRepository tasks, TaskSupport taskSupport, ObjectMapper json) {
        this.webhooks = webhooks;
        this.service = service;
        this.urls = urls;
        this.access = access;
        this.currentUser = currentUser;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.json = json;
    }

    public record Subscribe(@NotBlank(message = "Choose a project") String projectKey,
                            @NotBlank(message = "Choose an event") String event,
                            @NotBlank(message = "Give the target URL") @Size(max = 1000) String targetUrl) {
    }

    public record Subscription(Long id, String projectKey, String event, String targetUrl, String secret) {
    }

    @PostMapping("/api/rest-hooks")
    @ResponseStatus(HttpStatus.CREATED)
    public Subscription subscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Subscribe request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(request.projectKey(), user);
        if (!WebhookController.EVENTS.contains(request.event())) {
            throw ApiException.field("event", "Unknown event. Use one of: " + String.join(", ", WebhookController.EVENTS) + ".");
        }
        if (webhooks.findByProjectIdOrderByIdAsc(project.getId()).size() >= WebhookController.MAX_HOOKS) {
            throw ApiException.badRequest("This project already has the maximum number of webhooks.");
        }
        urls.checkUrl(request.targetUrl());
        Webhook hook = new Webhook(project, request.targetUrl().trim(), WebhookController.newSecret());
        hook.getEvents().add(request.event());
        hook.markRestHook(user.getId());
        webhooks.save(hook);
        return new Subscription(hook.getId(), project.getKey(), request.event(), hook.getUrl(), hook.getSecret());
    }

    @DeleteMapping("/api/rest-hooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Webhook hook = webhooks.findById(id).filter(Webhook::isRestHook)
                .filter(h -> h.getProject().hasMember(user))
                .orElseThrow(() -> ApiException.notFound("Subscription not found."));
        if (!user.getId().equals(hook.getCreatedBy()) && !hook.getProject().isOwner(user)) {
            throw ApiException.forbidden("Only whoever subscribed it or the project owner can remove it.");
        }
        webhooks.delete(hook);
    }

    /** One example payload per event, built from the project's newest task (for mapping fields in Zapier). */
    @GetMapping("/api/rest-hooks/sample")
    @Transactional(readOnly = true)
    public List<JsonNode> sample(@AuthenticationPrincipal Jwt jwt, @RequestParam String project, @RequestParam String event)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        User user = currentUser.from(jwt);
        Project p = access.memberProject(project, user);
        if (!WebhookController.EVENTS.contains(event)) {
            throw ApiException.field("event", "Unknown event.");
        }
        Task task = tasks.findByProjectId(p.getId()).stream().max(Comparator.comparing(Task::getCreatedAt)).orElse(null);
        if (task == null) {
            return List.of();
        }
        return List.of(json.readTree(service.sample(event, task, user)));
    }

    /** Polling trigger: the project's newest tasks first (platforms remember which ids they have seen). */
    @GetMapping("/api/projects/{key}/tasks/recent")
    @Transactional(readOnly = true)
    public List<TaskResponse> recent(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                     @RequestParam(defaultValue = "25") int limit) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return taskSupport.responses(tasks.findByProjectId(project.getId()).stream()
                .sorted(Comparator.comparing(Task::getCreatedAt).reversed())
                .limit(Math.max(1, Math.min(100, limit))).toList());
    }
}
