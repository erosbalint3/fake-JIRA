package com.fakejira.release;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Releases (versions): plan which tasks ship together, mark them released and generate release notes. */
@RestController
public class ReleaseController {

    private final ReleaseRepository releases;
    private final TaskRepository tasks;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskSupport taskSupport;
    private final LiveEvents live;
    private final AuditLog audit;

    public ReleaseController(ReleaseRepository releases, TaskRepository tasks, ProjectAccess access,
                             CurrentUser currentUser, TaskSupport taskSupport, LiveEvents live, AuditLog audit) {
        this.releases = releases;
        this.tasks = tasks;
        this.access = access;
        this.currentUser = currentUser;
        this.taskSupport = taskSupport;
        this.live = live;
        this.audit = audit;
    }

    public record ReleaseRequest(
            @NotBlank(message = "Name is required") @Size(max = 40, message = "Name must be at most 40 characters") String name,
            @Size(max = 2000, message = "Description must be at most 2000 characters") String description,
            LocalDate releaseDate) {
    }

    public record ReleaseResponse(Long id, String name, String description, LocalDate releaseDate, boolean released,
                                  Instant releasedAt, int taskCount, int doneCount, int points, int donePoints) {
    }

    /** {@code moveUnfinishedTo}: another release for the tasks that are not done; null leaves them unassigned. */
    public record ShipRequest(Long moveUnfinishedTo) {
    }

    public record TaskReleaseRequest(Long releaseId) {
    }

    public record ReleaseNotes(ReleaseResponse release, String markdown) {
    }

