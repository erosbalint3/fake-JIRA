package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectRepository;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.sprint.SprintState;
import com.fakejira.task.TaskDtos.ActivityResponse;
import com.fakejira.task.TaskDtos.ChecklistItemResponse;
import com.fakejira.task.TaskDtos.ChecklistUpdateRequest;
import com.fakejira.task.TaskDtos.CommentResponse;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskDtos.UpdateTaskRequest;
import com.fakejira.user.User;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
@Transactional
public class TaskService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final SprintRepository sprints;
    private final CommentRepository comments;
    private final ChecklistItemRepository checklist;
    private final TaskActivityRepository activity;
    private final TaskCleanup cleanup;
    private final NotificationService notifications;
    private final LiveEvents live;

    public TaskService(TaskRepository tasks, ProjectRepository projects, ProjectAccess access,
                       SprintRepository sprints, CommentRepository comments, ChecklistItemRepository checklist,
                       TaskActivityRepository activity, TaskCleanup cleanup, NotificationService notifications,
                       LiveEvents live) {
        this.tasks = tasks;
        this.projects = projects;
        this.access = access;
        this.sprints = sprints;
        this.comments = comments;
        this.checklist = checklist;
        this.activity = activity;
        this.cleanup = cleanup;
        this.notifications = notifications;
        this.live = live;
    }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<TaskResponse> search(User user, TaskFilter filter) {
        if (filter.project() != null && !filter.project().isBlank()) {
            access.memberProject(filter.project(), user);
        }
        List<Task> found = tasks.findAll(TaskSpecifications.visibleTo(user, filter), Sort.by(Sort.Direction.DESC, "updatedAt"));
        return responses(found);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(User user, Long id) {
        return response(memberTask(id, user));
    }

    // ---------------------------------------------------------------- writing

    public TaskResponse create(User user, CreateTaskRequest request) {
        Project project = access.memberProject(request.projectKey(), user);
        project = projects.lockById(project.getId()).orElseThrow();
        Task task = new Task(project, project.allocateNumber(), request.title().trim(),
                normalize(request.description()), request.priority(), user);
        task.setDueDate(request.dueDate());
        task.getLabels().addAll(normalizeLabels(request.labels()));
        if (request.sprintId() != null) {
            task.setSprint(openSprint(project, request.sprintId()));
        }
        if (request.assigneeId() != null) {
            task.setAssignee(member(project, request.assigneeId()));
        }
        tasks.save(task);
        record(task, user, "created the task");
        if (task.getAssignee() != null) {
            notifications.notify(task.getAssignee(), user, task, "assigned you to");
        }
        live.taskChanged(task);
        return response(task);
    }

    public TaskResponse update(User user, Long id, UpdateTaskRequest request) {
        Task task = memberTask(id, user);
        List<String> changes = new ArrayList<>();
        String title = request.title().trim();
        if (!title.equals(task.getTitle())) {
            changes.add("renamed the task to “" + title + "”");
            task.setTitle(title);
        }
        String description = normalize(request.description());
        if (!description.equals(task.getDescription())) {
            changes.add("updated the description");
            task.setDescription(description);
        }
        if (request.priority() != task.getPriority()) {
            changes.add("changed priority from " + task.getPriority().label() + " to " + request.priority().label());
            task.setPriority(request.priority());
        }
        if (!Objects.equals(request.dueDate(), task.getDueDate())) {
            changes.add(request.dueDate() == null
                    ? "removed the due date"
                    : "set the due date to " + DATE.format(request.dueDate()));
            task.setDueDate(request.dueDate());
        }
        Set<String> labels = normalizeLabels(request.labels());
        if (!labels.equals(task.getLabels())) {
            changes.add(labels.isEmpty() ? "removed all labels" : "set labels to " + String.join(", ", labels));
            task.getLabels().clear();
            task.getLabels().addAll(labels);
        }
        if (!changes.isEmpty()) {
            changes.forEach(change -> record(task, user, change));
            notifyParticipants(task, user, "updated");
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return response(task);
    }

    public TaskResponse changeStatus(User user, Long id, TaskStatus status) {
        Task task = memberTask(id, user);
        if (task.getStatus() != status) {
            record(task, user, "changed status from " + task.getStatus().label() + " to " + status.label());
            task.setStatus(status);
            notifyParticipants(task, user, "moved to " + status.label().toLowerCase(Locale.ROOT));
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return response(task);
    }

    public TaskResponse assign(User user, Long id, Long assigneeId) {
        Task task = memberTask(id, user);
        User previous = task.getAssignee();
        User next = assigneeId == null ? null : member(task.getProject(), assigneeId);
        if (Objects.equals(previous == null ? null : previous.getId(), next == null ? null : next.getId())) {
            return response(task);
        }
        task.setAssignee(next);
        if (next == null) {
            record(task, user, "unassigned " + previous.getUsername());
            notifications.notify(previous, user, task, "unassigned you from");
        } else {
            record(task, user, next.getId().equals(user.getId())
                    ? "assigned the task to themselves"
                    : "assigned the task to " + next.getUsername());
            notifications.notify(next, user, task, "assigned you to");
            if (previous != null) {
                notifications.notify(previous, user, task, "reassigned");
            }
        }
        tasks.saveAndFlush(task);
        live.taskChanged(task);
        return response(task);
    }

    public TaskResponse accept(User user, Long id) {
        Task task = memberTask(id, user);
        if (task.getAssignee() != null) {
            throw ApiException.conflict(task.isAssignee(user)
                    ? "You already own this task."
                    : "This task was already taken by " + task.getAssignee().getUsername() + ".");
        }
        task.setAssignee(user);
        record(task, user, "accepted the task");
        notifications.notify(task.getReporter(), user, task, "accepted");
        tasks.saveAndFlush(task);
        live.taskChanged(task);
        return response(task);
    }

    public TaskResponse release(User user, Long id) {
        Task task = memberTask(id, user);
        if (!task.isAssignee(user)) {
            throw ApiException.forbidden("Only the assignee can release this task.");
        }
        task.setAssignee(null);
        task.setStatus(TaskStatus.TODO);
        record(task, user, "released the task");
        notifications.notify(task.getReporter(), user, task, "released");
        tasks.saveAndFlush(task);
        live.taskChanged(task);
        return response(task);
    }

    public TaskResponse moveToSprint(User user, Long id, Long sprintId) {
        Task task = memberTask(id, user);
        Sprint target = sprintId == null ? null : openSprint(task.getProject(), sprintId);
        Long current = task.getSprint() == null ? null : task.getSprint().getId();
        if (!Objects.equals(current, sprintId)) {
            task.setSprint(target);
            record(task, user, target == null ? "moved the task to the backlog" : "moved the task to " + target.getName());
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return response(task);
    }

    public void delete(User user, Long id) {
        Task task = memberTask(id, user);
        if (!task.isReporter(user) && !task.getProject().isOwner(user)) {
            throw ApiException.forbidden("Only the reporter or the project owner can delete this task.");
        }
        notifications.notify(task.getAssignee(), user, task, "deleted");
        Project project = task.getProject();
        Long taskId = task.getId();
        cleanup.delete(task);
        live.taskDeleted(project, taskId);
    }

    // ---------------------------------------------------------------- comments

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(User user, Long taskId) {
        memberTask(taskId, user);
        return comments.findForTask(taskId).stream().map(CommentResponse::of).toList();
    }

    public CommentResponse addComment(User user, Long taskId, String body) {
        Task task = memberTask(taskId, user);
        Comment comment = comments.save(new Comment(task, user, body.trim()));

        Map<Long, User> mentioned = new HashMap<>();
        Set<String> names = MentionParser.usernames(comment.getBody());
        for (User member : task.getProject().getMembers()) {
            if (names.contains(member.getUsername().toLowerCase(Locale.ROOT))) {
                mentioned.put(member.getId(), member);
            }
        }
        mentioned.values().forEach(member -> notifications.notify(member, user, task, "mentioned you in"));
        for (User participant : participants(task)) {
            if (!mentioned.containsKey(participant.getId())) {
                notifications.notify(participant, user, task, "commented on");
            }
        }
        live.taskChanged(task);
        return CommentResponse.of(comment);
    }

    // ---------------------------------------------------------------- checklist

    @Transactional(readOnly = true)
    public List<ChecklistItemResponse> checklist(User user, Long taskId) {
        memberTask(taskId, user);
        return checklist.findByTaskIdOrderByPositionAscIdAsc(taskId).stream().map(ChecklistItemResponse::of).toList();
    }

    public ChecklistItemResponse addChecklistItem(User user, Long taskId, String text) {
        Task task = memberTask(taskId, user);
        ChecklistItem item = checklist.save(new ChecklistItem(task, text.trim(), checklist.maxPosition(taskId) + 1));
        live.taskChanged(task);
        return ChecklistItemResponse.of(item);
    }

    public ChecklistItemResponse updateChecklistItem(User user, Long taskId, Long itemId, ChecklistUpdateRequest request) {
        Task task = memberTask(taskId, user);
        ChecklistItem item = checklist.findByIdAndTaskId(itemId, taskId)
                .orElseThrow(() -> ApiException.notFound("Checklist item not found."));
        if (request.text() != null) {
            item.setText(request.text().trim());
        }
        if (request.done() != null) {
            item.setDone(request.done());
        }
        live.taskChanged(task);
        return ChecklistItemResponse.of(item);
    }

    public void deleteChecklistItem(User user, Long taskId, Long itemId) {
        Task task = memberTask(taskId, user);
        ChecklistItem item = checklist.findByIdAndTaskId(itemId, taskId)
                .orElseThrow(() -> ApiException.notFound("Checklist item not found."));
        checklist.delete(item);
        live.taskChanged(task);
    }

    // ---------------------------------------------------------------- activity

    @Transactional(readOnly = true)
    public List<ActivityResponse> activity(User user, Long taskId) {
        memberTask(taskId, user);
        return activity.findForTask(taskId).stream().map(ActivityResponse::of).toList();
    }

    public void record(Task task, User actor, String message) {
        activity.save(new TaskActivity(task, actor, message));
    }

    // ---------------------------------------------------------------- helpers

    /** Loads a task the user may see; non-members get the same 404 as a missing task. */
    public Task memberTask(Long id, User user) {
        Task task = tasks.findById(id).orElseThrow(() -> ApiException.notFound("Task does not exist."));
        if (!task.getProject().hasMember(user)) {
            throw ApiException.notFound("Task does not exist.");
        }
        return task;
    }

    public List<TaskResponse> responses(Collection<Task> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, int[]> progress = new HashMap<>();
        for (Object[] row : checklist.progressFor(list.stream().map(Task::getId).toList())) {
            progress.put((Long) row[0], new int[]{((Number) row[1]).intValue(), ((Number) row[2]).intValue()});
        }
        return list.stream().map(task -> {
            int[] counts = progress.getOrDefault(task.getId(), new int[]{0, 0});
            return TaskResponse.of(task, counts[0], counts[1]);
        }).toList();
    }

    private TaskResponse response(Task task) {
        return responses(List.of(task)).get(0);
    }

    private void notifyParticipants(Task task, User actor, String action) {
        participants(task).forEach(participant -> notifications.notify(participant, actor, task, action));
    }

    private static Set<User> participants(Task task) {
        Map<Long, User> byId = new HashMap<>();
        if (task.getAssignee() != null) {
            byId.put(task.getAssignee().getId(), task.getAssignee());
        }
        byId.put(task.getReporter().getId(), task.getReporter());
        return new LinkedHashSet<>(byId.values());
    }

    private static User member(Project project, Long userId) {
        return project.getMembers().stream()
                .filter(member -> member.getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("The assignee must be a member of " + project.getKey() + "."));
    }

    private Sprint openSprint(Project project, Long sprintId) {
        Sprint sprint = sprints.findById(sprintId)
                .filter(candidate -> candidate.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> ApiException.badRequest("That sprint does not belong to " + project.getKey() + "."));
        if (sprint.getState() == SprintState.COMPLETED) {
            throw ApiException.badRequest("Tasks cannot be added to a completed sprint.");
        }
        return sprint;
    }

    private static String normalize(String description) {
        return description == null ? "" : description.trim();
    }

    static Set<String> normalizeLabels(List<String> labels) {
        Set<String> result = new TreeSet<>();
        if (labels != null) {
            for (String label : labels) {
                String clean = label == null ? "" : label.trim().replaceAll("\\s+", "-").toLowerCase(Locale.ROOT);
                if (!clean.isEmpty()) {
                    result.add(clean.length() > 30 ? clean.substring(0, 30) : clean);
                }
            }
        }
        return result;
    }
}
