package com.fakejira.sprint;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.sprint.SprintDtos.Burndown;
import com.fakejira.sprint.SprintDtos.BurndownPoint;
import com.fakejira.sprint.SprintDtos.SprintRequest;
import com.fakejira.sprint.SprintDtos.SprintResponse;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class SprintService {

    static final int DEFAULT_LENGTH_DAYS = 14;

    private final SprintRepository sprints;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final ProjectAccess access;
    private final LiveEvents live;
    private final Clock clock;

    public SprintService(SprintRepository sprints, TaskRepository tasks, TaskService taskService,
                         ProjectAccess access, LiveEvents live) {
        this.sprints = sprints;
        this.tasks = tasks;
        this.taskService = taskService;
        this.access = access;
        this.live = live;
        this.clock = Clock.systemDefaultZone();
    }

    @Transactional(readOnly = true)
    public List<SprintResponse> list(User user, String projectKey) {
        Project project = access.memberProject(projectKey, user);
        return sprints.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream().map(SprintResponse::of).toList();
    }

    public SprintResponse create(User user, String projectKey, SprintRequest request) {
        Project project = access.memberProject(projectKey, user);
        String name = blank(request.name())
                ? project.getKey() + " Sprint " + (sprints.countByProjectId(project.getId()) + 1)
                : request.name().trim();
        validateDates(request.startDate(), request.endDate());
        Sprint sprint = sprints.save(new Sprint(project, name, trim(request.goal()), request.startDate(), request.endDate()));
        live.projectChanged(project);
        return SprintResponse.of(sprint);
    }

    public SprintResponse update(User user, Long id, SprintRequest request) {
        Sprint sprint = memberSprint(id, user);
        if (sprint.getState() == SprintState.COMPLETED) {
            throw ApiException.badRequest("Completed sprints cannot be changed.");
        }
        if (!blank(request.name())) {
            sprint.setName(request.name().trim());
        }
        sprint.setGoal(trim(request.goal()));
        validateDates(request.startDate(), request.endDate());
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
        live.projectChanged(sprint.getProject());
        return SprintResponse.of(sprint);
    }

    public SprintResponse start(User user, Long id, SprintRequest request) {
        Sprint sprint = memberSprint(id, user);
        if (sprint.getState() != SprintState.PLANNED) {
            throw ApiException.badRequest("Only planned sprints can be started.");
        }
        sprints.findFirstByProjectIdAndState(sprint.getProject().getId(), SprintState.ACTIVE).ifPresent(active -> {
            throw ApiException.conflict(active.getName() + " is still active. Complete it first.");
        });
        LocalDate start = firstNonNull(request == null ? null : request.startDate(), sprint.getStartDate(), today());
        LocalDate end = firstNonNull(request == null ? null : request.endDate(), sprint.getEndDate(),
                start.plusDays(DEFAULT_LENGTH_DAYS));
        validateDates(start, end);
        sprint.setStartDate(start);
        sprint.setEndDate(end);
        sprint.setState(SprintState.ACTIVE);
        live.projectChanged(sprint.getProject());
        return SprintResponse.of(sprint);
    }

    /** Finishes the sprint; unfinished tasks go back to the backlog. */
    public SprintResponse complete(User user, Long id) {
        Sprint sprint = memberSprint(id, user);
        if (sprint.getState() != SprintState.ACTIVE) {
            throw ApiException.badRequest("Only the active sprint can be completed.");
        }
        int carried = 0;
        for (Task task : tasks.findBySprintId(sprint.getId())) {
            if (task.getStatus() != TaskStatus.DONE) {
                task.setSprint(null);
                taskService.record(task, user, "moved the task to the backlog when " + sprint.getName() + " was completed");
                carried++;
            }
        }
        sprint.setCarriedOver(carried);
        sprint.setState(SprintState.COMPLETED);
        sprint.setCompletedAt(Instant.now());
        live.projectChanged(sprint.getProject());
        return SprintResponse.of(sprint);
    }

    /** Deletes a sprint that has not started; its tasks go back to the backlog. */
    public void delete(User user, Long id) {
        Sprint sprint = memberSprint(id, user);
        if (sprint.getState() != SprintState.PLANNED) {
            throw ApiException.badRequest("Only planned sprints can be deleted.");
        }
        tasks.findBySprintId(sprint.getId()).forEach(task -> task.setSprint(null));
        live.projectChanged(sprint.getProject());
        sprints.delete(sprint);
    }

    /**
     * Remaining (not done) task count at the end of each sprint day, plus the ideal straight line.
     * Tasks carried over to the backlog at completion count as never done.
     */
    @Transactional(readOnly = true)
    public Burndown burndown(User user, Long id) {
        Sprint sprint = memberSprint(id, user);
        if (sprint.getState() == SprintState.PLANNED || sprint.getStartDate() == null || sprint.getEndDate() == null) {
            throw ApiException.badRequest("The burndown is available once the sprint has started.");
        }
        ZoneId zone = clock.getZone();
        List<Task> sprintTasks = tasks.findBySprintId(sprint.getId());
        int total = sprintTasks.size() + sprint.getCarriedOver();
        int done = (int) sprintTasks.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();

        LocalDate start = sprint.getStartDate();
        LocalDate end = sprint.getEndDate();
        if (sprint.getCompletedAt() != null) {
            LocalDate completed = sprint.getCompletedAt().atZone(zone).toLocalDate();
            if (completed.isAfter(end)) {
                end = completed;
            }
        }
        LocalDate lastActual = sprint.getCompletedAt() != null
                ? sprint.getCompletedAt().atZone(zone).toLocalDate()
                : today();
        long days = Math.max(1, ChronoUnit.DAYS.between(start, end));

        List<BurndownPoint> points = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Instant endOfDay = day.plusDays(1).atStartOfDay(zone).toInstant();
            Integer remaining = null;
            if (!day.isAfter(lastActual)) {
                long finished = sprintTasks.stream()
                        .filter(task -> task.getStatus() == TaskStatus.DONE && task.getCompletedAt() != null
                                && task.getCompletedAt().isBefore(endOfDay))
                        .count();
                remaining = total - (int) finished;
            }
            double ideal = total * (1.0 - (double) ChronoUnit.DAYS.between(start, day) / days);
            points.add(new BurndownPoint(day, remaining, Math.max(0, Math.round(ideal * 100) / 100.0)));
        }
        return new Burndown(SprintResponse.of(sprint), total, done, points);
    }

    private Sprint memberSprint(Long id, User user) {
        Sprint sprint = sprints.findById(id).orElseThrow(() -> ApiException.notFound("Sprint not found."));
        access.requireMember(sprint.getProject(), user);
        return sprint;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("The end date must be on or after the start date.");
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
