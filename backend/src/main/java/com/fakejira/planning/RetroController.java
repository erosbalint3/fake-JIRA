package com.fakejira.planning;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintChange;
import com.fakejira.sprint.SprintChangeRepository;
import com.fakejira.sprint.SprintDtos.SprintResponse;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.sprint.SprintState;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sprint retrospectives (three-column board with votes) and the sprint review summary. */
@RestController
public class RetroController {

    private final RetroItemRepository items;
    private final SprintRepository sprints;
    private final SprintChangeRepository sprintChanges;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fakejira.sprint.SprintGoalRepository sprintGoals;

    public RetroController(RetroItemRepository items, SprintRepository sprints, SprintChangeRepository sprintChanges,
                           TaskRepository tasks, TaskService taskService, ProjectAccess access,
                           CurrentUser currentUser, LiveEvents live) {
        this.items = items;
        this.sprints = sprints;
        this.sprintChanges = sprintChanges;
        this.tasks = tasks;
        this.taskService = taskService;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record RetroRequest(@NotNull(message = "Pick a column") RetroItem.Kind kind,
                               @NotBlank(message = "Write something") @Size(max = 500, message = "At most 500 characters") String text) {
    }

    public record RetroItemResponse(Long id, RetroItem.Kind kind, String text, UserSummary author, int votes,
                                    boolean voted, boolean mine, Long taskId, String taskKey, Instant createdAt) {
    }

    public record ReviewTask(Long id, String key, String title, TaskType type, TaskStatus status, Integer points,
                             UserSummary assignee) {
    }

    public record PersonTotal(UserSummary user, int tasks, int points) {
    }

    public record SprintReview(SprintResponse sprint, int committedPoints, int completedPoints, int completedTasks,
                               List<ReviewTask> completed, List<ReviewTask> unfinished, List<ReviewTask> added,
                               List<ReviewTask> removed, List<PersonTotal> people, String markdown,
                               List<GoalItem> goals, int goalsMet) {
    }

    public record GoalItem(Long id, String text, boolean done) {
    }

    @GetMapping("/api/sprints/{id}/retro")
    @Transactional(readOnly = true)
    public List<RetroItemResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Sprint sprint = memberSprint(id, user);
        return items.findBySprintIdOrderByCreatedAtAsc(sprint.getId()).stream()
                .sorted(Comparator.comparingInt((RetroItem i) -> -i.getVotes().size())
                        .thenComparing(RetroItem::getCreatedAt))
                .map(item -> response(item, user)).toList();
    }

