package com.fakejira.sprint;

import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.StatusChange;
import com.fakejira.task.StatusChangeRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Keeps the history planning reports need: tasks joining or leaving a running sprint (scope change) and
 * every status transition (flow, cycle time).
 */
@Component
public class SprintScope {

    private final SprintChangeRepository changes;
    private final SprintRepository sprints;
    private final StatusChangeRepository statuses;

    public SprintScope(SprintChangeRepository changes, SprintRepository sprints, StatusChangeRepository statuses) {
        this.changes = changes;
        this.sprints = sprints;
        this.statuses = statuses;
    }

    /** Call after a task's sprint changed from {@code before} to its current sprint. */
    public void sprintChanged(Task task, Sprint before, User actor) {
        Sprint after = task.getSprint();
        Long beforeId = before == null ? null : before.getId();
        Long afterId = after == null ? null : after.getId();
        if (Objects.equals(beforeId, afterId)) {
            return;
        }
        if (before != null && before.getState() == SprintState.ACTIVE) {
            changes.save(new SprintChange(before, task.getId(), task.getKey(), task.getTitle(), task.getStoryPoints(), false,
                    actor == null ? null : actor.getUsername()));
        }
        if (after != null && after.getState() == SprintState.ACTIVE) {
            changes.save(new SprintChange(after, task.getId(), task.getKey(), task.getTitle(), task.getStoryPoints(), true,
                    actor == null ? null : actor.getUsername()));
        }
    }

    /** An unfinished task went back to the backlog when its sprint was completed. */
    public void carriedOver(Task task, Sprint sprint) {
        changes.save(SprintChange.carriedOver(sprint, task.getId(), task.getKey(), task.getTitle(), task.getStoryPoints()));
    }

    public void statusChanged(Task task, TaskStatus from, TaskStatus to) {
        statuses.save(new StatusChange(task.getId(), task.getProject().getId(), from, to));
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        statuses.deleteForTask(event.taskId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        sprints.findByProjectIdOrderByCreatedAtAsc(event.projectId()).forEach(s -> changes.deleteForSprint(s.getId()));
    }
}
