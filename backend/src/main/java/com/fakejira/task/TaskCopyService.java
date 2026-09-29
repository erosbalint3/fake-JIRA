package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectRepository;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.user.User;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Cloning a task, and moving a task (with its subtasks) to another project. */
@Service
@Transactional
public class TaskCopyService {

    private final TaskService taskService;
    private final TaskSupport support;
    private final TaskRepository tasks;
    private final ChecklistItemRepository checklist;
    private final TaskKeyAliasRepository aliases;
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final LiveEvents live;

    public TaskCopyService(TaskService taskService, TaskSupport support, TaskRepository tasks,
                           ChecklistItemRepository checklist, TaskKeyAliasRepository aliases, ProjectAccess access,
                           ProjectRepository projects, LiveEvents live) {
        this.taskService = taskService;
        this.support = support;
        this.tasks = tasks;
        this.checklist = checklist;
        this.aliases = aliases;
        this.access = access;
        this.projects = projects;
        this.live = live;
    }

    /** A copy in the same project and sprint: title, description, type, priority, labels, points, epic, and optionally subtasks. */
    public TaskResponse cloneTask(User user, Long id, boolean withSubtasks) {
        Task source = support.editableTask(id, user);
        TaskResponse copy = copy(user, source, source.getParent() == null ? null : source.getParent().getId(), "Copy of ");
        if (withSubtasks && source.getParent() == null) {
            for (Task subtask : tasks.findByParentIdOrderByIdAsc(source.getId())) {
                copy(user, subtask, copy.id(), "");
            }
        }
        support.record(tasks.findById(copy.id()).orElseThrow(), user, "cloned the task from " + source.getKey());
        return taskService.get(user, copy.id());
    }

    private TaskResponse copy(User user, Task source, Long parentId, String prefix) {
        String title = (prefix + source.getTitle());
        List<String> items = checklist.findByTaskIdOrderByPositionAscIdAsc(source.getId()).stream().map(ChecklistItem::getText).toList();
        CreateTaskRequest request = new CreateTaskRequest(source.getProject().getKey(),
                title.length() > 120 ? title.substring(0, 120) : title, source.getDescription(), source.getPriority(),
                source.getDueDate(), List.copyOf(source.getLabels()),
                source.getAssignee() == null ? null : source.getAssignee().getId(),
                parentId == null && source.getSprint() != null && source.getSprint().getState() != com.fakejira.sprint.SprintState.COMPLETED
                        ? source.getSprint().getId() : null,
                source.getStoryPoints(), source.getEpic() == null ? null : source.getEpic().getId(), parentId,
                source.getType(), items);
        return taskService.create(user, request);
    }

    /**
     * Moves a top-level task and its subtasks to another project. They get new keys there; the old keys keep
     * working as aliases. Sprint, epic and column are cleared; assignees and watchers stay only if they are
     * members of the target project.
     */
    public TaskResponse move(User user, Long id, String targetKey) {
        Task task = support.editableTask(id, user);
        if (task.getParent() != null) {
            throw ApiException.badRequest("Move the parent task; subtasks move with it.");
        }
        Project source = task.getProject();
        Project target = access.editorProject(targetKey, user);
        if (target.getId().equals(source.getId())) {
            throw ApiException.badRequest("The task is already in " + target.getKey() + ".");
        }
        target = projects.lockById(target.getId()).orElseThrow();
        String oldKey = task.getKey();
        moveOne(task, target);
        for (Task subtask : tasks.findByParentIdOrderByIdAsc(task.getId())) {
            moveOne(subtask, target);
        }
        support.record(task, user, "moved the task from " + oldKey + " (" + source.getName() + ") to " + target.getName());
        live.projectChanged(source);
        live.taskChanged(task);
        return support.response(task);
    }

    private void moveOne(Task task, Project target) {
        aliases.save(new TaskKeyAlias(task.getKey(), task));
        task.moveTo(target, target.allocateNumber());
        task.setSprint(null);
        task.setEpic(null);
        task.setBoardColumn(null);
        if (task.getAssignee() != null && !target.canEdit(task.getAssignee())) {
            task.setAssignee(null);
        }
        task.getWatchers().removeIf(watcher -> !target.hasMember(watcher));
        tasks.saveAndFlush(task);
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        aliases.deleteForTask(event.taskId());
    }
}
