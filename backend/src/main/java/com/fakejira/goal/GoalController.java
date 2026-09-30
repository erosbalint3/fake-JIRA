package com.fakejira.goal;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Goals / OKRs. A goal is visible to its owner, to everyone when shared, and to members of any project whose
 * epics it tracks. Only the owner edits it; epics can only be linked by members of their project.
 */
@RestController
@Transactional
public class GoalController {

    private final GoalRepository goals;
    private final KeyResultRepository keyResults;
    private final EpicRepository epics;
    private final TaskRepository tasks;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    public GoalController(GoalRepository goals, KeyResultRepository keyResults, EpicRepository epics, TaskRepository tasks,
                          CurrentUser currentUser, JdbcTemplate jdbc) {
        this.goals = goals;
        this.keyResults = keyResults;
        this.epics = epics;
        this.tasks = tasks;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    public record GoalRequest(
            @NotBlank(message = "Give the goal a title") @Size(max = 120, message = "At most 120 characters") String title,
            @Size(max = 2000, message = "At most 2000 characters") String description,
            @NotNull(message = "Pick a quarter") @Pattern(regexp = "^\\d{4}-Q[1-4]$", message = "Use a quarter like 2026-Q4") String quarter,
            boolean shared) {
    }

    public record KeyResultRequest(
            @NotBlank(message = "Describe the key result") @Size(max = 160, message = "At most 160 characters") String title,
            @NotNull KeyResult.Kind kind,
            BigDecimal startValue,
            BigDecimal target,
            BigDecimal current,
            @Size(max = 20, message = "At most 20 characters") String unit,
            @Size(max = 20, message = "At most 20 epics") List<Long> epicIds) {
    }

    public record EpicProgress(Long id, String name, String projectKey, int done, int total) {
    }

    public record KeyResultResponse(Long id, String title, KeyResult.Kind kind, BigDecimal startValue, BigDecimal target,
                                    BigDecimal current, String unit, int percent, List<EpicProgress> epics) {
    }

    /** {@code expected}: how far along the quarter is, to compare progress against. */
    public record GoalResponse(Long id, String title, String description, String quarter, UserSummary owner, boolean shared,
                               boolean canEdit, int percent, int expected, String health, List<KeyResultResponse> keyResults) {
    }

    @GetMapping("/api/goals")
    @Transactional(readOnly = true)
    public List<GoalResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String quarter) {
        User user = currentUser.from(jwt);
        List<Goal> all = quarter == null || quarter.isBlank() ? goals.findAllByOrderByQuarterDescCreatedAtAsc()
                : goals.findByQuarterOrderByCreatedAtAsc(quarter.trim());
        return all.stream().filter(g -> visible(g, user)).map(g -> response(g, user)).toList();
    }

