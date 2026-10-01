package com.fakejira.report;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.release.Release;
import com.fakejira.release.ReleaseRepository;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.task.Resolution;
import com.fakejira.task.StatusChange;
import com.fakejira.task.StatusChangeRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Forecasts and health reports: Monte Carlo forecast, release burn-up, aging work in progress, bug trends. */
@RestController
@Transactional(readOnly = true)
public class InsightsController {

    static final int SIMULATIONS = 10_000;
    static final int[] CONFIDENCE = {50, 70, 85, 95};

    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskRepository tasks;
    private final StatusChangeRepository changes;
    private final ReleaseRepository releases;
    private final EpicRepository epics;
    private final SprintRepository sprints;

    public InsightsController(ProjectAccess access, CurrentUser currentUser, TaskRepository tasks,
                              StatusChangeRepository changes, ReleaseRepository releases, EpicRepository epics,
                              SprintRepository sprints) {
        this.access = access;
        this.currentUser = currentUser;
        this.tasks = tasks;
        this.changes = changes;
        this.releases = releases;
        this.epics = epics;
        this.sprints = sprints;
    }

    // ---- Monte Carlo forecast --------------------------------------------------------------------------------

    public record Estimate(int confidence, int weeks, LocalDate date) {
    }

    public record Capacity(int confidence, int items) {
    }

    public record Forecast(String scope, int remaining, List<Integer> weeklyThroughput, boolean enoughData,
                           List<Estimate> completion, Map<Integer, Integer> histogram, LocalDate targetDate,
                           Integer targetProbability, List<Capacity> byTarget) {
    }

    /**
     * When will the remaining work be done? Replays the weekly throughput of the last {@code weeks} weeks
     * {@value #SIMULATIONS} times. Scope: a release, epic or sprint, or every open task. With {@code by} (or a
     * release date) it also answers "how likely by then" and "how many items by then".
     */
    @GetMapping("/api/projects/{key}/forecast")
    public Forecast forecast(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                             @RequestParam(required = false) Long release, @RequestParam(required = false) Long epic,
                             @RequestParam(required = false) Long sprint, @RequestParam(required = false) Integer items,
                             @RequestParam(required = false) LocalDate by,
                             @RequestParam(defaultValue = "12") int weeks) {
        return forecastFor(access.memberProject(key, currentUser.from(jwt)), release, epic, sprint, items, by, weeks);
    }

    public Forecast forecastFor(Project project, Long release, Long epic, Long sprint, Integer items, LocalDate by, int weeks) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<Task> all = tasks.findByProjectId(project.getId()).stream().filter(t -> t.getParent() == null).toList();

        String scope = "All open tasks";
        List<Task> inScope = all;
        LocalDate target = by;
        if (release != null) {
            Release r = releases.findById(release).filter(x -> x.getProject().getId().equals(project.getId()))
                    .orElseThrow(() -> ApiException.notFound("Release not found."));
            scope = "Release " + r.getName();
            inScope = all.stream().filter(t -> t.getRelease() != null && t.getRelease().getId().equals(release)).toList();
            if (target == null) {
                target = r.getReleaseDate();
            }
        } else if (epic != null) {
            Epic e = epics.findById(epic).filter(x -> x.getProject().getId().equals(project.getId()))
                    .orElseThrow(() -> ApiException.notFound("Epic not found."));
            scope = "Epic " + e.getName();
            inScope = all.stream().filter(t -> t.getEpic() != null && t.getEpic().getId().equals(epic)).toList();
        } else if (sprint != null) {
            Sprint s = sprints.findById(sprint).filter(x -> x.getProject().getId().equals(project.getId()))
                    .orElseThrow(() -> ApiException.notFound("Sprint not found."));
            scope = "Sprint " + s.getName();
            inScope = all.stream().filter(t -> t.getSprint() != null && t.getSprint().getId().equals(sprint)).toList();
        }
        int remaining = items != null ? Math.max(0, Math.min(10_000, items))
                : (int) inScope.stream().filter(t -> t.getStatus() != TaskStatus.DONE && !t.isArchived()).count();
        if (items != null) {
            scope = items + " items";
        }

