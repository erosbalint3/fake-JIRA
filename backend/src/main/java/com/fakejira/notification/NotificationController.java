package com.fakejira.notification;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvent;
import com.fakejira.project.MemberRemoved;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.project.ProjectRepository;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;

    public NotificationController(NotificationRepository notifications, NotificationPreferenceRepository preferences,
                                  ProjectRepository projects, ProjectAccess access, CurrentUser currentUser,
                                  ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.projects = projects;
        this.access = access;
        this.currentUser = currentUser;
        this.events = events;
    }

    public record NotificationResponse(Long id, String message, Long taskId, boolean read, Instant createdAt,
                                       Instant snoozedUntil, Instant doneAt) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.getId(), n.getMessage(), n.getTaskId(), n.isRead(), n.getActiveAt(),
                    n.getSnoozedUntil(), n.getArchivedAt());
        }
    }

    public record NotificationList(long unread, List<NotificationResponse> items) {
    }

    /** {@code view}: inbox (default), snoozed or done. */
    @GetMapping
    @Transactional(readOnly = true)
    public NotificationList list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String view) {
        Long userId = Long.valueOf(jwt.getSubject());
        Pageable page = Pageable.ofSize(100);
        List<Notification> found = switch (view == null ? "inbox" : view) {
            case "snoozed" -> notifications.snoozed(userId, page);
            case "done" -> notifications.done(userId, page);
            case "inbox" -> notifications.inbox(userId, page);
            default -> throw ApiException.badRequest("Unknown view.");
        };
        return new NotificationList(notifications.countByRecipientIdAndReadFalse(userId),
                found.stream().map(NotificationResponse::of).toList());
    }

    @GetMapping("/unread-count")
    public long unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return notifications.countByRecipientIdAndReadFalse(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        own(jwt, id).markRead();
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(Long.valueOf(jwt.getSubject()));
    }

    /** Triage several at once: done, undone (back to the inbox) or snooze (needs {@code until}). */
    public record TriageRequest(@NotEmpty(message = "Pick at least one notification")
                                @Size(max = 500, message = "Too many notifications at once") List<Long> ids,
                                @NotNull(message = "Choose an action") String action, Instant until) {
    }

    @PostMapping("/triage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void triage(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TriageRequest request) {
        Long userId = Long.valueOf(jwt.getSubject());
        Instant now = Instant.now();
        if ("snooze".equals(request.action()) && (request.until() == null || !request.until().isAfter(now))) {
            throw ApiException.field("until", "Snooze until a time in the future.");
        }
        if ("snooze".equals(request.action()) && request.until().isAfter(now.plus(366, ChronoUnit.DAYS))) {
            throw ApiException.field("until", "Snooze for at most a year.");
        }
        for (Notification n : notifications.findByIdInAndRecipientId(request.ids(), userId)) {
            switch (request.action()) {
                case "done" -> n.archive(now);
                case "undone" -> {
                    n.unarchive();
                    if (n.getSnoozedUntil() != null) {
                        n.wake(now);
                    }
                }
                case "snooze" -> n.snooze(request.until());
                case "read" -> n.markRead();
                default -> throw ApiException.badRequest("Unknown action.");
            }
        }
        events.publishEvent(new LiveEvent(Set.of(userId), "notification", Map.of()));
    }

    /** Moves every read inbox notification to Done. */
    @PostMapping("/done-read")
    @Transactional
    public Map<String, Integer> archiveRead(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("done", notifications.archiveRead(Long.valueOf(jwt.getSubject()), Instant.now()));
    }

    /** Brings back snoozed notifications whose time has come. */
    @Scheduled(fixedDelayString = "${app.notifications.wake-ms:60000}", initialDelay = 20_000)
    @Transactional
    public void wakeSnoozed() {
        Instant now = Instant.now();
        Set<Long> recipients = new java.util.HashSet<>();
        for (Notification n : notifications.dueToWake(now)) {
            n.wake(now);
            recipients.add(n.getRecipient().getId());
        }
        if (!recipients.isEmpty()) {
            events.publishEvent(new LiveEvent(recipients, "notification", Map.of()));
        }
    }

    private Notification own(Jwt jwt, Long id) {
        return notifications.findByIdAndRecipientId(id, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> ApiException.notFound("Notification not found."));
    }

    // ---- Rules ---------------------------------------------------------------------------------------------

    public record Rule(String projectKey, String projectName, NotificationLevel level, Boolean email, Boolean push) {
    }

    public record QuietHours(String timeZone, java.time.LocalTime from, java.time.LocalTime to) {
    }

    public record Settings(Rule defaults, List<Rule> projects, QuietHours quietHours) {
    }

    public record RuleRequest(NotificationLevel level, Boolean email, Boolean push) {
    }

    @GetMapping("/settings")
    @Transactional(readOnly = true)
    public Settings settings(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        Map<Long, NotificationPreference> byProject = new HashMap<>();
        NotificationPreference global = null;
        for (NotificationPreference p : preferences.findByUserId(user.getId())) {
            if (p.getProjectId() == null) {
                global = p;
            } else {
                byProject.put(p.getProjectId(), p);
            }
        }
        Map<Long, Project> mine = projects.findForMember(user.getId()).stream()
                .collect(Collectors.toMap(Project::getId, p -> p, (a, b) -> a, java.util.LinkedHashMap::new));
        List<Rule> rules = byProject.values().stream().filter(p -> mine.containsKey(p.getProjectId()))
                .map(p -> {
                    Project project = mine.get(p.getProjectId());
                    return new Rule(project.getKey(), project.getName(), p.getLevel(), p.getEmail(), p.getPush());
                })
                .sorted(java.util.Comparator.comparing(Rule::projectKey)).toList();
        Rule defaults = global == null ? new Rule(null, null, NotificationLevel.ALL, null, null)
                : new Rule(null, null, global.getLevel(), global.getEmail(), global.getPush());
        return new Settings(defaults, rules, new QuietHours(user.getTimeZone(), user.getQuietFrom(), user.getQuietTo()));
    }

    @PutMapping("/settings/defaults")
    @Transactional
    public Settings saveDefaults(@AuthenticationPrincipal Jwt jwt, @RequestBody RuleRequest request) {
        User user = currentUser.from(jwt);
        NotificationPreference p = preferences.findByUserIdAndProjectIdIsNull(user.getId())
                .orElseGet(() -> new NotificationPreference(user, null));
        apply(p, request);
        preferences.save(p);
        return settings(jwt);
    }

    @PutMapping("/settings/projects/{key}")
    @Transactional
    public Settings saveProjectRule(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                    @RequestBody RuleRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        NotificationPreference p = preferences.findByUserIdAndProjectId(user.getId(), project.getId())
                .orElseGet(() -> new NotificationPreference(user, project.getId()));
        apply(p, request);
        preferences.save(p);
        return settings(jwt);
    }

    @DeleteMapping("/settings/projects/{key}")
    @Transactional
    public Settings deleteProjectRule(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        preferences.findByUserIdAndProjectId(user.getId(), project.getId()).ifPresent(preferences::delete);
        return settings(jwt);
    }

    /** The rule for one project, for the mute button on the project pages. */
    @GetMapping("/settings/projects/{key}")
    @Transactional(readOnly = true)
    public Rule projectRule(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        return preferences.findByUserIdAndProjectId(user.getId(), project.getId())
                .map(p -> new Rule(project.getKey(), project.getName(), p.getLevel(), p.getEmail(), p.getPush()))
                .orElse(new Rule(project.getKey(), project.getName(), null, null, null));
    }

    @PutMapping("/settings/quiet-hours")
    @Transactional
    public Settings saveQuietHours(@AuthenticationPrincipal Jwt jwt, @RequestBody QuietHours request) {
        User user = currentUser.from(jwt);
        if (request.timeZone() != null && !request.timeZone().isBlank()) {
            try {
                java.time.ZoneId.of(request.timeZone());
            } catch (java.time.DateTimeException e) {
                throw ApiException.field("timeZone", "Unknown time zone.");
            }
            user.setTimeZone(request.timeZone());
        }
        if ((request.from() == null) != (request.to() == null)) {
            throw ApiException.field("to", "Set both the start and the end of quiet hours, or neither.");
        }
        if (request.from() != null && request.from().equals(request.to())) {
            throw ApiException.field("to", "Quiet hours must end at a different time than they start.");
        }
        user.setQuietHours(request.from(), request.to());
        return settings(jwt);
    }

    private static void apply(NotificationPreference p, RuleRequest request) {
        p.setLevel(request.level());
        p.setEmail(request.email());
        p.setPush(request.push());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        preferences.deleteForProject(event.projectId());
    }

    @EventListener
    public void onMemberRemoved(MemberRemoved event) {
        preferences.deleteForMember(event.projectId(), event.userId());
    }
}