    @GetMapping("/api/goals/{id}")
    @Transactional(readOnly = true)
    public GoalResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        return response(visibleGoal(id, user), user);
    }

    @PostMapping("/api/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoalRequest request) {
        User user = currentUser.from(jwt);
        Goal goal = new Goal(request.title().trim(), request.quarter(), user);
        goal.setDescription(trim(request.description()));
        goal.setShared(request.shared());
        return response(goals.save(goal), user);
    }

    @PutMapping("/api/goals/{id}")
    public GoalResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody GoalRequest request) {
        User user = currentUser.from(jwt);
        Goal goal = ownGoal(id, user);
        goal.setTitle(request.title().trim());
        goal.setDescription(trim(request.description()));
        goal.setQuarter(request.quarter());
        goal.setShared(request.shared());
        return response(goal, user);
    }

    @DeleteMapping("/api/goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Goal goal = ownGoal(id, currentUser.from(jwt));
        keyResults.deleteAll(keyResults.findByGoalIdOrderByPositionAscIdAsc(goal.getId()));
        goals.delete(goal);
    }

    @PostMapping("/api/goals/{id}/key-results")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse addKeyResult(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody KeyResultRequest request) {
        User user = currentUser.from(jwt);
        Goal goal = ownGoal(id, user);
        List<KeyResult> existing = keyResults.findByGoalIdOrderByPositionAscIdAsc(goal.getId());
        if (existing.size() >= 8) {
            throw ApiException.badRequest("A goal can have at most 8 key results.");
        }
        KeyResult kr = new KeyResult(goal, request.title().trim(), request.kind(), existing.size());
        apply(kr, request, user);
        keyResults.save(kr);
        return response(goal, user);
    }

    @PutMapping("/api/key-results/{id}")
    public GoalResponse updateKeyResult(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                        @Valid @RequestBody KeyResultRequest request) {
        User user = currentUser.from(jwt);
        KeyResult kr = keyResults.findById(id).orElseThrow(() -> ApiException.notFound("Key result not found."));
        Goal goal = ownGoal(kr.getGoal().getId(), user);
        kr.setTitle(request.title().trim());
        kr.setKind(request.kind());
        apply(kr, request, user);
        return response(goal, user);
    }

    @DeleteMapping("/api/key-results/{id}")
    public GoalResponse deleteKeyResult(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        KeyResult kr = keyResults.findById(id).orElseThrow(() -> ApiException.notFound("Key result not found."));
        Goal goal = ownGoal(kr.getGoal().getId(), user);
        keyResults.delete(kr);
        keyResults.flush();
        return response(goal, user);
    }

    /** Goals an epic contributes to (for the roadmap). */
    @GetMapping("/api/epics/{id}/goals")
    @Transactional(readOnly = true)
    public List<GoalResponse> forEpic(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Epic epic = epics.findById(id).orElseThrow(() -> ApiException.notFound("Epic not found."));
        if (!epic.getProject().hasMember(user)) {
            throw ApiException.notFound("Epic not found.");
        }
        Set<Goal> found = new LinkedHashSet<>();
        keyResults.findByEpic(id).forEach(kr -> found.add(kr.getGoal()));
        return found.stream().filter(g -> visible(g, user)).map(g -> response(g, user)).toList();
    }

    private void apply(KeyResult kr, KeyResultRequest request, User user) {
        kr.getEpics().clear();
        if (request.kind() == KeyResult.Kind.MANUAL) {
            if (request.target() == null) {
                throw ApiException.field("target", "Set a target number.");
            }
            BigDecimal start = request.startValue() == null ? BigDecimal.ZERO : request.startValue();
            if (request.target().compareTo(start) == 0) {
                throw ApiException.field("target", "The target must differ from the starting value.");
            }
            kr.setStartValue(start);
            kr.setTarget(request.target());
            kr.setCurrent(request.current() == null ? start : request.current());
            kr.setUnit(request.unit() == null || request.unit().isBlank() ? null : request.unit().trim());
        } else {
            if (request.epicIds() == null || request.epicIds().isEmpty()) {
                throw ApiException.field("epicIds", "Link at least one epic.");
            }
            for (Long epicId : new LinkedHashSet<>(request.epicIds())) {
                Epic epic = epics.findById(epicId).filter(e -> e.getProject().hasMember(user))
                        .orElseThrow(() -> ApiException.field("epicIds", "You can only link epics of your projects."));
                kr.getEpics().add(epic);
            }
            kr.setStartValue(null);
            kr.setTarget(null);
            kr.setCurrent(null);
            kr.setUnit(null);
        }
    }

    private GoalResponse response(Goal goal, User user) {
        List<KeyResultResponse> krs = new ArrayList<>();
        for (KeyResult kr : keyResults.findByGoalIdOrderByPositionAscIdAsc(goal.getId())) {
            krs.add(keyResult(kr, user));
        }
        int percent = krs.isEmpty() ? 0 : (int) Math.round(krs.stream().mapToInt(KeyResultResponse::percent).average().orElse(0));
        int expected = expected(goal.getQuarter());
        String health = krs.isEmpty() ? "none" : percent >= 100 ? "done" : percent + 15 >= expected ? "on_track"
                : percent + 35 >= expected ? "at_risk" : "off_track";
        return new GoalResponse(goal.getId(), goal.getTitle(), goal.getDescription(), goal.getQuarter(),
                UserSummary.of(goal.getOwner()), goal.isShared(), goal.getOwner().getId().equals(user.getId()),
                percent, expected, health, krs);
    }

    private KeyResultResponse keyResult(KeyResult kr, User user) {
        if (kr.getKind() == KeyResult.Kind.MANUAL) {
            BigDecimal start = kr.getStartValue() == null ? BigDecimal.ZERO : kr.getStartValue();
            BigDecimal span = kr.getTarget().subtract(start);
            BigDecimal gained = (kr.getCurrent() == null ? start : kr.getCurrent()).subtract(start);
            int percent = span.signum() == 0 ? 0 : gained.multiply(BigDecimal.valueOf(100))
                    .divide(span, 0, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).intValue();
            return new KeyResultResponse(kr.getId(), kr.getTitle(), kr.getKind(), start, kr.getTarget(), kr.getCurrent(),
                    kr.getUnit(), percent, List.of());
        }
        List<EpicProgress> list = new ArrayList<>();
        int done = 0;
        int total = 0;
        for (Epic epic : kr.getEpics()) {
            List<Task> epicTasks = tasks.findByEpicId(epic.getId());
            int d = (int) epicTasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
            done += d;
            total += epicTasks.size();
            // People outside the epic's project see the numbers but not its name.
            boolean member = epic.getProject().hasMember(user);
            list.add(new EpicProgress(member ? epic.getId() : null, member ? epic.getName() : "Epic in another project",
                    epic.getProject().getKey(), d, epicTasks.size()));
        }
        int percent = total == 0 ? 0 : done * 100 / total;
        return new KeyResultResponse(kr.getId(), kr.getTitle(), kr.getKind(), null, null, null, null, percent, list);
    }

    /** Percentage of the quarter that has passed (0 for future quarters, 100 for past ones). */
    static int expected(String quarter) {
        int year = Integer.parseInt(quarter.substring(0, 4));
        int q = quarter.charAt(6) - '0';
        LocalDate start = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        LocalDate end = start.plusMonths(3);
        LocalDate today = LocalDate.now();
        if (!today.isAfter(start)) {
            return 0;
        }
        if (!today.isBefore(end)) {
            return 100;
        }
        return (int) (ChronoUnit.DAYS.between(start, today) * 100 / ChronoUnit.DAYS.between(start, end));
    }

    private boolean visible(Goal goal, User user) {
        if (goal.isShared() || goal.getOwner().getId().equals(user.getId())) {
            return true;
        }
        return keyResults.findByGoalIdOrderByPositionAscIdAsc(goal.getId()).stream()
                .flatMap(kr -> kr.getEpics().stream()).anyMatch(e -> e.getProject().hasMember(user));
    }

    private Goal visibleGoal(Long id, User user) {
        Goal goal = goals.findById(id).orElseThrow(() -> ApiException.notFound("Goal not found."));
        if (!visible(goal, user)) {
            throw ApiException.notFound("Goal not found.");
        }
        return goal;
    }

    private Goal ownGoal(Long id, User user) {
        Goal goal = visibleGoal(id, user);
        if (!goal.getOwner().getId().equals(user.getId())) {
            throw ApiException.forbidden("Only " + goal.getOwner().getUsername() + " can change this goal.");
        }
        return goal;
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        jdbc.update("delete from key_result_epics where epic_id in (select id from epics where project_id = ?)",
                event.projectId());
    }
}
