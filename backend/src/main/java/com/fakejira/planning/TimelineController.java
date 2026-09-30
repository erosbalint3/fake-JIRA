package com.fakejira.planning;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.EpicRef;
import com.fakejira.task.TaskLink;
import com.fakejira.task.TaskLinkRepository;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.user.UserSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task-level timeline (Gantt): scheduled tasks as bars, "blocks" links as dependencies, and the critical
 * path — the chain of dependent tasks with the longest total duration, which has no slack.
 */
@RestController
public class TimelineController {

    private final TaskRepository tasks;
    private final TaskLinkRepository links;
    private final ProjectAccess access;
    private final CurrentUser currentUser;

    public TimelineController(TaskRepository tasks, TaskLinkRepository links, ProjectAccess access, CurrentUser currentUser) {
        this.tasks = tasks;
        this.links = links;
        this.access = access;
        this.currentUser = currentUser;
    }

    /**
     * {@code slack}: days the task could slip without delaying the end of the longest chain; {@code conflict}: it is
     * scheduled to start before one of its blockers is due.
     */
    public record Bar(Long id, String key, String title, TaskStatus status, TaskType type, UserSummary assignee,
                      EpicRef epic, LocalDate start, LocalDate due, int days, Integer points, boolean critical,
                      int slack, boolean conflict) {
    }

    public record Dependency(Long from, Long to) {
    }

    public record Timeline(List<Bar> tasks, List<Dependency> dependencies, List<Long> criticalPath, int criticalDays,
                           int unscheduled, boolean autoSchedule) {
    }

    @GetMapping("/api/projects/{key}/timeline")
    @Transactional(readOnly = true)
    public Timeline timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        List<Task> all = tasks.findByProjectId(project.getId()).stream().filter(t -> !t.isArchived()).toList();
        Map<Long, Task> scheduled = new LinkedHashMap<>();
        int unscheduled = 0;
        for (Task t : all.stream().sorted(Comparator.comparing(TimelineController::start,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Task::getNumber)).toList()) {
            if (start(t) != null) {
                scheduled.put(t.getId(), t);
            } else if (t.getStatus() != TaskStatus.DONE) {
                unscheduled++;
            }
        }
        List<Dependency> deps = new ArrayList<>();
        Map<Long, List<Long>> successors = new HashMap<>();
        Map<Long, List<Long>> predecessors = new HashMap<>();
        for (TaskLink link : links.blocksInProject(project.getId())) {
            Long from = link.getSource().getId();
            Long to = link.getTarget().getId();
            if (scheduled.containsKey(from) && scheduled.containsKey(to)) {
                deps.add(new Dependency(from, to));
                successors.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                predecessors.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        }
        Map<Long, Integer> duration = new HashMap<>();
        scheduled.values().forEach(t -> duration.put(t.getId(), days(t)));
        List<Long> order = topological(scheduled.keySet(), successors, predecessors);

        // Forward pass (earliest start/finish in days) and backward pass (latest start) over the dependency graph.
        Map<Long, Integer> es = new HashMap<>();
        Map<Long, Integer> ef = new HashMap<>();
        for (Long id : order) {
            int start = predecessors.getOrDefault(id, List.of()).stream().filter(ef::containsKey)
                    .mapToInt(ef::get).max().orElse(0);
            es.put(id, start);
            ef.put(id, start + duration.get(id));
        }
        int length = ef.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<Long, Integer> ls = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            Long id = order.get(i);
            int finish = successors.getOrDefault(id, List.of()).stream().filter(ls::containsKey)
                    .mapToInt(ls::get).min().orElse(length);
            ls.put(id, finish - duration.get(id));
        }
        // Only chains of at least two tasks are worth calling a critical path.
        Set<Long> critical = new HashSet<>();
        for (Long id : order) {
            boolean zeroSlack = ls.get(id) - es.get(id) == 0;
            boolean linked = !successors.getOrDefault(id, List.of()).isEmpty() || !predecessors.getOrDefault(id, List.of()).isEmpty();
            if (zeroSlack && linked) {
                critical.add(id);
            }
        }
        List<Long> path = new ArrayList<>();
        Long current = order.stream().filter(critical::contains)
                .filter(id -> predecessors.getOrDefault(id, List.of()).stream().noneMatch(critical::contains))
                .findFirst().orElse(null);
        while (current != null) {
            path.add(current);
            Long here = current;
            current = successors.getOrDefault(here, List.of()).stream().filter(critical::contains)
                    .filter(next -> es.get(next).equals(ef.get(here))).findFirst().orElse(null);
        }
        if (path.size() < 2) {
            path.clear();
            critical.clear();
        } else {
            critical.retainAll(path);
        }

        List<Bar> bars = new ArrayList<>();
        for (Task t : scheduled.values()) {
            boolean conflict = predecessors.getOrDefault(t.getId(), List.of()).stream().map(scheduled::get)
                    .anyMatch(p -> p.getDueDate() != null && !start(t).isAfter(p.getDueDate()) && p.getStatus() != TaskStatus.DONE);
            bars.add(new Bar(t.getId(), t.getKey(), t.getTitle(), t.getStatus(), t.getType(), UserSummary.of(t.getAssignee()),
                    EpicRef.of(t.getEpic()), start(t), t.getDueDate() != null ? t.getDueDate() : start(t), duration.get(t.getId()),
                    t.getStoryPoints(), critical.contains(t.getId()), ls.get(t.getId()) - es.get(t.getId()), conflict));
        }
        int criticalDays = path.stream().mapToInt(duration::get).sum();
        return new Timeline(bars, deps, path, criticalDays, unscheduled, project.isAutoSchedule());
    }

    private static LocalDate start(Task t) {
        return t.getStartDate() != null ? t.getStartDate() : t.getDueDate();
    }

    private static int days(Task t) {
        LocalDate start = start(t);
        LocalDate end = t.getDueDate() != null ? t.getDueDate() : start;
        return (int) Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);
    }

    /** Kahn's algorithm; tasks caught in a cycle are appended at the end without dependencies. */
    private static List<Long> topological(Set<Long> ids, Map<Long, List<Long>> successors, Map<Long, List<Long>> predecessors) {
        Map<Long, Integer> incoming = new HashMap<>();
        ids.forEach(id -> incoming.put(id, predecessors.getOrDefault(id, List.of()).size()));
        Deque<Long> ready = new ArrayDeque<>();
        ids.stream().filter(id -> incoming.get(id) == 0).forEach(ready::add);
        List<Long> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            Long id = ready.poll();
            order.add(id);
            for (Long next : successors.getOrDefault(id, List.of())) {
                if (incoming.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        for (Long id : ids) {
            if (!order.contains(id)) {
                predecessors.remove(id);
                successors.values().forEach(list -> list.remove(id));
                order.add(id);
            }
        }
        return order;
    }
}
