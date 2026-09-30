package com.fakejira.task;

import com.fakejira.board.BoardColumn;
import com.fakejira.board.BoardColumnRepository;
import com.fakejira.common.ApiException;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.events.LiveEvents;
import com.fakejira.integration.ChatNotifier;
import com.fakejira.sprint.SprintScope;
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

    private final TaskEvents taskEvents;

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
    private final SprintScope scope;
    private final com.fakejira.workflow.WorkflowService workflow;
    private final ScheduleService schedule;
    private final com.fakejira.component.ProjectComponentRepository components;
    private final com.fakejira.template.TypeChecklistRepository typeChecklists;

    public TaskService(TaskRepository tasks, ProjectRepository projects, ProjectAccess access, SprintRepository sprints,
                       EpicRepository epics, BoardColumnRepository columns, TaskCleanup cleanup, TaskSupport support,
                       NotificationService notifications, LiveEvents live, ChatNotifier chat,
                       ChecklistItemRepository checklistItems, TaskKeyAliasRepository aliases, SprintScope scope, TaskEvents taskEvents,
                       com.fakejira.workflow.WorkflowService workflow, ScheduleService schedule,
                       com.fakejira.component.ProjectComponentRepository components,
                       com.fakejira.template.TypeChecklistRepository typeChecklists) {
        this.workflow = workflow;
        this.schedule = schedule;
        this.components = components;
        this.typeChecklists = typeChecklists;
        this.taskEvents = taskEvents;
        this.aliases = aliases;
        this.scope = scope;
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
        if (request.componentIds() != null) {
            for (Long componentId : new LinkedHashSet<>(request.componentIds())) {
                Project owner = project;
                task.getComponents().add(components.findById(componentId)
                        .filter(c -> c.getProject().getId().equals(owner.getId()))
                        .orElseThrow(() -> ApiException.badRequest("That component does not belong to " + owner.getKey() + ".")));
            }
        }
        if (request.assigneeId() != null) {
            task.setAssignee(editor(project, request.assigneeId()));
        } else {
            // A component's lead picks up new work in it.
            task.getComponents().stream().map(com.fakejira.component.ProjectComponent::getLead)
                    .filter(Objects::nonNull).filter(lead -> !owningProject(task).isViewer(lead)).findFirst()
                    .ifPresent(task::setAssignee);
        }
        tasks.save(task);
        scope.statusChanged(task, null, task.getStatus());
        scope.sprintChanged(task, null, user);
        List<String> items = request.checklist() == null || request.checklist().isEmpty()
                ? typeChecklists.findByProjectIdAndType(project.getId(), task.getType())
                        .map(com.fakejira.template.TypeChecklist::getItems).orElse(List.of())
                : request.checklist();
        {
            int position = 0;
            for (String text : items) {
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
        taskEvents.publish(TaskEvent.Kind.CREATED, task, user);
        return support.response(task);
    }

    public TaskResponse update(User user, Long id, UpdateTaskRequest request) {
        return update(user, id, request, null);
    }

    /**
     * {@code expected}: the updatedAt the editor saw when opening the form. When the task changed since,
     * the save is refused so nobody silently overwrites someone else's edit.
     */
    public TaskResponse update(User user, Long id, UpdateTaskRequest request, java.time.Instant expected) {
        Task task = support.editableTask(id, user);
        if (expected != null && task.getUpdatedAt().isAfter(expected.plusMillis(1))) {
            String who = support.lastActor(task).filter(actor -> !actor.getId().equals(user.getId()))
                    .map(User::getUsername).orElse("Someone");
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    who + " changed this task while you were editing it.", java.util.Map.of("updatedBy", who));
        }
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
        java.time.LocalDate oldDue = task.getDueDate();
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
            taskEvents.publish(TaskEvent.Kind.UPDATED, task, user, "changes",
                    String.join("; ", descriptionChanged ? concat(changes, "updated the description") : changes));
            if (!Objects.equals(oldDue, task.getDueDate())) {
                schedule.dueChanged(task, user, oldDue);
            }
        }
        return support.response(task);
    }

    /** For imports and automation: no workflow checks. */
    public TaskResponse changeStatus(User user, Long id, TaskStatus status) {
        Task task = support.editableTask(id, user);
        applyStatus(task, user, status, null, false);
        return support.response(task);
    }

    /** A person changing the status: the project's workflow applies. */
    public TaskResponse changeStatus(User user, Long id, TaskStatus status, Resolution resolution) {
        Task task = support.editableTask(id, user);
        applyStatus(task, user, status, resolution, true);
        return support.response(task);
    }

    /** Moves a card to a board column, which also sets the column's status. */
    public TaskResponse moveToColumn(User user, Long id, Long columnId, Resolution resolution) {
        Task task = support.editableTask(id, user);
        BoardColumn column = columns.findById(columnId)
                .filter(c -> c.getProject().getId().equals(task.getProject().getId()))
                .orElseThrow(() -> ApiException.badRequest("That column does not belong to " + task.getProject().getKey() + "."));
        workflow.check(task, column, resolution);
        BoardColumn previous = task.getBoardColumn();
        task.setBoardColumn(column);
        if (task.getStatus() != column.getStatus()) {
            applyStatus(task, user, column.getStatus(), resolution, false);
            task.setBoardColumn(column);
        } else if (previous == null || !previous.getId().equals(column.getId())) {
            support.record(task, user, "moved the task to column " + column.getName());
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return support.response(task);
    }

    private void applyStatus(Task task, User user, TaskStatus status, Resolution resolution, boolean enforceWorkflow) {
        if (task.getStatus() == status) {
            return;
        }
        if (enforceWorkflow) {
            workflow.check(task, workflow.firstColumn(task.getProject(), status), resolution);
        }
        support.record(task, user, "changed status from " + task.getStatus().label() + " to " + status.label());
        chat.statusChanged(task, user, task.getStatus().label(), status.label());
        TaskStatus from = task.getStatus();
        scope.statusChanged(task, task.getStatus(), status);
        task.setStatus(status);
        if (status == TaskStatus.DONE) {
            task.setResolution(resolution);
            if (resolution != null && resolution != Resolution.DONE) {
                support.record(task, user, "resolved the task as " + resolution.label());
            }
        }
        // A column pinned to another status no longer fits; fall back to the first column of the new status.
        if (task.getBoardColumn() != null && task.getBoardColumn().getStatus() != status) {
            task.setBoardColumn(null);
        }
        support.notifyParticipants(task, user, "moved to " + status.label().toLowerCase(Locale.ROOT));
        tasks.saveAndFlush(task);
        live.taskChanged(task);
        taskEvents.publish(TaskEvent.Kind.STATUS_CHANGED, task, user, "from", from.name(), "to", status.name());
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
        taskEvents.publish(TaskEvent.Kind.ASSIGNED, task, user, "from", previous == null ? "" : previous.getUsername(),
                "to", next == null ? "" : next.getUsername());
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
        taskEvents.publish(TaskEvent.Kind.ASSIGNED, task, user, "from", "", "to", user.getUsername());
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
        taskEvents.publish(TaskEvent.Kind.ASSIGNED, task, user, "from", user.getUsername(), "to", "");
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
            Sprint before = task.getSprint();
            task.setSprint(target);
            scope.sprintChanged(task, before, user);
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
        taskEvents.publish(TaskEvent.Kind.DELETED, task, user);
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
                applyStatus(task, user, request.status(), null, true);
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

    public TaskResponse setResolution(User user, Long id, Resolution resolution) {
        Task task = support.editableTask(id, user);
        if (task.getStatus() != TaskStatus.DONE) {
            throw ApiException.badRequest("Only finished tasks have a resolution.");
        }
        if (task.getResolution() != resolution) {
            task.setResolution(resolution);
            support.record(task, user, "changed the resolution to " + resolution.label());
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return support.response(task);
    }

    public TaskResponse setArchived(User user, Long id, boolean archived) {
        Task task = support.editableTask(id, user);
        if (task.isArchived() != archived) {
            task.setArchivedAt(archived ? java.time.Instant.now() : null);
            support.record(task, user, archived ? "archived the task" : "restored the task from the archive");
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return support.response(task);
    }

    /** Archives the project's finished tasks done more than {@code days} days ago; returns how many. */
    public int archiveDone(User user, String projectKey, int days) {
        Project project = access.editorProject(projectKey, user);
        java.time.Instant before = java.time.Instant.now().minus(java.time.Duration.ofDays(Math.max(0, days)));
        int count = 0;
        for (Task task : tasks.findByProjectId(project.getId())) {
            if (task.getStatus() == TaskStatus.DONE && !task.isArchived() && task.getCompletedAt() != null
                    && task.getCompletedAt().isBefore(before)) {
                task.setArchivedAt(java.time.Instant.now());
                support.record(task, user, "archived the task");
                count++;
            }
        }
        if (count > 0) {
            live.projectChanged(project);
        }
        return count;
    }

    public TaskResponse setHelpers(User user, Long id, List<Long> userIds) {
        Task task = support.editableTask(id, user);
        Set<User> next = new LinkedHashSet<>();
        for (Long userId : new LinkedHashSet<>(userIds)) {
            User helper = editor(task.getProject(), userId);
            if (task.isAssignee(helper)) {
                throw ApiException.badRequest(helper.getUsername() + " is already the assignee.");
            }
            next.add(helper);
        }
        Set<Long> before = new java.util.HashSet<>(task.getHelpers().stream().map(User::getId).toList());
        Set<Long> after = new java.util.HashSet<>(next.stream().map(User::getId).toList());
        if (!before.equals(after)) {
            for (User added : next) {
                if (!before.contains(added.getId())) {
                    notifications.notify(added, user, task, "asked you to help with");
                }
            }
            task.getHelpers().clear();
            task.getHelpers().addAll(next);
            support.record(task, user, next.isEmpty() ? "removed all helpers"
                    : "set helpers to " + String.join(", ", next.stream().map(User::getUsername).toList()));
            tasks.saveAndFlush(task);
            live.taskChanged(task);
        }
        return support.response(task);
    }

    // ---------------------------------------------------------------- helpers

    private static Project owningProject(Task task) {
        return task.getProject();
    }

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

    private static List<String> concat(List<String> list, String extra) {
        List<String> all = new ArrayList<>(list);
        all.add(extra);
        return all;
    }
}
