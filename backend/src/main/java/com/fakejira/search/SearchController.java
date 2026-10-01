package com.fakejira.search;

import com.fakejira.common.CurrentUser;
import com.fakejira.search.SearchService.Hit;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
public class SearchController {

    private final SearchService search;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;

    public SearchController(SearchService search, TaskSupport taskSupport, CurrentUser currentUser) {
        this.search = search;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
    }

    public record SearchResponse(int total, boolean truncated, List<TaskResponse> tasks) {
    }

    /** One bucket of a grouped count; {@code key} is stable (id, enum name, username), {@code label} is for display. */
    public record Group(String key, String label, int count, int points, int done) {
    }

    public record Field(String name, String hint, List<String> values) {
    }

    /** FQL search, e.g. {@code project = WEB AND status != done ORDER BY priority DESC}. */
    @GetMapping("/api/search")
    @Transactional(readOnly = true)
    public SearchResponse search(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q,
                                 @RequestParam(defaultValue = "200") int limit) {
        SearchService.Result result = search.run(currentUser.from(jwt), q, limit);
        return new SearchResponse(result.total(), result.total() > result.tasks().size(), taskSupport.responses(result.tasks()));
    }

    /** Counts of the query's tasks grouped by a field, for dashboard charts. */
    @GetMapping("/api/search/stats")
    @Transactional(readOnly = true)
    public List<Group> stats(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q,
                             @RequestParam(defaultValue = "status") String groupBy) {
        User user = currentUser.from(jwt);
        List<Task> all = search.run(user, q, SearchService.MAX_RESULTS).tasks();
        Function<Task, String[]> keyOf = switch (FqlCompiler.canonical(groupBy.toLowerCase())) {
            case "priority" -> t -> new String[]{t.getPriority().name(), t.getPriority().label()};
            case "type" -> t -> new String[]{t.getType().name(), t.getType().label()};
            case "assignee" -> t -> t.getAssignee() == null ? new String[]{"", "Unassigned"}
                    : new String[]{t.getAssignee().getUsername(), t.getAssignee().getName()};
            case "project" -> t -> new String[]{t.getProject().getKey(), t.getProject().getName()};
            case "epic" -> t -> t.getEpic() == null ? new String[]{"", "No epic"}
                    : new String[]{String.valueOf(t.getEpic().getId()), t.getEpic().getName()};
            case "sprint" -> t -> t.getSprint() == null ? new String[]{"", "Backlog"}
                    : new String[]{String.valueOf(t.getSprint().getId()), t.getSprint().getName()};
            case "release" -> t -> t.getRelease() == null ? new String[]{"", "No release"}
                    : new String[]{String.valueOf(t.getRelease().getId()), t.getRelease().getName()};
            case "resolution" -> t -> t.getResolution() == null ? new String[]{"", "Unresolved"}
                    : new String[]{t.getResolution().name(), t.getResolution().name().charAt(0)
                    + t.getResolution().name().substring(1).toLowerCase().replace('_', ' ')};
            case "reporter" -> t -> new String[]{t.getReporter().getUsername(), t.getReporter().getName()};
            default -> t -> new String[]{t.getStatus().name(), t.getStatus().label()};
        };
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Task task : all) {
            String[] key = keyOf.apply(task);
            labels.putIfAbsent(key[0], key[1]);
            int[] c = counts.computeIfAbsent(key[0], k -> new int[3]);
            c[0]++;
            c[1] += task.getStoryPoints() == null ? 0 : task.getStoryPoints();
            if (task.getStatus() == TaskStatus.DONE) {
                c[2]++;
            }
        }
        List<Group> groups = new ArrayList<>();
        counts.forEach((key, c) -> groups.add(new Group(key, labels.get(key), c[0], c[1], c[2])));
        boolean ordinal = groupBy.equalsIgnoreCase("status") || groupBy.equalsIgnoreCase("priority");
        if (!ordinal) {
            groups.sort(Comparator.comparingInt(Group::count).reversed());
        } else if (groupBy.equalsIgnoreCase("status")) {
            groups.sort(Comparator.comparing(g -> TaskStatus.valueOf(g.key())));
        } else {
            groups.sort(Comparator.comparing((Group g) -> com.fakejira.task.TaskPriority.valueOf(g.key())).reversed());
        }
        return groups;
    }

    public record TrendWeek(java.time.LocalDate weekStart, int created, int resolved, int open) {
    }

    /** Created, resolved and still-open counts per week for the query's tasks (for trend charts). */
    @GetMapping("/api/search/trend")
    @Transactional(readOnly = true)
    public List<TrendWeek> trend(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q,
                                 @RequestParam(defaultValue = "12") int weeks) {
        List<Task> all = search.run(currentUser.from(jwt), q, SearchService.MAX_RESULTS).tasks();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        int span = Math.max(4, Math.min(52, weeks));
        java.time.LocalDate thisWeek = java.time.LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY);
        List<TrendWeek> out = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            java.time.LocalDate week = thisWeek.minusWeeks(i);
            java.time.Instant from = week.atStartOfDay(zone).toInstant();
            java.time.Instant to = week.plusWeeks(1).atStartOfDay(zone).toInstant();
            int created = 0;
            int resolved = 0;
            int open = 0;
            for (Task task : all) {
                java.time.Instant done = task.getStatus() == TaskStatus.DONE ? task.getCompletedAt() : null;
                if (!task.getCreatedAt().isBefore(from) && task.getCreatedAt().isBefore(to)) created++;
                if (done != null && !done.isBefore(from) && done.isBefore(to)) resolved++;
                if (task.getCreatedAt().isBefore(to) && (done == null || !done.isBefore(to))) open++;
            }
            out.add(new TrendWeek(week, created, resolved, open));
        }
        return out;
    }

    /** Fields and common values for the query editor's autocomplete. */
    @GetMapping("/api/search/fields")
    public List<Field> fields() {
        return FqlCompiler.FIELDS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new Field(e.getKey(), e.getValue(), FqlCompiler.staticValues(e.getKey()))).toList();
    }

    /** The quick search box: words across tasks, comments, file names, epics and releases. */
    @GetMapping("/api/search/text")
    public List<Hit> text(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q,
                          @RequestParam(defaultValue = "30") int limit) {
        return search.text(currentUser.from(jwt), q, Math.max(1, Math.min(100, limit)));
    }
}
