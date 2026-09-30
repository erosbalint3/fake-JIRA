package com.fakejira.webhook;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.integration.ChatSender;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.TaskEvent;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Outgoing webhooks, managed by the project owner. */
@RestController
public class WebhookController {

    static final int MAX_HOOKS = 10;
    static final Set<String> EVENTS = Arrays.stream(TaskEvent.Kind.values()).map(TaskEvent.Kind::wireName)
            .collect(Collectors.toUnmodifiableSet());

    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookService service;
    private final ChatSender urls;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final AuditLog audit;

    public WebhookController(WebhookRepository webhooks, WebhookDeliveryRepository deliveries, WebhookService service,
                             ChatSender urls, ProjectAccess access, CurrentUser currentUser, AuditLog audit) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.service = service;
        this.urls = urls;
        this.access = access;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record WebhookRequest(@NotBlank(message = "URL is required") @Size(max = 500) String url, List<String> events,
                                 Boolean enabled) {
    }

    /** {@code secret} is only included right after creating the webhook or rotating its secret. */
    public record WebhookResponse(Long id, String url, List<String> events, boolean enabled, Instant createdAt,
                                  Instant lastDeliveryAt, Integer lastStatus, String lastError, String secret) {
    }

    public record DeliveryResponse(Long id, String event, Instant sentAt, Integer status, int attempts, long durationMs,
                                   String error, String payload) {
    }

    @GetMapping("/api/projects/{key}/webhooks")
    @Transactional(readOnly = true)
    public List<WebhookResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = owned(key, currentUser.from(jwt));
        return webhooks.findByProjectIdOrderByIdAsc(project.getId()).stream().map(w -> response(w, false)).toList();
    }

    @PostMapping("/api/projects/{key}/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WebhookResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                  @Valid @RequestBody WebhookRequest request) {
        User user = currentUser.from(jwt);
        Project project = owned(key, user);
        if (webhooks.findByProjectIdOrderByIdAsc(project.getId()).size() >= MAX_HOOKS) {
            throw ApiException.badRequest("A project can have at most " + MAX_HOOKS + " webhooks.");
        }
        urls.checkUrl(request.url());
        Webhook hook = new Webhook(project, request.url().trim(), newSecret());
        hook.getEvents().addAll(events(request.events()));
        webhooks.save(hook);
        audit.record(user, "webhook.create", project.getKey(), hostOf(hook.getUrl()));
        return response(hook, true);
    }

    @PutMapping("/api/webhooks/{id}")
    @Transactional
    public WebhookResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody WebhookRequest request) {
        Webhook hook = editable(id, currentUser.from(jwt));
        urls.checkUrl(request.url());
        hook.setUrl(request.url().trim());
        hook.getEvents().clear();
        hook.getEvents().addAll(events(request.events()));
        if (request.enabled() != null) {
            hook.setEnabled(request.enabled());
        }
        return response(hook, false);
    }

    @PostMapping("/api/webhooks/{id}/secret")
    @Transactional
    public WebhookResponse rotate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Webhook hook = editable(id, currentUser.from(jwt));
        hook.setSecret(newSecret());
        return response(hook, true);
    }

    @DeleteMapping("/api/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Webhook hook = editable(id, user);
        deliveries.deleteForWebhook(id);
        audit.record(user, "webhook.delete", hook.getProject().getKey(), hostOf(hook.getUrl()));
        webhooks.delete(hook);
    }

    @PostMapping("/api/webhooks/{id}/test")
    @Transactional
    public DeliveryResponse test(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        editable(id, currentUser.from(jwt));
        return delivery(service.ping(id));
    }

    @GetMapping("/api/webhooks/{id}/deliveries")
    @Transactional(readOnly = true)
    public List<DeliveryResponse> deliveries(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        editable(id, currentUser.from(jwt));
        return deliveries.findByWebhookIdOrderByIdDesc(id, PageRequest.of(0, WebhookService.LOG_SIZE)).stream()
                .map(WebhookController::delivery).toList();
    }

    private Project owned(String key, User user) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        return project;
    }

    private Webhook editable(Long id, User user) {
        Webhook hook = webhooks.findById(id).orElseThrow(() -> ApiException.notFound("Webhook not found."));
        access.requireOwner(hook.getProject(), user);
        return hook;
    }

    private static List<String> events(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        for (String e : requested) {
            if (!EVENTS.contains(e)) {
                throw ApiException.field("events", "Unknown event " + e + ".");
            }
        }
        return requested.stream().distinct().toList();
    }

    static String newSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    private static WebhookResponse response(Webhook w, boolean withSecret) {
        return new WebhookResponse(w.getId(), w.getUrl(), List.copyOf(w.getEvents()), w.isEnabled(), w.getCreatedAt(),
                w.getLastDeliveryAt(), w.getLastStatus(), w.getLastError(), withSecret ? w.getSecret() : null);
    }

    private static DeliveryResponse delivery(WebhookDelivery d) {
        return d == null ? null : new DeliveryResponse(d.getId(), d.getEvent(), d.getSentAt(), d.getStatus(), d.getAttempts(),
                d.getDurationMs(), d.getError(), d.getPayload());
    }
}
