package com.fakejira.task;

import com.fakejira.board.BoardColumn;
import com.fakejira.board.BoardColumnRepository;
import com.fakejira.common.ApiException;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.events.LiveEvents;
import com.fakejira.integration.ChatNotifier;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectRepository;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.sprint.SprintState;
import com.fakejira.task.TaskDtos.BulkRequest;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskDtos.UpdateTaskRequest;
import com.fakejira.user.User;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final EpicRepository epics;
    private final BoardColumnRepository columns;
    private final TaskCleanup cleanup;
    private final TaskSupport support;
    private final NotificationService notifications;
    private final LiveEvents live;
    private final ChatNotifier chat;
    private final ChecklistItemRepository checklistItems;
    private final TaskKeyAliasRepository aliases;

    public TaskService(TaskRepository tasks, ProjectRepository projects, ProjectAccess access, SprintRepository sprints,
                       EpicRepository epics, BoardColumnRepository columns, TaskCleanup cleanup, TaskSupport support,
                       NotificationService notifications, LiveEvents live, ChatNotifier chat,
                       ChecklistItemRepository checklistItems, TaskKeyAliasRepository aliases) {
        this.aliases = aliases;
        this.chat = chat;
        this.checklistItems = checklistItems;
        this.tasks = tasks;
        this.projects = projects;
        this.access = access;
        this.sprints = sprints;
        this.epics = epics;
        this.columns = columns;
        this.cleanup = cleanup;
        this.support = support;
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
        return support.responses(found);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(User user, Long id) {
        return support.response(support.memberTask(id, user));
    }

    /** Finds a task by key such as "WR-12" among the user's projects. */
    @Transactional(readOnly = true)
    public TaskResponse byKey(User user, String key) {
        return support.response(taskByKey(user, key));
    }

    public Task taskByKey(User user, String key) {
        // A task that moved to another project is still found by its old key.
        var alias = aliases.findByOldKey(key.toUpperCase(java.util.Locale.ROOT));
        if (alias.isPresent()) {
            Task moved = alias.get().getTask();
            access.requireMember(moved.getProject(), user);
            return moved;
        }
        int dash = key.lastIndexOf('-');
        if (dash <= 0) {
            throw ApiException.notFound("Task " + key + " does not exist.");
        }
        Project project = access.memberProject(key.substring(0, dash), user);
        try {
            return tasks.findByProjectIdAndNumber(project.getId(), Integer.valueOf(key.substring(dash + 1)))
                    .orElseThrow(() -> ApiException.notFound("Task " + key + " does not exist."));
        } catch (NumberFormatException e) {
            throw ApiException.notFound("Task " + key + " does not exist.");
        }
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> subtasks(User user, Long id) {
        support.memberTask(id, user);
        return support.responses(tasks.findByParentIdOrderByIdAsc(id));
    }

    // ---------------------------------------------------------------- writing

    public TaskResponse create(User user, CreateTaskRequest request) {
        Project project = access.editorProject(request.projectKey(), user);
        Task parent = null;
        if (request.parentId() != null) {
            parent = support.memberTask(request.parentId(), user);
            if (!parent.getProject().getId().equals(project.getId())) {
                throw ApiException.badRequest("A subtask must be in the same project as its parent.");
            }
            if (parent.getParent() != null) {
                throw ApiException.badRequest("Subtasks cannot have their own subtasks.");
            }
        }
        project = projects.lockById(project.getId()).orElseThrow();
        Task task = new Task(project, project.allocateNumber(), request.title().trim(),
                normalize(request.description()), request.priority(), user);
        task.setDueDate(request.dueDate());
        task.getLabels().addAll(normalizeLabels(request.labels()));
        task.setStoryPoints(request.storyPoints());
        task.setType(request.type());
        task.setParent(parent);
        if (request.sprintId() != null) {
            task.setSprint(openSprint(project, request.sprintId()));
        } else if (parent != null) {
            task.setSprint(parent.getSprint());
        }
        if (request.epicId() != null) {
            task.setEpic(epic(project, request.epicId()));
        } else if (parent != null) {
            task.setEpic(parent.getEpic());
        }
        if (request.assigneeId() != null) {
            task.setAssignee(editor(project, request.assigneeId()));
        }
        tasks.save(task);
        if (request.checklist() != null) {
            int position = 0;
            for (String text : request.checklist()) {
                if (text != null && !text.isBlank()) {
                    checklistItems.save(new ChecklistItem(task, text.trim(), ++position));
                }
            }
        }
        support.record(task, user, parent == null ? "created the task" : "created the task as a subtask of " + parent.getKey());
        if (parent != null) {
            support.record(parent, user, "added subtask " + task.getKey());
            live.taskChanged(parent);
        }
        if (task.getAssignee() != null) {
            notifications.notify(task.getAssignee(), user, task, "assigned you to");
        }
        live.taskChanged(task);
        chat.created(task, user);
        return support.response(task);
    }

    public TaskResponse update(User user, Long id, UpdateTaskRequest request) {
        Task task = support.editableTask(id, user);
        List<String> changes = new ArrayList<>();
        String title = request.title().trim();
        if (!title.equals(task.getTitle())) {
            changes.add("renamed the task to “" + title + "”");
            task.setTitle(title);
        }
        String description = normalize(request.description());
        boolean descriptionChanged = !description.equals(task.getDescription());
        String oldDescription = task.getDescription();
        if (descriptionChanged) {
            task.setDescription(description);
        }
        if (request.type() != null && request.type() != task.getType()) {
            changes.add("changed the type from " + task.getType().label() + " to " + request.type().label());
            task.setType(request.type());
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
        if (!Objects.equals(request.storyPoints(), task.getStoryPoints())) {
            changes.add(request.storyPoints() == null
                    ? "removed the estimate"
                    : "estimated the task at " + request.storyPoints() + " point" + (request.storyPoints() == 1 ? "" : "s"));
            task.setStoryPoints(request.storyPoints());
        }
        Long currentEpic = task.getEpic() == null ? null : task.getEpic().getId();
        if (!Objects.equals(request.epicId(), currentEpic)) {
            Epic epic = request.epicId() == null ? null : epic(task.getProject(), request.epicId());
            changes.add(epic == null ? "removed the task from its epic" : "added the task to epic " + epic.getName());
            task.setEpic(epic);
        }
        if (descriptionChanged) {
            support.recordChange(task, user, "updated the description", oldDescription, description);
        }
        if (!changes.isEmpty() || descriptionChanged) {
            changes.forEach(change -> support.record(task, user, change));
            support.notifyParticipants(task, user, "updated");
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return support.response(task);
    }

    public TaskResponse changeStatus(User user, Long id, TaskStatus status) {
        Task task = support.editableTask(id, user);
        applyStatus(task, user, status);
        return support.response(task);
    }

    /** Moves a card to a board column, which also sets the column's status. */
    public TaskResponse moveToColumn(User user, Long id, Long columnId) {
        Task task = support.editableTask(id, user);
        BoardColumn column = columns.findById(columnId)
                .filter(c -> c.getProject().getId().equals(task.getProject().getId()))
                .orElseThrow(() -> ApiException.badRequest("That column does not belong to " + task.getProject().getKey() + "."));
        BoardColumn previous = task.getBoardColumn();
        task.setBoardColumn(column);
        if (task.getStatus() != column.getStatus()) {
            applyStatus(task, user, column.getStatus());
            task.setBoardColumn(column);
        } else if (previous == null || !previous.getId().equals(column.getId())) {
            support.record(task, user, "moved the task to column " + column.getName());
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return support.response(task);
    }

    private void applyStatus(Task task, User user, TaskStatus status) {
        if (task.getStatus() == status) {
            return;
        }
        support.record(task, user, "changed status from " + task.getStatus().label() + " to " + status.label());
        chat.statusChanged(task, user, task.getStatus().label(), status.label());
        task.setStatus(status);
        // A column pinned to another status no longer fits; fall back to the first column of the new status.
        if (task.getBoardColumn() != null && task.getBoardColumn().getStatus() != status) {
            task.setBoardColumn(null);
        }
        support.notifyParticipants(task, user, "moved to " + status.label().toLowerCase(Locale.ROOT));
        tasks.saveAndFlush(task);
        live.taskChanged(task);
    }

    public TaskResponse assign(User user, Long id, Long assigneeId) {
        Task task = support.editableTask(id, user);
        applyAssignee(task, user, assigneeId == null ? null : editor(task.getProject(), assigneeId));
        return support.response(task);
    }

    private void applyAssignee(Task task, User user, User next) {
        User previous = task.getAssignee();
        if (Objects.equals(previous == null ? null : previous.getId(), next == null ? null : next.getId())) {
            return;
        }
        task.setAssignee(next);
        if (next == null) {
            support.record(task, user, "unassigned " + previous.getUsername());
            notifications.notify(previous, user, task, "unassigned you from");
        } else {
            support.record(task, user, next.getId().equals(user.getId())
                    ? "assigned the task to themselves"
                    : "assigned the task to " + next.getUsername());
            notifications.notify(next, user, task, "assigned you to");
            if (previous != null) {
                notifications.notify(previous, user, task, "reassigned");
            }
        }
        tasks.saveAndFlush(task);
        live.taskChanged(task);
    }

    public TaskResponse accept(User user, Long id) {
        Task task = support.editableTask(id, user);
        if (task.getAssignee() != null) {
            throw ApiException.conflict(task.isAssignee(user)
                    ? "You already own this task."
                    : "This task was already taken by " + task.getAssignee().getUsername() + ".");
        }
        task.setAssignee(user);
        support.record(task, user, "accepted the task");
        notifications.notify(task.getReporter(), user, task, "accepted");
        tasks.saveAndFlush(task);
        live.taskChanged(task);
        return support.response(task);
    }

    public TaskResponse release(User user, Long id) {
        Task task = support.editableTask(id, user);
        if (!task.isAssignee(user)) {
            throw ApiException.forbidden("Only the assignee can release this task.");
        }
        task.setAssignee(null);
        task.setStatus(TaskStatus.TODO);
        task.setBoardColumn(null);
        support.record(task, user, "released the task");
        notifications.notify(task.getReporter(), user, task, "released");
        tasks.saveAndFlush(task);
        live.taskChanged(task);
        return support.response(task);
    }

    public TaskResponse moveToSprint(User user, Long id, Long sprintId) {
        Task task = support.editableTask(id, user);
        applySprint(task, user, sprintId);
        return support.response(task);
    }

    private void applySprint(Task task, User user, Long sprintId) {
        Sprint target = sprintId == null ? null : openSprint(task.getProject(), sprintId);
        Long current = task.getSprint() == null ? null : task.getSprint().getId();
        if (!Objects.equals(current, sprintId)) {
            task.setSprint(target);
            support.record(task, user, target == null ? "moved the task to the backlog" : "moved the task to " + target.getName());
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
    }

    public void delete(User user, Long id) {
        Task task = support.editableTask(id, user);
        if (!task.isReporter(user) && !task.getProject().isOwner(user)) {
            throw ApiException.forbidden("Only the reporter or the project owner can delete this task.");
        }
        notifications.notify(task.getAssignee(), user, task, "deleted");
        Project project = task.getProject();
        Long taskId = task.getId();
        Task parent = task.getParent();
        cleanup.delete(task);
        live.taskDeleted(project, taskId);
        if (parent != null) {
            live.taskChanged(parent);
        }
    }

    /** Applies one change to many tasks in a single transaction; all tasks must be editable. */
    public List<TaskResponse> bulk(User user, BulkRequest request) {
        List<Task> selected = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(request.taskIds())) {
            selected.add(support.editableTask(id, user));
        }
        if (request.delete()) {
            for (Task task : selected) {
                delete(user, task.getId());
            }
            return List.of();
        }
        for (Task task : selected) {
            if (request.status() != null) {
                applyStatus(task, user, request.status());
            }
            if (request.unassign()) {
                applyAssignee(task, user, null);
            } else if (request.assigneeId() != null) {
                applyAssignee(task, user, editor(task.getProject(), request.assigneeId()));
            }
            if (request.clearSprint()) {
                applySprint(task, user, null);
            } else if (request.sprintId() != null) {
                applySprint(task, user, request.sprintId());
            }
            boolean changed = false;
            if (request.priority() != null && request.priority() != task.getPriority()) {
                support.record(task, user, "changed priority from " + task.getPriority().label() + " to " + request.priority().label());
                task.setPriority(request.priority());
                changed = true;
            }
            if (request.type() != null && request.type() != task.getType()) {
                support.record(task, user, "changed the type from " + task.getType().label() + " to " + request.type().label());
                task.setType(request.type());
                changed = true;
            }
            if (request.clearEpic() && task.getEpic() != null) {
                support.record(task, user, "removed the task from its epic");
                task.setEpic(null);
                changed = true;
            } else if (request.epicId() != null && (task.getEpic() == null || !task.getEpic().getId().equals(request.epicId()))) {
                Epic epic = epic(task.getProject(), request.epicId());
                support.record(task, user, "added the task to epic " + epic.getName());
                task.setEpic(epic);
                changed = true;
            }
            Set<String> labels = new TreeSet<>(task.getLabels());
            labels.addAll(normalizeLabels(request.addLabels()));
            labels.removeAll(normalizeLabels(request.removeLabels()));
            if (labels.size() > 10) {
                throw ApiException.badRequest(task.getKey() + " would have more than 10 labels.");
            }
            if (!labels.equals(task.getLabels())) {
                support.record(task, user, labels.isEmpty() ? "removed all labels" : "set labels to " + String.join(", ", labels));
                task.getLabels().clear();
                task.getLabels().addAll(labels);
                changed = true;
            }
            if (changed) {
                tasks.saveAndFlush(task);
                live.taskChanged(task);
            }
        }
        return support.responses(selected);
    }

    // ---------------------------------------------------------------- helpers

    /** Assignees must be members who can edit (viewers cannot own work). */
    private static User editor(Project project, Long userId) {
        User user = project.getMembers().stream()
                .filter(member -> member.getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("The assignee must be a member of " + project.getKey() + "."));
        if (project.isViewer(user)) {
            throw ApiException.badRequest(user.getUsername() + " has read-only access and cannot be assigned tasks.");
        }
        return user;
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

    private Epic epic(Project project, Long epicId) {
        return epics.findById(epicId)
                .filter(candidate -> candidate.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> ApiException.badRequest("That epic does not belong to " + project.getKey() + "."));
    }

    private static String normalize(String description) {
        return description == null ? "" : description.trim();
    }

    public static Set<String> normalizeLabels(List<String> labels) {
        Set<String> result = new TreeSet<>();
        if (labels != null) {
            for (String label : labels) {
                String clean = label == null ? "" : label.trim().replaceAll("\\s+", "-").replaceAll("[,;|]", "")
                        .toLowerCase(Locale.ROOT);
                if (!clean.isEmpty()) {
                    result.add(clean.length() > 30 ? clean.substring(0, 30) : clean);
                }
            }
        }
        return result;
    }
}
