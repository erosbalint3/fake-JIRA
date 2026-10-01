package com.fakejira.personal;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvent;
import com.fakejira.notification.NotificationService;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Personal reminders, optionally about a task; they arrive as notifications. */
@RestController
@Transactional
public class ReminderController {

    static final int MAX_PENDING = 100;

    private final ReminderRepository reminders;
    private final TaskSupport taskSupport;
    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;

    public ReminderController(ReminderRepository reminders, TaskSupport taskSupport, NotificationService notifications,
                              CurrentUser currentUser, ApplicationEventPublisher events) {
        this.reminders = reminders;
        this.taskSupport = taskSupport;
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.events = events;
    }

    public record ReminderRequest(Long taskId,
                                  @NotNull(message = "Choose when to be reminded") Instant remindAt,
                                  @Size(max = 200, message = "Note must be at most 200 characters") String note) {
    }

    public record ReminderResponse(Long id, TaskRef task, String note, Instant remindAt) {
        static ReminderResponse of(Reminder r) {
            return new ReminderResponse(r.getId(), TaskRef.of(r.getTask()), r.getNote(), r.getRemindAt());
        }
    }

    @GetMapping("/api/reminders")
    @Transactional(readOnly = true)
    public List<ReminderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return reminders.pending(user.getId()).stream()
                .filter(r -> r.getTask() == null || r.getTask().getProject().hasMember(user))
                .map(ReminderResponse::of).toList();
    }

    @GetMapping("/api/tasks/{id}/reminders")
    @Transactional(readOnly = true)
    public List<ReminderResponse> forTask(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        taskSupport.memberTask(id, user);
        return reminders.pendingForTask(user.getId(), id).stream().map(ReminderResponse::of).toList();
    }

    @PostMapping("/api/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    public ReminderResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReminderRequest request) {
        User user = currentUser.from(jwt);
        Task task = request.taskId() == null ? null : taskSupport.memberTask(request.taskId(), user);
        String note = request.note() == null ? "" : request.note().trim();
        if (task == null && note.isEmpty()) {
            throw ApiException.field("note", "Say what to remind you about.");
        }
        Instant now = Instant.now();
        if (!request.remindAt().isAfter(now.minusSeconds(60))) {
            throw ApiException.field("remindAt", "Choose a time in the future.");
        }
        if (request.remindAt().isAfter(now.plus(366, ChronoUnit.DAYS))) {
            throw ApiException.field("remindAt", "Reminders can be at most a year ahead.");
        }
        if (reminders.pending(user.getId()).size() >= MAX_PENDING) {
            throw ApiException.badRequest("You have " + MAX_PENDING + " reminders waiting; remove some first.");
        }
        Reminder saved = reminders.save(new Reminder(user, task, note, request.remindAt()));
        changed(user);
        return ReminderResponse.of(saved);
    }

    @DeleteMapping("/api/reminders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        reminders.delete(reminders.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Reminder not found.")));
        changed(user);
    }

    @Scheduled(fixedDelayString = "${app.reminders.poll-ms:30000}", initialDelay = 15_000)
    public void sendDue() {
        Instant now = Instant.now();
        for (Reminder r : reminders.due(now)) {
            r.markSent(now);
            Task task = r.getTask();
            if (task != null && !task.getProject().hasMember(r.getUser())) {
                continue;
            }
            String about = task == null ? "" : task.getKey() + " · " + task.getTitle();
            String message = "⏰ Reminder: " + about + (about.isEmpty() || r.getNote().isEmpty() ? "" : " — ") + r.getNote();
            notifications.notifySelf(r.getUser(), message, task == null ? null : task.getId());
            changed(r.getUser());
        }
    }

    private void changed(User user) {
        events.publishEvent(new LiveEvent(Set.of(user.getId()), "reminders", Map.of()));
    }
}
