package com.fakejira.sprint;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TimeEntryRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sprint goals (a checklist the review reports on) and capacity planning per person. */
@RestController
@Transactional
public class SprintPlanningController {

    static final BigDecimal DEFAULT_HOURS = BigDecimal.valueOf(6);

    private final SprintRepository sprints;
    private final SprintGoalRepository goals;
    private final SprintCapacityRepository capacity;
    private final TaskRepository tasks;
    private final TimeEntryRepository time;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public SprintPlanningController(SprintRepository sprints, SprintGoalRepository goals, SprintCapacityRepository capacity,
                                    TaskRepository tasks, TimeEntryRepository time, ProjectAccess access,
                                    CurrentUser currentUser, LiveEvents live) {
        this.sprints = sprints;
        this.goals = goals;
        this.capacity = capacity;
        this.tasks = tasks;
        this.time = time;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record GoalRequest(@NotBlank(message = "Describe the goal") @Size(max = 200, message = "At most 200 characters") String text) {
    }

    public record GoalUpdate(@Size(min = 1, max = 200, message = "Goals are 1-200 characters") String text, Boolean done) {
    }

    public record GoalResponse(Long id, String text, boolean done) {
        static GoalResponse of(SprintGoal g) {
            return new GoalResponse(g.getId(), g.getText(), g.isDone());
        }
    }

    public record CapacityRequest(
            @NotNull @DecimalMin(value = "0", message = "Hours cannot be negative")
            @DecimalMax(value = "12", message = "At most 12 hours a day") BigDecimal hoursPerDay,
            @Min(value = 0, message = "Days off cannot be negative") @Max(value = 60, message = "At most 60 days off") int daysOff) {
    }

    /**
     * One person's sprint: working days, days away (time off plus extra days off), available hours, and the
     * remaining estimated hours of their unfinished tasks in the sprint.
     */
    public record PersonCapacity(UserSummary user, int workingDays, int awayDays, int daysOff, BigDecimal hoursPerDay,
                                 BigDecimal availableHours, BigDecimal remainingHours, int tasks, int unestimated,
                                 int points, boolean over) {
    }

    public record CapacityResponse(LocalDate start, LocalDate end, boolean datesAssumed, int workingDays,
                                   List<PersonCapacity> people, BigDecimal availableHours, BigDecimal remainingHours) {
    }

    // ---------------------------------------------------------------- goals

    @GetMapping("/api/sprints/{id}/goals")
    @Transactional(readOnly = true)
    public List<GoalResponse> goals(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Sprint sprint = memberSprint(id, currentUser.from(jwt));
        return goals.findBySprintIdOrderByPositionAscIdAsc(sprint.getId()).stream().map(GoalResponse::of).toList();
    }

    @PostMapping("/api/sprints/{id}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse addGoal(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody GoalRequest request) {
        Sprint sprint = editableSprint(id, currentUser.from(jwt));
        List<SprintGoal> existing = goals.findBySprintIdOrderByPositionAscIdAsc(sprint.getId());
        if (existing.size() >= 10) {
            throw ApiException.badRequest("A sprint can have at most 10 goals.");
        }
        SprintGoal goal = goals.save(new SprintGoal(sprint, request.text().trim(), existing.size()));
        live.projectChanged(sprint.getProject());
        return GoalResponse.of(goal);
    }

    @PatchMapping("/api/sprint-goals/{id}")
    public GoalResponse updateGoal(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody GoalUpdate request) {
        SprintGoal goal = goals.findById(id).orElseThrow(() -> ApiException.notFound("Goal not found."));
        editableSprint(goal.getSprint().getId(), currentUser.from(jwt));
        if (request.text() != null) {
            goal.setText(request.text().trim());
        }
        if (request.done() != null) {
            goal.setDone(request.done());
        }
        live.projectChanged(goal.getSprint().getProject());
        return GoalResponse.of(goal);
    }

    @DeleteMapping("/api/sprint-goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        SprintGoal goal = goals.findById(id).orElseThrow(() -> ApiException.notFound("Goal not found."));
        editableSprint(goal.getSprint().getId(), currentUser.from(jwt));
        goals.delete(goal);
        live.projectChanged(goal.getSprint().getProject());
    }

    // ---------------------------------------------------------------- capacity

    @GetMapping("/api/sprints/{id}/capacity")
    @Transactional(readOnly = true)
    public CapacityResponse capacity(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Sprint sprint = memberSprint(id, currentUser.from(jwt));
        return capacityOf(sprint);
    }

    @PutMapping("/api/sprints/{id}/capacity/{userId}")
    public CapacityResponse setCapacity(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long userId,
                                        @Valid @RequestBody CapacityRequest request) {
        Sprint sprint = editableSprint(id, currentUser.from(jwt));
        User member = sprint.getProject().getMembers().stream().filter(m -> m.getId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That person is not a member of this project."));
        SprintCapacity row = capacity.findBySprintIdAndUserId(sprint.getId(), userId)
                .orElseGet(() -> capacity.save(new SprintCapacity(sprint, member, DEFAULT_HOURS, 0)));
        row.setHoursPerDay(request.hoursPerDay().setScale(1, RoundingMode.HALF_UP));
        row.setDaysOff(request.daysOff());
        live.projectChanged(sprint.getProject());
        return capacityOf(sprint);
    }

    CapacityResponse capacityOf(Sprint sprint) {
        Project project = sprint.getProject();
        boolean assumed = sprint.getStartDate() == null || sprint.getEndDate() == null;
        LocalDate start = sprint.getStartDate() != null ? sprint.getStartDate() : nextMonday(LocalDate.now());
        LocalDate end = sprint.getEndDate() != null ? sprint.getEndDate() : start.plusDays(13);
        int workingDays = workingDays(start, end, null, null);
        Map<Long, SprintCapacity> settings = new HashMap<>();
        capacity.findBySprintId(sprint.getId()).forEach(c -> settings.put(c.getUser().getId(), c));

        List<Task> sprintTasks = tasks.findBySprintId(sprint.getId());
        Map<Long, Integer> spent = new HashMap<>();
        List<Long> ids = sprintTasks.stream().map(Task::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] row : time.totalsFor(ids)) {
                spent.put((Long) row[0], ((Number) row[1]).intValue());
            }
        }
        List<PersonCapacity> people = new ArrayList<>();
        BigDecimal totalAvailable = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;
        for (User member : project.getMembers()) {
            if (project.isViewer(member)) {
                continue;
            }
            SprintCapacity setting = settings.get(member.getId());
            BigDecimal hours = setting == null ? DEFAULT_HOURS : setting.getHoursPerDay();
            int daysOff = setting == null ? 0 : setting.getDaysOff();
            int away = workingDays - workingDays(start, end, member.getAwayFrom(), member.getAwayUntil());
            int available = Math.max(0, workingDays - away - daysOff);
            BigDecimal availableHours = hours.multiply(BigDecimal.valueOf(available)).setScale(1, RoundingMode.HALF_UP);
            int remainingMinutes = 0;
            int count = 0;
            int unestimated = 0;
            int points = 0;
            for (Task task : sprintTasks) {
                if (!task.isAssignee(member) || task.getStatus() == TaskStatus.DONE) {
                    continue;
                }
                count++;
                points += task.getStoryPoints() == null ? 0 : task.getStoryPoints();
                if (task.getEstimateMinutes() == null) {
                    unestimated++;
                } else {
                    remainingMinutes += Math.max(0, task.getEstimateMinutes() - spent.getOrDefault(task.getId(), 0));
                }
            }
            BigDecimal remaining = BigDecimal.valueOf(remainingMinutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
            people.add(new PersonCapacity(UserSummary.of(member), workingDays, away, daysOff, hours, availableHours,
                    remaining, count, unestimated, points, remaining.compareTo(availableHours) > 0));
            totalAvailable = totalAvailable.add(availableHours);
            totalRemaining = totalRemaining.add(remaining);
        }
        return new CapacityResponse(start, end, assumed, workingDays, people, totalAvailable, totalRemaining);
    }

    /** Monday–Friday days in [start, end], excluding [awayFrom, awayUntil] when given. */
    static int workingDays(LocalDate start, LocalDate end, LocalDate awayFrom, LocalDate awayUntil) {
        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean away = awayFrom != null && awayUntil != null && !d.isBefore(awayFrom) && !d.isAfter(awayUntil);
            if (!weekend && !away) {
                days++;
            }
        }
        return days;
    }

    private static LocalDate nextMonday(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private Sprint memberSprint(Long id, User user) {
        Sprint sprint = sprints.findById(id).orElseThrow(() -> ApiException.notFound("Sprint not found."));
        access.requireMember(sprint.getProject(), user);
        return sprint;
    }

    private Sprint editableSprint(Long id, User user) {
        Sprint sprint = memberSprint(id, user);
        access.require(sprint.getProject(), user, com.fakejira.project.Permission.MANAGE_SPRINTS);
        return sprint;
    }

    @EventListener
    public void onSprintDeleting(SprintDeleting event) {
        goals.deleteForSprint(event.sprintId());
        capacity.deleteForSprint(event.sprintId());
    }

    @EventListener
    public void onMemberRemoved(com.fakejira.project.MemberRemoved event) {
        capacity.deleteForMember(event.projectId(), event.userId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        goals.deleteForProject(event.projectId());
        capacity.deleteForProject(event.projectId());
    }
}
