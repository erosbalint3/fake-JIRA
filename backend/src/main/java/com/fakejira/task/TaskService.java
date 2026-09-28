package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.notification.NotificationService;
import com.fakejira.task.TaskDtos.CommentRequest;
import com.fakejira.task.TaskDtos.CommentResponse;
import com.fakejira.task.TaskDtos.TaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class TaskService {

    private final TaskRepository tasks;
    private final CommentRepository comments;
    private final NotificationService notifications;

    public TaskService(TaskRepository tasks, CommentRepository comments, NotificationService notifications) {
        this.tasks = tasks;
        this.comments = comments;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> search(User user, TaskScope scope, String query, TaskPriority priority, TaskStatus status) {
        String pattern = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        return tasks.search(
                        scope == TaskScope.AVAILABLE,
                        scope == TaskScope.MINE ? user.getId() : null,
                        scope == TaskScope.REPORTED ? user.getId() : null,
                        priority,
                        status,
                        pattern)
                .stream().map(TaskResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(Long id) {
        return TaskResponse.of(find(id));
    }

    public TaskResponse create(User user, TaskRequest request) {
        Task task = new Task(request.title().trim(), normalize(request.description()), request.priority(), user);
        return TaskResponse.of(tasks.save(task));
    }

    public TaskResponse update(User user, Long id, TaskRequest request) {
        Task task = find(id);
        requireReporterOrAssignee(user, task, "edit");
        task.setTitle(request.title().trim());
        task.setDescription(normalize(request.description()));
        task.setPriority(request.priority());
        notifications.notify(task.getAssignee(), user, task, "updated");
        notifications.notify(task.getReporter(), user, task, "updated");
        return TaskResponse.of(tasks.saveAndFlush(task));
    }

    public TaskResponse changeStatus(User user, Long id, TaskStatus status) {
        Task task = find(id);
        requireReporterOrAssignee(user, task, "move");
        if (task.getStatus() != status) {
            task.setStatus(status);
            String action = "moved to " + status.name().replace('_', ' ').toLowerCase();
            notifications.notify(task.getAssignee(), user, task, action);
            notifications.notify(task.getReporter(), user, task, action);
        }
        return TaskResponse.of(tasks.saveAndFlush(task));
    }

    public TaskResponse accept(User user, Long id) {
        Task task = find(id);
        if (task.getAssignee() != null) {
            throw ApiException.conflict(task.isAssignee(user)
                    ? "You already own this task."
                    : "This task was already taken by " + task.getAssignee().getUsername() + ".");
        }
        task.setAssignee(user);
        notifications.notify(task.getReporter(), user, task, "accepted");
        return TaskResponse.of(tasks.saveAndFlush(task));
    }

    public TaskResponse release(User user, Long id) {
        Task task = find(id);
        if (!task.isAssignee(user)) {
            throw ApiException.forbidden("Only the assignee can release this task.");
        }
        task.setAssignee(null);
        task.setStatus(TaskStatus.TODO);
        notifications.notify(task.getReporter(), user, task, "released");
        return TaskResponse.of(tasks.saveAndFlush(task));
    }

    public void delete(User user, Long id) {
        Task task = find(id);
        if (!task.isReporter(user)) {
            throw ApiException.forbidden("Only the reporter can delete this task.");
        }
        notifications.notify(task.getAssignee(), user, task, "deleted");
        comments.deleteForTask(task.getId());
        tasks.delete(task);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(Long taskId) {
        find(taskId);
        return comments.findForTask(taskId).stream().map(CommentResponse::of).toList();
    }

    public CommentResponse addComment(User user, Long taskId, CommentRequest request) {
        Task task = find(taskId);
        Comment comment = comments.save(new Comment(task, user, request.body().trim()));
        notifications.notify(task.getAssignee(), user, task, "commented on");
        if (!task.isAssignee(task.getReporter())) {
            notifications.notify(task.getReporter(), user, task, "commented on");
        }
        return CommentResponse.of(comment);
    }

    private Task find(Long id) {
        return tasks.findById(id).orElseThrow(() -> ApiException.notFound("Task FJ-" + id + " does not exist."));
    }

    private static void requireReporterOrAssignee(User user, Task task, String verb) {
        if (!task.isReporter(user) && !task.isAssignee(user)) {
            throw ApiException.forbidden("Only the reporter or assignee can " + verb + " this task.");
        }
    }

    private static String normalize(String description) {
        return description == null ? "" : description.trim();
    }
}
