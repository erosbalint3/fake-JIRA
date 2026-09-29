package com.fakejira.integration;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.integration.ChatNotifier.ChatMessage;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import org.springframework.context.event.EventListener;
import com.fakejira.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Project owners connect Slack or Discord incoming webhooks. */
@RestController
public class ChatHookController {

    private static final int MAX_HOOKS = 5;
    private static final Set<ChatEventType> DEFAULT_EVENTS =
            EnumSet.of(ChatEventType.TASK_CREATED, ChatEventType.TASK_DONE, ChatEventType.SPRINT);

    private final ChatHookRepository hooks;
    private final ChatSender sender;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final AuditLog audit;

    public ChatHookController(ChatHookRepository hooks, ChatSender sender, ProjectAccess access,
                              CurrentUser currentUser, AuditLog audit) {
        this.hooks = hooks;
        this.sender = sender;
        this.access = access;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record HookRequest(@NotNull ChatHook.Kind kind, @NotBlank String url, Set<ChatEventType> events) {
    }

    public record EventsRequest(@NotNull Set<ChatEventType> events) {
    }

    /** The URL contains the webhook's secret token, so only its host and last characters are shown. */
    public record HookResponse(Long id, ChatHook.Kind kind, String url, Set<ChatEventType> events,
                               Instant lastDeliveryAt, String lastError) {
        static HookResponse of(ChatHook hook) {
            return new HookResponse(hook.getId(), hook.getKind(), mask(hook.getUrl()), hook.getEvents(),
                    hook.getLastDeliveryAt(), hook.getLastError());
        }

        private static String mask(String url) {
            int slashes = url.indexOf('/', url.indexOf("//") + 2);
            String base = slashes < 0 ? url : url.substring(0, slashes);
            return base + "/…" + url.substring(Math.max(slashes < 0 ? url.length() : slashes, url.length() - 4));
        }
    }

    public record TestResult(boolean delivered, String error) {
    }

    @GetMapping("/api/projects/{key}/chat-hooks")
    @Transactional(readOnly = true)
    public List<HookResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = owned(key, currentUser.from(jwt));
        return hooks.findByProjectIdOrderByIdAsc(project.getId()).stream().map(HookResponse::of).toList();
    }

    @PostMapping("/api/projects/{key}/chat-hooks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public HookResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                            @RequestBody @Validated HookRequest request) {
        User user = currentUser.from(jwt);
        Project project = owned(key, user);
        if (hooks.findByProjectIdOrderByIdAsc(project.getId()).size() >= MAX_HOOKS) {
            throw ApiException.badRequest("A project can have at most " + MAX_HOOKS + " chat webhooks.");
        }
        String url = request.url().trim();
        if (url.length() > 1000) {
            throw ApiException.badRequest("That URL is too long.");
        }
        sender.checkUrl(url);
        Set<ChatEventType> events = request.events() == null || request.events().isEmpty() ? DEFAULT_EVENTS : request.events();
        ChatHook hook = hooks.save(new ChatHook(project, request.kind(), url, events));
        audit.record(user, "chat_hook.add", project.getKey(), request.kind() + " " + HookResponse.of(hook).url());
        return HookResponse.of(hook);
    }

    @PutMapping("/api/chat-hooks/{id}")
    @Transactional
    public HookResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @RequestBody @Validated EventsRequest request) {
        ChatHook hook = ownedHook(id, currentUser.from(jwt));
        hook.setEvents(request.events());
        return HookResponse.of(hook);
    }

    @DeleteMapping("/api/chat-hooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        ChatHook hook = ownedHook(id, user);
        audit.record(user, "chat_hook.remove", hook.getProject().getKey(), hook.getKind() + " " + HookResponse.of(hook).url());
        hooks.delete(hook);
    }

    /** Sends a test message right away and reports the outcome. */
    @PostMapping("/api/chat-hooks/{id}/test")
    @Transactional
    public TestResult test(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        ChatHook hook = ownedHook(id, user);
        Project project = hook.getProject();
        String error = sender.send(hook.getKind(), hook.getUrl(), new ChatMessage(project.getId(), ChatEventType.SPRINT,
                user.getName(), "connected", project.getName() + " to this channel", "/p/" + project.getKey() + "/board",
                "This is a test message from FakeJIRA."));
        hook.delivered(error);
        return new TestResult(error == null, error);
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        hooks.deleteAll(hooks.findByProjectIdOrderByIdAsc(event.projectId()));
    }

    private Project owned(String key, User user) {
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        return project;
    }

    private ChatHook ownedHook(Long id, User user) {
        ChatHook hook = hooks.findById(id).orElseThrow(() -> ApiException.notFound("Webhook not found."));
        Project project = access.memberProjectById(hook.getProject().getId());
        access.requireMember(project, user);
        access.requireOwner(project, user);
        return hook;
    }
}
