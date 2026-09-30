package com.fakejira.report;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.StatusChange;
import com.fakejira.task.StatusChangeRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Service-level targets per priority, whether tasks meet them, and the SLA report. */
@RestController
@Transactional(readOnly = true)
public class SlaController {

    private final SlaPolicyRepository policies;
    private final StatusChangeRepository changes;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final ProjectAccess access;
    private final CurrentUser currentUser;

    public SlaController(SlaPolicyRepository policies, StatusChangeRepository changes, TaskRepository tasks,
                         TaskSupport taskSupport, ProjectAccess access, CurrentUser currentUser) {
        this.policies = policies;
        this.changes = changes;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.access = access;
        this.currentUser = currentUser;
    }

    public record Target(@NotNull TaskPriority priority,
                         @Min(value = 1, message = "At least one hour") @Max(value = 8760, message = "At most a year") Integer responseHours,
                         @Min(value = 1, message = "At least one hour") @Max(value = 8760, message = "At most a year") Integer resolveHours) {
    }

    public record TargetsRequest(@NotNull @Size(max = 4) List<@Valid Target> targets) {
    }

    /** One task's position against its targets. {@code state}: ok, at_risk (under a quarter of the time left), breached, met. */
    public record TaskSla(TaskPriority priority, Instant responseDueAt, Instant respondedAt, String responseState,
                          Instant resolveDueAt, Instant resolvedAt, String resolveState) {
    }

    public record PriorityStats(TaskPriority priority, Integer responseHours, Integer resolveHours, int tasks,
                                int responseMet, int responseBreached, int resolveMet, int resolveBreached,
                                Double averageResponseHours, Double averageResolveHours) {
    }

    public record OpenItem(TaskRef task, TaskPriority priority, String kind, String state, Instant dueAt) {
    }

    public record SlaReport(int days, List<PriorityStats> priorities, Integer responseMetPercent,
                            Integer resolveMetPercent, List<OpenItem> attention) {
    }

    @GetMapping("/api/projects/{key}/sla")
    public List<Target> targets(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        Map<TaskPriority, SlaPolicy> byPriority = new EnumMap<>(TaskPriority.class);
        policies.findByProjectId(project.getId()).forEach(p -> byPriority.put(p.getPriority(), p));
        List<Target> out = new ArrayList<>();
        for (TaskPriority priority : List.of(TaskPriority.CRITICAL, TaskPriority.HIGH, TaskPriority.MEDIUM, TaskPriority.LOW)) {
            SlaPolicy p = byPriority.get(priority);
            out.add(new Target(priority, p == null ? null : p.getResponseHours(), p == null ? null : p.getResolveHours()));
        }
        return out;
    }

    @PutMapping("/api/projects/{key}/sla")
    @Transactional
    public List<Target> saveTargets(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                    @Valid @RequestBody TargetsRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        for (Target target : request.targets()) {
            if (target.responseHours() != null && target.resolveHours() != null && target.responseHours() > target.resolveHours()) {
                throw ApiException.badRequest(target.priority().label() + ": the response target must not be longer than the resolution target.");
            }
            SlaPolicy policy = policies.findByProjectIdAndPriority(project.getId(), target.priority())
                    .orElseGet(() -> new SlaPolicy(project.getId(), target.priority()));
            policy.setTargets(target.responseHours(), target.resolveHours());
            policies.save(policy);
        }
        return targets(jwt, key);
    }

    @GetMapping("/api/tasks/{id}/sla")
    public ResponseEntity<TaskSla> taskSla(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Task task = taskSupport.memberTask(id, currentUser.from(jwt));
        SlaPolicy policy = policies.findByProjectIdAndPriority(task.getProject().getId(), task.getPriority()).orElse(null);
        if (policy == null || policy.getResponseHours() == null && policy.getResolveHours() == null) {
            return ResponseEntity.noContent().build();
        }
        Instant response = responseAt(policies.firstResponse(id), changes.findByTaskIdOrderByChangedAtAsc(id));
        return ResponseEntity.ok(evaluate(task, policy, response, Instant.now()));
    }

    /** SLA results for tasks created in the last {@code days} days, and open tasks close to or past a target. */
    @GetMapping("/api/projects/{key}/sla-report")
    public SlaReport report(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                            @RequestParam(defaultValue = "30") int days) {
        return reportFor(access.memberProject(key, currentUser.from(jwt)), days);
    }

