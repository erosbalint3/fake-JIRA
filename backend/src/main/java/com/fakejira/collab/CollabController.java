package com.fakejira.collab;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvent;
import com.fakejira.task.Task;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Presence ("Anna is viewing this task") and live co-editing of descriptions. Updates are relayed to the
 * task's project members over the live event stream; clients ignore tasks they do not have open.
 */
@RestController
@Transactional(readOnly = true)
public class CollabController {

    private final CollabHub hub;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public CollabController(CollabHub hub, TaskSupport taskSupport, CurrentUser currentUser, UserRepository users,
                            ApplicationEventPublisher events) {
        this.hub = hub;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.users = users;
        this.events = events;
    }

    public record PresenceRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String clientId,
                                  boolean editing) {
    }

    public record ClientRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String clientId) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String clientId,
                                @NotBlank @Size(max = 262144) @Pattern(regexp = "^[A-Za-z0-9+/=]+$") String update) {
    }

    /** One entry per person (several tabs of the same person collapse into one). */
    public record Present(UserSummary user, boolean editing) {
    }

    @PostMapping("/api/tasks/{id}/presence")
    public List<Present> heartbeat(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody PresenceRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        if (hub.heartbeat(id, user.getId(), request.clientId(), request.editing())) {
            broadcast(task, "presence", Map.of("taskId", id));
        }
        return present(id);
    }

    @PostMapping("/api/tasks/{id}/presence/leave")
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        if (hub.leave(id, request.clientId())) {
            broadcast(task, "presence", Map.of("taskId", id));
        }
    }

    @GetMapping("/api/tasks/{id}/presence")
    public List<Present> presence(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        taskSupport.memberTask(id, currentUser.from(jwt));
        return present(id);
    }

    @PostMapping("/api/tasks/{id}/collab/join")
    public CollabHub.JoinResult join(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        User user = currentUser.from(jwt);
        taskSupport.editableTask(id, user);
        return hub.join(id, request.clientId());
    }

    @PostMapping("/api/tasks/{id}/collab/update")
    public void update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        if (!hub.append(id, request.clientId(), request.update())) {
            throw ApiException.conflict("The editing session ended; reopen the editor to keep co-editing.");
        }
        broadcast(task, "collab", Map.of("taskId", id, "clientId", request.clientId(), "update", request.update()));
    }

    @PostMapping("/api/tasks/{id}/collab/leave")
    public void leaveSession(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        taskSupport.memberTask(id, currentUser.from(jwt));
        hub.leaveSession(id, request.clientId());
    }

    private List<Present> present(Long taskId) {
        Map<Long, Boolean> byUser = new LinkedHashMap<>();
        hub.viewers(taskId).forEach(v -> byUser.merge(v.userId(), v.editing(), Boolean::logicalOr));
        List<Present> result = new ArrayList<>();
        users.findAllById(byUser.keySet()).forEach(u -> result.add(new Present(UserSummary.of(u), byUser.get(u.getId()))));
        return result;
    }

    private void broadcast(Task task, String type, Map<String, Object> data) {
        Set<Long> recipients = task.getProject().getMembers().stream().map(User::getId).collect(Collectors.toSet());
        events.publishEvent(new LiveEvent(recipients, type, data));
    }
}