    @GetMapping("/api/projects/{key}/releases")
    @Transactional(readOnly = true)
    public List<ReleaseResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        Map<Long, int[]> stats = new HashMap<>();
        for (Task task : tasks.findByProjectId(project.getId())) {
            if (task.getRelease() != null) {
                count(stats.computeIfAbsent(task.getRelease().getId(), id -> new int[4]), task);
            }
        }
        return releases.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                .map(r -> response(r, stats.getOrDefault(r.getId(), new int[4]))).toList();
    }

    @PostMapping("/api/projects/{key}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ReleaseResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                  @Valid @RequestBody ReleaseRequest request) {
        User creator = currentUser.from(jwt);
        Project project = access.editorProject(key, creator);
        access.require(project, creator, com.fakejira.project.Permission.MANAGE_RELEASES);
        String name = request.name().trim();
        if (releases.existsByProjectIdAndNameIgnoreCase(project.getId(), name)) {
            throw ApiException.conflict("A release called " + name + " already exists.");
        }
        Release release = releases.save(new Release(project, name, trim(request.description()), request.releaseDate()));
        live.projectChanged(project);
        return response(release, new int[4]);
    }

    @GetMapping("/api/releases/{id}/tasks")
    @Transactional(readOnly = true)
    public List<TaskResponse> tasks(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Release release = member(id, currentUser.from(jwt));
        return taskSupport.responses(tasks.findByReleaseId(release.getId()));
    }

    @PutMapping("/api/releases/{id}")
    @Transactional
    public ReleaseResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @Valid @RequestBody ReleaseRequest request) {
        Release release = editable(id, currentUser.from(jwt));
        String name = request.name().trim();
        if (!name.equalsIgnoreCase(release.getName())
                && releases.existsByProjectIdAndNameIgnoreCase(release.getProject().getId(), name)) {
            throw ApiException.conflict("A release called " + name + " already exists.");
        }
        release.setName(name);
        release.setDescription(trim(request.description()));
        release.setReleaseDate(request.releaseDate());
        live.projectChanged(release.getProject());
        return stats(release);
    }

    /** Marks the release as shipped; unfinished tasks move to another release or become unassigned. */
    @PostMapping("/api/releases/{id}/release")
    @Transactional
    public ReleaseResponse ship(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                @RequestBody(required = false) ShipRequest request) {
        User user = currentUser.from(jwt);
        Release release = editable(id, user);
        if (release.isReleased()) {
            throw ApiException.badRequest(release.getName() + " is already released.");
        }
        Release target = null;
        if (request != null && request.moveUnfinishedTo() != null) {
            target = editable(request.moveUnfinishedTo(), user);
            if (target.getId().equals(release.getId()) || target.isReleased()
                    || !target.getProject().getId().equals(release.getProject().getId())) {
                throw ApiException.badRequest("Pick another unreleased version of this project.");
            }
        }
        for (Task task : tasks.findByReleaseId(release.getId())) {
            if (task.getStatus() != TaskStatus.DONE) {
                task.setRelease(target);
                taskSupport.record(task, user, target == null
                        ? "removed the task from " + release.getName() + " (released without it)"
                        : "moved the task from " + release.getName() + " to " + target.getName());
            }
        }
        release.markReleased(true);
        if (release.getReleaseDate() == null) {
            release.setReleaseDate(LocalDate.now());
        }
        audit.record(user, "release.ship", release.getProject().getKey(), release.getName());
        live.projectChanged(release.getProject());
        return stats(release);
    }

    @PostMapping("/api/releases/{id}/unrelease")
    @Transactional
    public ReleaseResponse unrelease(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Release release = editable(id, currentUser.from(jwt));
        release.markReleased(false);
        live.projectChanged(release.getProject());
        return stats(release);
    }

    /** Deleting a release keeps its tasks. */
    @DeleteMapping("/api/releases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Release release = editable(id, user);
        for (Task task : tasks.findByReleaseId(release.getId())) {
            task.setRelease(null);
            taskSupport.record(task, user, "removed the task from release " + release.getName() + " (release deleted)");
        }
        live.projectChanged(release.getProject());
        releases.delete(release);
    }

    /** Release notes as Markdown, grouped by task type. */
    @GetMapping("/api/releases/{id}/notes")
    @Transactional(readOnly = true)
    public ReleaseNotes notes(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Release release = member(id, currentUser.from(jwt));
        List<Task> list = tasks.findByReleaseId(release.getId()).stream()
                .filter(t -> t.getParent() == null)
                .sorted(Comparator.comparing(Task::getId)).toList();
        StringBuilder md = new StringBuilder("# ").append(release.getProject().getName()).append(' ')
                .append(release.getName()).append('\n');
        if (release.getReleaseDate() != null) {
            md.append("\n_").append(release.isReleased() ? "Released " : "Planned for ").append(release.getReleaseDate())
                    .append("_\n");
        }
        if (!release.getDescription().isBlank()) {
            md.append('\n').append(release.getDescription()).append('\n');
        }
        section(md, "New features", list, t -> t.getType() == TaskType.STORY);
        section(md, "Improvements", list, t -> t.getType() == TaskType.TASK || t.getType() == TaskType.SPIKE);
        section(md, "Bug fixes", list, t -> t.getType() == TaskType.BUG);
        List<Task> open = list.stream().filter(t -> t.getStatus() != TaskStatus.DONE).toList();
        if (!open.isEmpty()) {
            md.append("\n## Not finished yet\n\n");
            open.forEach(t -> md.append("- ").append(t.getKey()).append(' ').append(t.getTitle()).append('\n'));
        }
        return new ReleaseNotes(stats(release), md.toString());
    }

    @PutMapping("/api/tasks/{id}/release")
    @Transactional
    public TaskResponse setTaskRelease(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                       @RequestBody TaskReleaseRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        Release before = task.getRelease();
        Release after = null;
        if (request.releaseId() != null) {
            after = releases.findById(request.releaseId())
                    .filter(r -> r.getProject().getId().equals(task.getProject().getId()))
                    .orElseThrow(() -> ApiException.badRequest("That release does not belong to this project."));
        }
        if (before == null ? after == null : after != null && before.getId().equals(after.getId())) {
            return taskSupport.response(task);
        }
        task.setRelease(after);
        taskSupport.record(task, user, after == null ? "removed the task from release " + before.getName()
                : "set the release to " + after.getName());
        live.taskChanged(task);
        return taskSupport.response(task);
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        releases.deleteAll(releases.findByProjectIdOrderByCreatedAtAsc(event.projectId()));
    }

    private static void section(StringBuilder md, String title, List<Task> list,
                                java.util.function.Predicate<Task> filter) {
        List<Task> done = list.stream().filter(t -> t.getStatus() == TaskStatus.DONE).filter(filter).toList();
        if (done.isEmpty()) {
            return;
        }
        md.append("\n## ").append(title).append("\n\n");
        done.forEach(t -> md.append("- ").append(t.getTitle()).append(" (").append(t.getKey()).append(")\n"));
    }

    private ReleaseResponse stats(Release release) {
        int[] s = new int[4];
        tasks.findByReleaseId(release.getId()).forEach(t -> count(s, t));
        return response(release, s);
    }

    private static void count(int[] s, Task task) {
        int points = task.getStoryPoints() == null ? 0 : task.getStoryPoints();
        s[0]++;
        s[2] += points;
        if (task.getStatus() == TaskStatus.DONE) {
            s[1]++;
            s[3] += points;
        }
    }

    private Release member(Long id, User user) {
        Release release = releases.findById(id).orElseThrow(() -> ApiException.notFound("Release not found."));
        access.requireMember(release.getProject(), user);
        return release;
    }

    private Release editable(Long id, User user) {
        Release release = releases.findById(id).orElseThrow(() -> ApiException.notFound("Release not found."));
        access.require(release.getProject(), user, com.fakejira.project.Permission.MANAGE_RELEASES);
        return release;
    }

    private static ReleaseResponse response(Release r, int[] s) {
        return new ReleaseResponse(r.getId(), r.getName(), r.getDescription(), r.getReleaseDate(), r.isReleased(),
                r.getReleasedAt(), s[0], s[1], s[2], s[3]);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
