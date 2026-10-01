package com.fakejira.kudos;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.project.ProjectRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Kudos: thank teammates on finished tasks; the wall shows the latest across your projects. */
@RestController
@Transactional
public class KudosController {

    static final Set<String> EMOJI = Set.of("🎉", "🙏", "🚀", "💪", "⭐", "❤️");

    private final KudosRepository kudos;
    private final ProjectRepository projects;
    private final TaskSupport taskSupport;
    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public KudosController(KudosRepository kudos, ProjectRepository projects, TaskSupport taskSupport,
                           NotificationService notifications, CurrentUser currentUser, LiveEvents live) {
        this.kudos = kudos;
        this.projects = projects;
        this.taskSupport = taskSupport;
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record KudosRequest(@NotNull(message = "Who do you want to thank?") Long toUserId,
                               @Size(max = 280, message = "At most 280 characters") String message,
                               String emoji) {
    }

    public record KudosResponse(Long id, UserSummary from, UserSummary to, String message, String emoji, TaskRef task,
                                String projectKey, Instant createdAt) {
        static KudosResponse of(Kudos k) {
            return new KudosResponse(k.getId(), UserSummary.of(k.getFrom()), UserSummary.of(k.getTo()), k.getMessage(),
                    k.getEmoji(), TaskRef.of(k.getTask()), k.getProject().getKey(), k.getCreatedAt());
        }
    }

    public record Leader(UserSummary user, int count) {
    }

    public record Wall(List<KudosResponse> recent, List<Leader> thisMonth) {
    }

    @PostMapping("/api/tasks/{id}/kudos")
    @ResponseStatus(HttpStatus.CREATED)
    public KudosResponse give(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody KudosRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.commentableTask(id, user);
        if (task.getStatus() != TaskStatus.DONE) {
            throw ApiException.badRequest("Kudos are for finished tasks.");
        }
        Project project = task.getProject();
        User to = project.getMembers().stream().filter(m -> m.getId().equals(request.toUserId())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("You can only thank members of " + project.getKey() + "."));
        if (to.getId().equals(user.getId())) {
            throw ApiException.badRequest("Thanking yourself does not count.");
        }
        String emoji = request.emoji() != null && EMOJI.contains(request.emoji()) ? request.emoji() : "🎉";
        String message = request.message() == null || request.message().isBlank() ? "Thanks!" : request.message().trim();
        Kudos k = kudos.save(new Kudos(project, task, user, to, message, emoji));
        notifications.notify(to, user, user.getUsername() + " thanked you " + emoji + " for " + task.getKey() + " · " + message,
                task.getId());
        live.taskChanged(task);
        return KudosResponse.of(k);
    }

    @GetMapping("/api/tasks/{id}/kudos")
    @Transactional(readOnly = true)
    public List<KudosResponse> forTask(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        taskSupport.memberTask(id, currentUser.from(jwt));
        return kudos.findByTaskIdOrderByCreatedAtAsc(id).stream().map(KudosResponse::of).toList();
    }

    @GetMapping("/api/kudos")
    @Transactional(readOnly = true)
    public Wall wall(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String project) {
        User user = currentUser.from(jwt);
        List<Long> ids = projects.findForMember(user.getId()).stream()
                .filter(p -> project == null || p.getKey().equalsIgnoreCase(project)).map(Project::getId).toList();
        if (ids.isEmpty()) {
            return new Wall(List.of(), List.of());
        }
        List<Kudos> recent = kudos.recent(ids, PageRequest.of(0, 200));
        Instant monthAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Map<Long, Leader> leaders = new LinkedHashMap<>();
        for (Kudos k : recent) {
            if (k.getCreatedAt().isAfter(monthAgo)) {
                leaders.merge(k.getTo().getId(), new Leader(UserSummary.of(k.getTo()), 1),
                        (a, b) -> new Leader(a.user(), a.count() + 1));
            }
        }
        return new Wall(recent.stream().limit(60).map(KudosResponse::of).toList(),
                leaders.values().stream().sorted(Comparator.comparingInt(Leader::count).reversed()).limit(10).toList());
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        kudos.detachTask(event.taskId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        kudos.deleteForProject(event.projectId());
    }
}