        // Finished tasks per complete week, oldest first.
        int span = Math.max(4, Math.min(52, weeks));
        LocalDate thisWeek = today.with(DayOfWeek.MONDAY);
        int[] samples = new int[span];
        for (Task task : all) {
            if (task.getStatus() == TaskStatus.DONE && task.getCompletedAt() != null) {
                LocalDate week = task.getCompletedAt().atZone(zone).toLocalDate().with(DayOfWeek.MONDAY);
                long back = java.time.temporal.ChronoUnit.WEEKS.between(week, thisWeek);
                if (back >= 1 && back <= span) {
                    samples[(int) (span - back)]++;
                }
            }
        }
        // Weeks before anything was ever finished say nothing about the team's pace.
        int first = 0;
        while (first < samples.length - 1 && samples[first] == 0) {
            first++;
        }
        samples = Arrays.copyOfRange(samples, first, samples.length);
        List<Integer> history = Arrays.stream(samples).boxed().toList();
        boolean enough = Arrays.stream(samples).filter(s -> s > 0).count() >= 2;
        if (!enough || remaining == 0) {
            return new Forecast(scope, remaining, history, enough, List.of(), Map.of(), target, remaining == 0 ? 100 : null,
                    List.of());
        }

        SplittableRandom random = new SplittableRandom(42);
        int[] outcomes = new int[SIMULATIONS];
        Map<Integer, Integer> histogram = new java.util.TreeMap<>();
        for (int i = 0; i < SIMULATIONS; i++) {
            int left = remaining;
            int week = 0;
            while (left > 0 && week < 520) {
                left -= samples[random.nextInt(samples.length)];
                week++;
            }
            outcomes[i] = week;
            histogram.merge(week, 1, Integer::sum);
        }
        Arrays.sort(outcomes);
        List<Estimate> completion = new ArrayList<>();
        for (int c : CONFIDENCE) {
            int w = ReportMath.percentileInt(outcomes, c);
            completion.add(new Estimate(c, w, today.plusWeeks(w)));
        }

