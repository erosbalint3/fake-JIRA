package com.fakejira.epic;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class EpicController {

    /** Colors cycle through the 8-slot categorical palette in creation order. */
    static final int PALETTE_SIZE = 8;

    private final EpicRepository epics;
    private final TaskRepository tasks;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskSupport taskSupport;
    private final LiveEvents live;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public EpicController(EpicRepository epics, TaskRepository tasks, ProjectAccess access, CurrentUser currentUser,
                          TaskSupport taskSupport, LiveEvents live) {
        this.epics = epics;
        this.tasks = tasks;
        this.access = access;
        this.currentUser = currentUser;
        this.taskSupport = taskSupport;
        this.live = live;
    }

    public record EpicRequest(
            @NotBlank(message = "Name is required") @Size(max = 80, message = "Name must be at most 80 characters") String name,
            @Size(max = 1000, message = "Description must be at most 1000 characters") String description,
            LocalDate startDate,
            LocalDate dueDate) {
    }

    public record EpicResponse(Long id, String name, String description, int colorIndex, LocalDate startDate,
                               LocalDate dueDate, int taskCount, int doneCount, int points, int donePoints,
                               List<Long> dependsOn, String icon) {
    }

    public record IconRequest(String icon) {
    }

    public record DependenciesRequest(List<Long> dependsOn) {
    }

    @GetMapping("/api/projects/{key}/epics")
    @Transactional(readOnly = true)
    public List<EpicResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        List<Epic> list = epics.findByProjectIdOrderByCreatedAtAsc(project.getId());
        Map<Long, int[]> stats = new HashMap<>();
        for (Task task : tasks.findByProjectId(project.getId())) {
            if (task.getEpic() == null) {
                continue;
            }
            int[] s = stats.computeIfAbsent(task.getEpic().getId(), id -> new int[4]);
            int points = task.getStoryPoints() == null ? 0 : task.getStoryPoints();
            s[0]++;
            s[2] += points;
            if (task.getStatus() == TaskStatus.DONE) {
                s[1]++;
                s[3] += points;
            }
        }
        return list.stream().map(epic -> response(epic, stats.getOrDefault(epic.getId(), new int[4]))).toList();
    }

    @PostMapping("/api/projects/{key}/epics")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public EpicResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                               @Valid @RequestBody EpicRequest request) {
        User creator = currentUser.from(jwt);
        Project project = access.editorProject(key, creator);
        access.require(project, creator, com.fakejira.project.Permission.MANAGE_EPICS);
        validate(request);
        int color = (int) (epics.countByProjectId(project.getId()) % PALETTE_SIZE);
        Epic epic = epics.save(new Epic(project, request.name().trim(), trim(request.description()), color,
                request.startDate(), request.dueDate()));
        live.projectChanged(project);
        return response(epic, new int[4]);
    }

    @PutMapping("/api/epics/{id}")
    @Transactional
    public EpicResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody EpicRequest request) {
        User user = currentUser.from(jwt);
        Epic epic = editable(id, user);
        validate(request);
        epic.setName(request.name().trim());
        epic.setDescription(trim(request.description()));
        epic.setStartDate(request.startDate());
        epic.setDueDate(request.dueDate());
        live.projectChanged(epic.getProject());
        return response(epic, new int[4]);
    }

    /** Replaces the epics this one depends on; rejects cycles. */
    @PutMapping("/api/epics/{id}/dependencies")
    @Transactional
    public EpicResponse dependencies(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestBody DependenciesRequest request) {
        User user = currentUser.from(jwt);
        Epic epic = editable(id, user);
        java.util.Set<Epic> next = new java.util.LinkedHashSet<>();
        for (Long otherId : request.dependsOn() == null ? List.<Long>of() : request.dependsOn()) {
            Epic other = epics.findById(otherId)
                    .filter(e -> e.getProject().getId().equals(epic.getProject().getId()))
                    .orElseThrow(() -> ApiException.badRequest("Dependencies must be epics of the same project."));
            if (other.getId().equals(epic.getId()) || reaches(other, epic, new java.util.HashSet<>())) {
                throw ApiException.badRequest(other.getName() + " already depends on " + epic.getName() + ".");
            }
            next.add(other);
        }
        epic.getDependsOn().clear();
        epic.getDependsOn().addAll(next);
        live.projectChanged(epic.getProject());
        return response(epic, new int[4]);
    }

    /** True when {@code from} depends (directly or not) on {@code target}. */
    private static boolean reaches(Epic from, Epic target, java.util.Set<Long> seen) {
        if (!seen.add(from.getId())) {
            return false;
        }
        for (Epic next : from.getDependsOn()) {
            if (next.getId().equals(target.getId()) || reaches(next, target, seen)) {
                return true;
            }
        }
        return false;
    }

    /** Deleting an epic keeps its tasks; they just no longer belong to an epic. */
    @DeleteMapping("/api/epics/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Epic epic = editable(id, user);
        for (Task task : tasks.findByEpicId(epic.getId())) {
            task.setEpic(null);
            taskSupport.record(task, user, "removed the task from epic " + epic.getName() + " (epic deleted)");
        }
        for (Epic other : epics.findByProjectIdOrderByCreatedAtAsc(epic.getProject().getId())) {
            other.getDependsOn().removeIf(e -> e.getId().equals(epic.getId()));
        }
        epic.getDependsOn().clear();
        jdbc.update("delete from key_result_epics where epic_id = ?", epic.getId());
        live.projectChanged(epic.getProject());
        epics.delete(epic);
    }

    /** Sets or clears (empty) the epic's emoji. */
    @org.springframework.web.bind.annotation.PutMapping("/api/epics/{id}/icon")
    @Transactional
    public EpicResponse setIcon(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @RequestBody IconRequest request) {
        User user = currentUser.from(jwt);
        Epic epic = editable(id, user);
        epic.setIcon(com.fakejira.project.Icons.clean(request.icon()));
        live.projectChanged(epic.getProject());
        return response(epic, new int[4]);
    }

    private Epic editable(Long id, User user) {
        Epic epic = epics.findById(id).orElseThrow(() -> ApiException.notFound("Epic not found."));
        access.require(epic.getProject(), user, com.fakejira.project.Permission.MANAGE_EPICS);
        return epic;
    }

    private static void validate(EpicRequest request) {
        if (request.startDate() != null && request.dueDate() != null && request.dueDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("The due date must be on or after the start date.");
        }
    }

    private static EpicResponse response(Epic epic, int[] s) {
        return new EpicResponse(epic.getId(), epic.getName(), epic.getDescription(), epic.getColorIndex(),
                epic.getStartDate(), epic.getDueDate(), s[0], s[1], s[2], s[3],
                epic.getDependsOn().stream().map(Epic::getId).sorted().toList(), epic.getIcon());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
