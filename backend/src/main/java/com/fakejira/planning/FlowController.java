package com.fakejira.planning;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.StatusChange;
import com.fakejira.task.StatusChangeRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Flow reports: cumulative flow, lead/cycle time and weekly throughput. */
@RestController
public class FlowController {

    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskRepository tasks;
    private final StatusChangeRepository changes;

    public FlowController(ProjectAccess access, CurrentUser currentUser, TaskRepository tasks,
                          StatusChangeRepository changes) {
        this.access = access;
        this.currentUser = currentUser;
        this.tasks = tasks;
        this.changes = changes;
    }

    /** Number of tasks in each status at the end of each day. */
    public record FlowDay(LocalDate date, int todo, int inProgress, int inReview, int done) {
    }

    public record CycleTask(Long id, String key, String title, TaskType type, Instant completedAt, double leadDays,
                            Double cycleDays) {
    }

    public record CycleReport(int count, Double leadAverage, Double leadP50, Double leadP85, Double cycleAverage,
                              Double cycleP50, Double cycleP85, List<CycleTask> tasks) {
    }

    public record Throughput(LocalDate weekStart, int tasks, int points) {
    }

    @GetMapping("/api/projects/{key}/flow")
    @Transactional(readOnly = true)
    public List<FlowDay> flow(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                              @RequestParam(defaultValue = "30") int days) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        int span = Math.max(7, Math.min(180, days));
        ZoneId zone = ZoneId.systemDefault();
        List<Task> all = tasks.findByProjectId(project.getId()).stream().filter(t -> t.getParent() == null).toList();
        Map<Long, List<StatusChange>> history = history(project);
        LocalDate today = LocalDate.now(zone);
        List<FlowDay> out = new ArrayList<>();
        for (LocalDate day = today.minusDays(span - 1); !day.isAfter(today); day = day.plusDays(1)) {
            Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
            Map<TaskStatus, Integer> counts = new EnumMap<>(TaskStatus.class);
            for (Task task : all) {
                if (task.getCreatedAt().isAfter(end)) {
                    continue;
                }
                counts.merge(statusAt(task, history.getOrDefault(task.getId(), List.of()), end), 1, Integer::sum);
            }
            out.add(new FlowDay(day, counts.getOrDefault(TaskStatus.TODO, 0), counts.getOrDefault(TaskStatus.IN_PROGRESS, 0),
                    counts.getOrDefault(TaskStatus.IN_REVIEW, 0), counts.getOrDefault(TaskStatus.DONE, 0)));
        }
        return out;
    }

    /** Lead time (created → done) and cycle time (first started → done) of tasks finished recently. */
    @GetMapping("/api/projects/{key}/cycle-time")
    @Transactional(readOnly = true)
    public CycleReport cycleTime(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                 @RequestParam(defaultValue = "90") int days) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(7, Math.min(365, days))));
        Map<Long, List<StatusChange>> history = history(project);
        List<CycleTask> done = new ArrayList<>();
        for (Task task : tasks.findByProjectId(project.getId())) {
            if (task.getStatus() != TaskStatus.DONE || task.getCompletedAt() == null || task.getCompletedAt().isBefore(since)) {
                continue;
            }
            double lead = daysBetween(task.getCreatedAt(), task.getCompletedAt());
            Instant started = history.getOrDefault(task.getId(), List.of()).stream()
                    .filter(c -> c.getToStatus() == TaskStatus.IN_PROGRESS || c.getToStatus() == TaskStatus.IN_REVIEW)
                    .map(StatusChange::getChangedAt).findFirst().orElse(null);
            Double cycle = started == null || started.isAfter(task.getCompletedAt()) ? null
                    : daysBetween(started, task.getCompletedAt());
            done.add(new CycleTask(task.getId(), task.getKey(), task.getTitle(), task.getType(), task.getCompletedAt(),
                    lead, cycle));
        }
        done.sort(Comparator.comparing(CycleTask::completedAt));
        List<Double> leads = done.stream().map(CycleTask::leadDays).sorted().toList();
        List<Double> cycles = done.stream().map(CycleTask::cycleDays).filter(c -> c != null).sorted().toList();
        return new CycleReport(done.size(), average(leads), percentile(leads, 50), percentile(leads, 85),
                average(cycles), percentile(cycles, 50), percentile(cycles, 85), done);
    }

    /** Tasks and points finished per week, for the last {@code weeks} weeks. */
    @GetMapping("/api/projects/{key}/throughput")
    @Transactional(readOnly = true)
    public List<Throughput> throughput(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                       @RequestParam(defaultValue = "12") int weeks) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        ZoneId zone = ZoneId.systemDefault();
        int span = Math.max(4, Math.min(52, weeks));
        LocalDate thisWeek = LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY);
        Map<LocalDate, int[]> byWeek = new HashMap<>();
        for (Task task : tasks.findByProjectId(project.getId())) {
            if (task.getStatus() == TaskStatus.DONE && task.getCompletedAt() != null) {
                LocalDate week = task.getCompletedAt().atZone(zone).toLocalDate().with(java.time.DayOfWeek.MONDAY);
                int[] totals = byWeek.computeIfAbsent(week, w -> new int[2]);
                totals[0]++;
                totals[1] += task.getStoryPoints() == null ? 0 : task.getStoryPoints();
            }
        }
        List<Throughput> out = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            LocalDate week = thisWeek.minusWeeks(i);
            int[] totals = byWeek.getOrDefault(week, new int[2]);
            out.add(new Throughput(week, totals[0], totals[1]));
        }
        return out;
    }

    private Map<Long, List<StatusChange>> history(Project project) {
        Map<Long, List<StatusChange>> byTask = new HashMap<>();
        for (StatusChange change : changes.findByProjectIdOrderByChangedAtAsc(project.getId())) {
            byTask.computeIfAbsent(change.getTaskId(), id -> new ArrayList<>()).add(change);
        }
        return byTask;
    }

    /**
     * The task's status at {@code end}. Tasks from before status history was kept count as To do until their
     * completion date.
     */
    static TaskStatus statusAt(Task task, List<StatusChange> history, Instant end) {
        TaskStatus status = null;
        for (StatusChange change : history) {
            if (change.getChangedAt().isAfter(end)) {
                if (status == null) {
                    status = change.getFromStatus() != null ? change.getFromStatus() : TaskStatus.TODO;
                }
                break;
            }
            status = change.getToStatus();
        }
        if (status != null) {
            return status;
        }
        if (task.getCompletedAt() != null && !task.getCompletedAt().isAfter(end)) {
            return TaskStatus.DONE;
        }
        return history.isEmpty() && task.getStatus() != TaskStatus.DONE ? task.getStatus() : TaskStatus.TODO;
    }

    private static double daysBetween(Instant from, Instant to) {
        return Math.round(Duration.between(from, to).toMinutes() / 144.0) / 10.0;
    }

    private static Double average(List<Double> values) {
        return values.isEmpty() ? null
                : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    private static Double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    // Visible for tests.
    static int isoWeek(LocalDate date) {
        return date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