    @PostMapping("/api/sprints/{id}/retro")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RetroItemResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                 @Valid @RequestBody RetroRequest request) {
        User user = currentUser.from(jwt);
        Sprint sprint = memberSprint(id, user);
        access.requireEditor(sprint.getProject(), user);
        if (sprint.getState() == SprintState.PLANNED) {
            throw ApiException.badRequest("The retrospective opens once the sprint has started.");
        }
        RetroItem item = items.save(new RetroItem(sprint, user, request.kind(), request.text().trim()));
        live.projectChanged(sprint.getProject());
        return response(item, user);
    }

    @PutMapping("/api/retro/{id}")
    @Transactional
    public RetroItemResponse edit(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @Valid @RequestBody RetroRequest request) {
        User user = currentUser.from(jwt);
        RetroItem item = own(id, user);
        item.setText(request.text().trim());
        item.setKind(request.kind());
        live.projectChanged(item.getSprint().getProject());
        return response(item, user);
    }

    @DeleteMapping("/api/retro/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        RetroItem item = own(id, user);
        live.projectChanged(item.getSprint().getProject());
        items.delete(item);
    }

    /** Toggles the caller's vote. */
    @PostMapping("/api/retro/{id}/vote")
    @Transactional
    public RetroItemResponse vote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        RetroItem item = item(id);
        access.requireEditor(item.getSprint().getProject(), user);
        if (!item.getVotes().remove(user.getId())) {
            item.getVotes().add(user.getId());
        }
        live.projectChanged(item.getSprint().getProject());
        return response(item, user);
    }

    /** Turns an action item into a backlog task. */
    @PostMapping("/api/retro/{id}/task")
    @Transactional
    public RetroItemResponse toTask(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        RetroItem item = item(id);
        access.requireEditor(item.getSprint().getProject(), user);
        if (item.getTaskId() != null) {
            throw ApiException.conflict("This item already has task " + item.getTaskKey() + ".");
        }
        String title = item.getText().length() > 120 ? item.getText().substring(0, 117) + "…" : item.getText();
        TaskResponse task = taskService.create(user, CreateTaskRequest.of(item.getSprint().getProject().getKey(), title,
                "Action item from the " + item.getSprint().getName() + " retrospective.\n\n" + item.getText(),
                TaskPriority.MEDIUM, TaskType.TASK).withLabels(List.of("retro")));
        item.linkTask(task.id(), task.key());
        return response(item, user);
    }

    @GetMapping("/api/sprints/{id}/review")
    @Transactional(readOnly = true)
    public SprintReview review(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Sprint sprint = memberSprint(id, currentUser.from(jwt));
        List<Task> current = tasks.findBySprintId(sprint.getId()).stream().filter(t -> t.getParent() == null).toList();
        List<ReviewTask> completed = current.stream().filter(t -> t.getStatus() == TaskStatus.DONE).map(RetroController::task).toList();
        List<ReviewTask> unfinished = new ArrayList<>(current.stream().filter(t -> t.getStatus() != TaskStatus.DONE)
                .map(RetroController::task).toList());
        List<ReviewTask> added = new ArrayList<>();
        List<ReviewTask> removed = new ArrayList<>();
        Set<Long> addedIds = new HashSet<>();
        int addedPoints = 0;
        int removedPoints = 0;
        for (SprintChange change : sprintChanges.findBySprintIdOrderByChangedAtAsc(sprint.getId())) {
            Task task = tasks.findById(change.getTaskId()).orElse(null);
            ReviewTask entry = task != null ? task(task) : new ReviewTask(change.getTaskId(), change.getTaskKey(),
                    change.getTaskTitle(), null, null, change.getPoints(), null);
            int points = change.getPoints() == null ? 0 : change.getPoints();
            if (change.isCarried()) {
                unfinished.add(entry);
            } else if (change.isAdded()) {
                if (addedIds.add(change.getTaskId())) {
                    added.add(entry);
                    addedPoints += points;
                }
            } else {
                removed.add(entry);
                removedPoints += points;
            }
        }
        int completedPoints = completed.stream().mapToInt(t -> t.points() == null ? 0 : t.points()).sum();
        int unfinishedPoints = unfinished.stream().mapToInt(t -> t.points() == null ? 0 : t.points()).sum();
        int committedPoints = Math.max(0, completedPoints + unfinishedPoints - addedPoints + removedPoints);

        Map<Long, int[]> byPerson = new LinkedHashMap<>();
        Map<Long, UserSummary> people = new LinkedHashMap<>();
        for (Task task : current) {
            if (task.getStatus() == TaskStatus.DONE && task.getAssignee() != null) {
                people.putIfAbsent(task.getAssignee().getId(), UserSummary.of(task.getAssignee()));
                int[] s = byPerson.computeIfAbsent(task.getAssignee().getId(), k -> new int[2]);
                s[0]++;
                s[1] += task.getStoryPoints() == null ? 0 : task.getStoryPoints();
            }
        }
        List<PersonTotal> totals = byPerson.entrySet().stream()
                .map(e -> new PersonTotal(people.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(PersonTotal::points).reversed()).toList();

        StringBuilder md = new StringBuilder("# ").append(sprint.getName()).append(" review\n");
        if (sprint.getGoal() != null && !sprint.getGoal().isBlank()) {
            md.append("\n**Goal:** ").append(sprint.getGoal()).append('\n');
        }
        List<GoalItem> goalItems = sprintGoals.findBySprintIdOrderByPositionAscIdAsc(sprint.getId()).stream()
                .map(g -> new GoalItem(g.getId(), g.getText(), g.isDone())).toList();
        int goalsMet = (int) goalItems.stream().filter(GoalItem::done).count();
        if (!goalItems.isEmpty()) {
            md.append("\n**Goals met:** ").append(goalsMet).append(" of ").append(goalItems.size()).append('\n');
            goalItems.forEach(g -> md.append("- [").append(g.done() ? 'x' : ' ').append("] ").append(g.text()).append('\n'));
        }
        if (sprint.getStartDate() != null) {
            md.append("\n").append(sprint.getStartDate()).append(" → ").append(sprint.getEndDate()).append('\n');
        }
        md.append("\nCompleted ").append(completed.size()).append(" task").append(completed.size() == 1 ? "" : "s")
                .append(" (").append(completedPoints).append(" of ").append(committedPoints).append(" committed points).\n");
        list(md, "Completed", completed);
        list(md, "Not finished", unfinished);
        list(md, "Added during the sprint", added);
        list(md, "Removed during the sprint", removed);
        return new SprintReview(com.fakejira.sprint.SprintDtos.SprintResponse.of(sprint), committedPoints,
                completedPoints, completed.size(), completed, unfinished, added, removed, totals, md.toString(),
                goalItems, goalsMet);
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        items.deleteAll(items.findBySprintProjectId(event.projectId()));
    }

    private static void list(StringBuilder md, String title, List<ReviewTask> list) {
        if (list.isEmpty()) {
            return;
        }
        md.append("\n## ").append(title).append("\n\n");
        for (ReviewTask t : list) {
            md.append("- ").append(t.key()).append(' ').append(t.title());
            if (t.points() != null) {
                md.append(" · ").append(t.points()).append(" pt");
            }
            md.append('\n');
        }
    }

    private static ReviewTask task(Task t) {
        return new ReviewTask(t.getId(), t.getKey(), t.getTitle(), t.getType(), t.getStatus(), t.getStoryPoints(),
                t.getAssignee() == null ? null : UserSummary.of(t.getAssignee()));
    }

    private Sprint memberSprint(Long id, User user) {
        Sprint sprint = sprints.findById(id).orElseThrow(() -> ApiException.notFound("Sprint not found."));
        access.requireMember(sprint.getProject(), user);
        return sprint;
    }

    private RetroItem item(Long id) {
        return items.findById(id).orElseThrow(() -> ApiException.notFound("Retro item not found."));
    }

    /** The author, or the project owner, may edit or remove an item. */
    private RetroItem own(Long id, User user) {
        RetroItem item = item(id);
        access.requireEditor(item.getSprint().getProject(), user);
        if (!item.getAuthor().getId().equals(user.getId()) && !item.getSprint().getProject().isOwner(user)) {
            throw ApiException.forbidden("Only the author can change this item.");
        }
        return item;
    }

    private static RetroItemResponse response(RetroItem item, User user) {
        return new RetroItemResponse(item.getId(), item.getKind(), item.getText(), UserSummary.of(item.getAuthor()),
                item.getVotes().size(), item.getVotes().contains(user.getId()),
                item.getAuthor().getId().equals(user.getId()), item.getTaskId(), item.getTaskKey(), item.getCreatedAt());
    }
}