        Integer probability = null;
        List<Capacity> byTarget = List.of();
        if (target != null) {
            long weeksLeft = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, target) / 7);
            final long limit = weeksLeft;
            probability = (int) Math.round(Arrays.stream(outcomes).filter(w -> w <= limit).count() * 100.0 / SIMULATIONS);
            int[] sums = new int[SIMULATIONS];
            for (int i = 0; i < SIMULATIONS; i++) {
                int sum = 0;
                for (int w = 0; w < weeksLeft; w++) {
                    sum += samples[random.nextInt(samples.length)];
                }
                sums[i] = sum;
            }
            Arrays.sort(sums);
            List<Capacity> capacity = new ArrayList<>();
            // "85% confident of at least N items" is the 15th percentile of the simulated totals.
            for (int c : CONFIDENCE) {
                capacity.add(new Capacity(c, ReportMath.percentileInt(sums, 100 - c)));
            }
            byTarget = capacity;
        }
        return new Forecast(scope, remaining, history, true, completion, histogram, target, probability, byTarget);
    }

    // ---- Release burn-up ---------------------------------------------------------------------------------------

    public record BurnupDay(LocalDate date, int scope, int done, int scopePoints, int donePoints) {
    }

    public record Burnup(Long releaseId, String name, LocalDate releaseDate, List<BurnupDay> days,
                         LocalDate projectedDate, double dailyRate) {
    }

    /** Scope and completed work of a release, day by day, with a straight-line projection. */
    @GetMapping("/api/releases/{id}/burnup")
    public Burnup burnup(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                         @RequestParam(defaultValue = "60") int days) {
        User user = currentUser.from(jwt);
        Release release = releases.findById(id).filter(r -> r.getProject().hasMember(user))
                .orElseThrow(() -> ApiException.notFound("Release not found."));
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<Task> scope = tasks.findByReleaseId(id).stream().filter(t -> t.getParent() == null).toList();
        LocalDate start = scope.stream().map(t -> t.getCreatedAt().atZone(zone).toLocalDate()).min(Comparator.naturalOrder())
                .orElse(today);
        start = start.isBefore(today.minusDays(Math.max(7, Math.min(365, days)) - 1))
                ? today.minusDays(Math.max(7, Math.min(365, days)) - 1) : start;
        List<BurnupDay> out = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
            int total = 0;
            int done = 0;
            int totalPoints = 0;
            int donePoints = 0;
            for (Task task : scope) {
                if (task.getCreatedAt().isAfter(end)) {
                    continue;
                }
                int points = task.getStoryPoints() == null ? 0 : task.getStoryPoints();
                total++;
                totalPoints += points;
                if (task.getStatus() == TaskStatus.DONE && task.getCompletedAt() != null && !task.getCompletedAt().isAfter(end)) {
                    done++;
                    donePoints += points;
                }
            }
            out.add(new BurnupDay(day, total, done, totalPoints, donePoints));
        }
        // Projection from the completion rate of the last two weeks.
        BurnupDay last = out.get(out.size() - 1);
        BurnupDay twoWeeksAgo = out.get(Math.max(0, out.size() - 15));
        int window = Math.max(1, out.size() - 1 - Math.max(0, out.size() - 15));
        double rate = (last.done() - twoWeeksAgo.done()) / (double) window;
        LocalDate projected = null;
        if (last.done() >= last.scope() && last.scope() > 0) {
            projected = today;
        } else if (rate > 0) {
            projected = today.plusDays((long) Math.ceil((last.scope() - last.done()) / rate));
        }
        return new Burnup(release.getId(), release.getName(), release.getReleaseDate(), out, projected,
                Math.round(rate * 100) / 100.0);
    }

    // ---- Aging work in progress ------------------------------------------------------------------------------

    public record AgingItem(TaskRef task, TaskStatus status, String column, UserSummary assignee, Instant startedAt,
                            double ageDays, String level) {
    }

    public record AgingWip(Double cycleP50, Double cycleP85, List<AgingItem> items) {
    }

    /**
     * How long each task in progress has been in progress, against the project's recent cycle times:
     * "ok" below the median, "watch" up to the 85th percentile, "late" beyond it.
     */
    @GetMapping("/api/projects/{key}/aging-wip")
    public AgingWip agingWip(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return agingFor(access.memberProject(key, currentUser.from(jwt)));
    }

    public AgingWip agingFor(Project project) {
        Map<Long, List<StatusChange>> history = history(project);
        Instant now = Instant.now();
        Instant since = now.minus(java.time.Duration.ofDays(90));
        List<Double> cycles = new ArrayList<>();
        List<AgingItem> items = new ArrayList<>();
        for (Task task : tasks.findByProjectId(project.getId())) {
            Instant started = ReportMath.startedAt(history.getOrDefault(task.getId(), List.of()));
            if (task.getStatus() == TaskStatus.DONE) {
                if (started != null && task.getCompletedAt() != null && task.getCompletedAt().isAfter(since)
                        && started.isBefore(task.getCompletedAt())) {
                    cycles.add(ReportMath.days(started, task.getCompletedAt()));
                }
            } else if ((task.getStatus() == TaskStatus.IN_PROGRESS || task.getStatus() == TaskStatus.IN_REVIEW)
                    && !task.isArchived()) {
                Instant from = started != null ? started : task.getCreatedAt();
                items.add(new AgingItem(TaskRef.of(task), task.getStatus(),
                        task.getBoardColumn() == null ? null : task.getBoardColumn().getName(),
                        UserSummary.of(task.getAssignee()), from, ReportMath.days(from, now), null));
            }
        }
        cycles.sort(Comparator.naturalOrder());
        Double p50 = ReportMath.percentile(cycles, 50);
        Double p85 = ReportMath.percentile(cycles, 85);
        List<AgingItem> leveled = items.stream()
                .map(i -> new AgingItem(i.task(), i.status(), i.column(), i.assignee(), i.startedAt(), i.ageDays(),
                        p50 == null ? "ok" : i.ageDays() > p85 ? "late" : i.ageDays() > p50 ? "watch" : "ok"))
                .sorted(Comparator.comparingDouble(AgingItem::ageDays).reversed()).toList();
        return new AgingWip(p50, p85, leveled);
    }

    // ---- Bug trends --------------------------------------------------------------------------------------------

    public record BugWeek(LocalDate weekStart, int created, int resolved, int open) {
    }

    public record BugTrends(List<BugWeek> weeks, Map<TaskPriority, Integer> openByPriority,
                            Map<Resolution, Integer> resolutions, Double meanDaysToResolve, List<TaskRef> oldestOpen) {
    }

    /** Bugs created and resolved per week, open bugs at each week's end, and what happened to them. */
    @GetMapping("/api/projects/{key}/bug-trends")
    public BugTrends bugTrends(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                               @RequestParam(defaultValue = "12") int weeks) {
        return bugsFor(access.memberProject(key, currentUser.from(jwt)), weeks);
    }

    public BugTrends bugsFor(Project project, int weeks) {
        ZoneId zone = ZoneId.systemDefault();
        int span = Math.max(4, Math.min(52, weeks));
        LocalDate thisWeek = LocalDate.now(zone).with(DayOfWeek.MONDAY);
        List<Task> bugs = tasks.findByProjectId(project.getId()).stream().filter(t -> t.getType() == TaskType.BUG).toList();
        List<BugWeek> out = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            LocalDate week = thisWeek.minusWeeks(i);
            Instant from = week.atStartOfDay(zone).toInstant();
            Instant to = week.plusWeeks(1).atStartOfDay(zone).toInstant();
            int created = 0;
            int resolved = 0;
            int open = 0;
            for (Task bug : bugs) {
                Instant done = bug.getStatus() == TaskStatus.DONE ? bug.getCompletedAt() : null;
                if (!bug.getCreatedAt().isBefore(from) && bug.getCreatedAt().isBefore(to)) {
                    created++;
                }
                if (done != null && !done.isBefore(from) && done.isBefore(to)) {
                    resolved++;
                }
                if (bug.getCreatedAt().isBefore(to) && (done == null || !done.isBefore(to))) {
                    open++;
                }
            }
            out.add(new BugWeek(week, created, resolved, open));
        }
        Map<TaskPriority, Integer> byPriority = new EnumMap<>(TaskPriority.class);
        for (TaskPriority p : TaskPriority.values()) {
            byPriority.put(p, 0);
        }
        Map<Resolution, Integer> resolutions = new LinkedHashMap<>();
        Instant windowStart = thisWeek.minusWeeks(span - 1L).atStartOfDay(zone).toInstant();
        List<Double> times = new ArrayList<>();
        for (Task bug : bugs) {
            if (bug.getStatus() != TaskStatus.DONE) {
                if (!bug.isArchived()) {
                    byPriority.merge(bug.getPriority(), 1, Integer::sum);
                }
            } else if (bug.getCompletedAt() != null && !bug.getCompletedAt().isBefore(windowStart)) {
                resolutions.merge(bug.getResolution() == null ? Resolution.DONE : bug.getResolution(), 1, Integer::sum);
                times.add(ReportMath.days(bug.getCreatedAt(), bug.getCompletedAt()));
            }
        }
        List<TaskRef> oldest = bugs.stream().filter(b -> b.getStatus() != TaskStatus.DONE && !b.isArchived())
                .sorted(Comparator.comparing(Task::getCreatedAt)).limit(5).map(TaskRef::of).toList();
        return new BugTrends(out, byPriority, resolutions, ReportMath.average(times), oldest);
    }

    private Map<Long, List<StatusChange>> history(Project project) {
        Map<Long, List<StatusChange>> byTask = new HashMap<>();
        for (StatusChange change : changes.findByProjectIdOrderByChangedAtAsc(project.getId())) {
            byTask.computeIfAbsent(change.getTaskId(), id -> new ArrayList<>()).add(change);
        }
        return byTask;
    }
}
