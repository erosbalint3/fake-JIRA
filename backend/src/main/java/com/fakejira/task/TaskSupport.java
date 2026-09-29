package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.notification.NotificationService;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.user.User;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared task helpers: access checks, history, notifications and response building. */
@Component
public class TaskSupport {

    private final TaskRepository tasks;
    private final ChecklistItemRepository checklist;
    private final TimeEntryRepository time;
    private final TaskLinkRepository links;
    private final TaskActivityRepository activity;
    private final NotificationService notifications;

    public TaskSupport(TaskRepository tasks, ChecklistItemRepository checklist, TimeEntryRepository time,
                       TaskLinkRepository links, TaskActivityRepository activity, NotificationService notifications) {
        this.tasks = tasks;
        this.checklist = checklist;
        this.time = time;
        this.links = links;
        this.activity = activity;
        this.notifications = notifications;
    }

    /** Loads a task the user may see; non-members get the same 404 as a missing task. */
    public Task memberTask(Long id, User user) {
        Task task = tasks.findById(id).orElseThrow(() -> ApiException.notFound("Task does not exist."));
        if (!task.getProject().hasMember(user)) {
            throw ApiException.notFound("Task does not exist.");
        }
        return task;
    }

    /** Like {@link #memberTask} but also rejects read-only viewers. */
    public Task editableTask(Long id, User user) {
        Task task = memberTask(id, user);
        if (!task.getProject().canEdit(user)) {
            throw ApiException.forbidden("You have read-only access to " + task.getProject().getKey() + ".");
        }
        return task;
    }

    public void record(Task task, User actor, String message) {
        activity.save(new TaskActivity(task, actor, message));
    }

    /** Reporter, assignee and watchers, without duplicates. */
    public Set<User> participants(Task task) {
        Map<Long, User> byId = new LinkedHashMap<>();
        byId.put(task.getReporter().getId(), task.getReporter());
        if (task.getAssignee() != null) {
            byId.put(task.getAssignee().getId(), task.getAssignee());
        }
        task.getWatchers().forEach(watcher -> byId.putIfAbsent(watcher.getId(), watcher));
        return new LinkedHashSet<>(byId.values());
    }

    public void notifyParticipants(Task task, User actor, String action) {
        participants(task).forEach(participant -> notifications.notify(participant, actor, task, action));
    }

    public TaskResponse response(Task task) {
        return responses(List.of(task)).get(0);
    }

    /** Builds responses with aggregate counts loaded in a few queries rather than per task. */
    public List<TaskResponse> responses(Collection<Task> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Task::getId).toList();
        Map<Long, int[]> progress = new HashMap<>();
        for (Object[] row : checklist.progressFor(ids)) {
            progress.put((Long) row[0], new int[]{((Number) row[1]).intValue(), ((Number) row[2]).intValue()});
        }
        Map<Long, int[]> subtasks = new HashMap<>();
        for (Object[] row : tasks.subtaskProgressFor(ids)) {
            subtasks.put((Long) row[0], new int[]{((Number) row[1]).intValue(), ((Number) row[2]).intValue()});
        }
        Map<Long, Integer> minutes = new HashMap<>();
        for (Object[] row : time.totalsFor(ids)) {
            minutes.put((Long) row[0], ((Number) row[1]).intValue());
        }
        Set<Long> blocked = new HashSet<>(links.blockedAmong(ids));
        return list.stream().map(task -> {
            int[] check = progress.getOrDefault(task.getId(), new int[]{0, 0});
            int[] subs = subtasks.getOrDefault(task.getId(), new int[]{0, 0});
            return TaskResponse.of(task, check[0], check[1], subs[0], subs[1],
                    minutes.getOrDefault(task.getId(), 0), blocked.contains(task.getId()));
        }).toList();
    }
}