    public SlaReport reportFor(Project project, int days) {
        int span = Math.max(1, Math.min(365, days));
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(span));
        Map<TaskPriority, SlaPolicy> byPriority = new EnumMap<>(TaskPriority.class);
        policies.findByProjectId(project.getId()).forEach(p -> byPriority.put(p.getPriority(), p));
        Map<Long, Instant> firstComments = new HashMap<>();
        for (Object[] row : policies.firstResponses(project.getId())) {
            firstComments.put((Long) row[0], (Instant) row[1]);
        }
        Map<Long, List<StatusChange>> history = new HashMap<>();
        for (StatusChange change : changes.findByProjectIdOrderByChangedAtAsc(project.getId())) {
            history.computeIfAbsent(change.getTaskId(), k -> new ArrayList<>()).add(change);
        }
        Map<TaskPriority, int[]> counts = new EnumMap<>(TaskPriority.class);
        Map<TaskPriority, List<Double>> responseTimes = new EnumMap<>(TaskPriority.class);
        Map<TaskPriority, List<Double>> resolveTimes = new EnumMap<>(TaskPriority.class);
        List<OpenItem> attention = new ArrayList<>();
        for (Task task : tasks.findByProjectId(project.getId())) {
            SlaPolicy policy = byPriority.get(task.getPriority());
            if (policy == null || task.getParent() != null) {
                continue;
            }
            Instant response = responseAt(firstComments.get(task.getId()), history.getOrDefault(task.getId(), List.of()));
            TaskSla sla = evaluate(task, policy, response, now);
            if (task.getStatus() != TaskStatus.DONE && !task.isArchived()) {
                if (isAlert(sla.responseState())) {
                    attention.add(new OpenItem(TaskRef.of(task), task.getPriority(), "response", sla.responseState(), sla.responseDueAt()));
                }
                if (isAlert(sla.resolveState())) {
                    attention.add(new OpenItem(TaskRef.of(task), task.getPriority(), "resolution", sla.resolveState(), sla.resolveDueAt()));
                }
            }
            if (task.getCreatedAt().isBefore(since)) {
                continue;
            }
            int[] c = counts.computeIfAbsent(task.getPriority(), p -> new int[5]);
            c[0]++;
            if ("met".equals(sla.responseState())) c[1]++;
            if ("breached".equals(sla.responseState())) c[2]++;
            if ("met".equals(sla.resolveState())) c[3]++;
            if ("breached".equals(sla.resolveState())) c[4]++;
            if (response != null) {
                responseTimes.computeIfAbsent(task.getPriority(), p -> new ArrayList<>()).add(ReportMath.hours(task.getCreatedAt(), response));
            }
            if (task.getStatus() == TaskStatus.DONE && task.getCompletedAt() != null) {
                resolveTimes.computeIfAbsent(task.getPriority(), p -> new ArrayList<>())
                        .add(ReportMath.hours(task.getCreatedAt(), task.getCompletedAt()));
            }
        }
        List<PriorityStats> stats = new ArrayList<>();
        int responseMet = 0;
        int responseJudged = 0;
        int resolveMet = 0;
        int resolveJudged = 0;
        for (TaskPriority priority : List.of(TaskPriority.CRITICAL, TaskPriority.HIGH, TaskPriority.MEDIUM, TaskPriority.LOW)) {
            SlaPolicy policy = byPriority.get(priority);
            int[] c = counts.getOrDefault(priority, new int[5]);
            stats.add(new PriorityStats(priority, policy == null ? null : policy.getResponseHours(),
                    policy == null ? null : policy.getResolveHours(), c[0], c[1], c[2], c[3], c[4],
                    ReportMath.average(responseTimes.getOrDefault(priority, List.of())),
                    ReportMath.average(resolveTimes.getOrDefault(priority, List.of()))));
            responseMet += c[1];
            responseJudged += c[1] + c[2];
            resolveMet += c[3];
            resolveJudged += c[3] + c[4];
        }
        attention.sort(Comparator.comparing((OpenItem i) -> !"breached".equals(i.state())).thenComparing(OpenItem::dueAt));
        return new SlaReport(span, stats,
                responseJudged == 0 ? null : (int) Math.round(responseMet * 100.0 / responseJudged),
                resolveJudged == 0 ? null : (int) Math.round(resolveMet * 100.0 / resolveJudged),
                attention);
    }

    private static boolean isAlert(String state) {
        return "breached".equals(state) || "at_risk".equals(state);
    }

    /** The first sign of life: a comment from someone other than the reporter, or work starting. */
    static Instant responseAt(Instant firstComment, List<StatusChange> history) {
        Instant moved = history.stream().filter(c -> c.getToStatus() != TaskStatus.TODO)
                .map(StatusChange::getChangedAt).findFirst().orElse(null);
        if (firstComment == null) return moved;
        if (moved == null) return firstComment;
        return firstComment.isBefore(moved) ? firstComment : moved;
    }

    static TaskSla evaluate(Task task, SlaPolicy policy, Instant respondedAt, Instant now) {
        Instant created = task.getCreatedAt();
        Instant resolvedAt = task.getStatus() == TaskStatus.DONE ? task.getCompletedAt() : null;
        if (resolvedAt != null && (respondedAt == null || resolvedAt.isBefore(respondedAt))) {
            respondedAt = resolvedAt;
        }
        Instant responseDue = policy.getResponseHours() == null ? null : created.plus(Duration.ofHours(policy.getResponseHours()));
        Instant resolveDue = policy.getResolveHours() == null ? null : created.plus(Duration.ofHours(policy.getResolveHours()));
        return new TaskSla(task.getPriority(), responseDue, respondedAt, state(created, responseDue, respondedAt, now),
                resolveDue, resolvedAt, state(created, resolveDue, resolvedAt, now));
    }

    static String state(Instant start, Instant due, Instant doneAt, Instant now) {
        if (due == null) {
            return null;
        }
        if (doneAt != null) {
            return doneAt.isAfter(due) ? "breached" : "met";
        }
        if (now.isAfter(due)) {
            return "breached";
        }
        long total = Duration.between(start, due).toMinutes();
        long left = Duration.between(now, due).toMinutes();
        return left * 4 < total ? "at_risk" : "ok";
    }

    @EventListener
    @Transactional
    public void onProjectDeleting(ProjectDeleting event) {
        policies.deleteForProject(event.projectId());
    }
}
