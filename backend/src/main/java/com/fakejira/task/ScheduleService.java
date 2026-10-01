package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.task.TaskDtos.ScheduleRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Start dates, due dates and estimates, plus automatic rescheduling: in projects that turn it on, when a
 * task's due date slips, tasks it blocks move later by as much as needed (keeping their length).
 */
@Service
@Transactional
public class ScheduleService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final TaskSupport support;
    private final TaskLinkRepository links;
    private final TaskRepository tasks;
    private final LiveEvents live;
    private final TaskEvents taskEvents;

    public ScheduleService(TaskSupport support, TaskLinkRepository links, TaskRepository tasks, LiveEvents live,
                           TaskEvents taskEvents) {
        this.support = support;
        this.links = links;
        this.tasks = tasks;
        this.live = live;
        this.taskEvents = taskEvents;
    }

    public TaskResponse schedule(User user, Long id, ScheduleRequest request) {
        Task task = support.editableTask(id, user);
        if (request.startDate() != null && request.dueDate() != null && request.startDate().isAfter(request.dueDate())) {
            throw ApiException.field("startDate", "The start date must be on or before the due date.");
        }
        LocalDate oldDue = task.getDueDate();
        boolean changed = false;
        if (!Objects.equals(task.getStartDate(), request.startDate())) {
            support.record(task, user, request.startDate() == null ? "removed the start date"
                    : "set the start date to " + DATE.format(request.startDate()));
            task.setStartDate(request.startDate());
            changed = true;
        }
        if (!Objects.equals(task.getDueDate(), request.dueDate())) {
            support.record(task, user, request.dueDate() == null ? "removed the due date"
                    : "set the due date to " + DATE.format(request.dueDate()));
            task.setDueDate(request.dueDate());
            changed = true;
        }
        if (!Objects.equals(task.getEstimateMinutes(), request.estimateMinutes())) {
            support.record(task, user, request.estimateMinutes() == null ? "removed the time estimate"
                    : "estimated the work at " + formatMinutes(request.estimateMinutes()));
            task.setEstimateMinutes(request.estimateMinutes());
            changed = true;
        }
        if (changed) {
            tasks.saveAndFlush(task);
            live.taskChanged(task);
            taskEvents.publish(TaskEvent.Kind.UPDATED, task, user, "changes", "schedule");
            dueChanged(task, user, oldDue);
        }
        return support.response(task);
    }

    /** Called whenever a due date changed; cascades to blocked tasks when the project schedules automatically. */
    public void dueChanged(Task task, User user, LocalDate oldDue) {
        if (!task.getProject().isAutoSchedule() || task.getDueDate() == null
                || oldDue != null && !task.getDueDate().isAfter(oldDue)) {
            return;
        }
        Set<Long> visited = new HashSet<>();
        visited.add(task.getId());
        cascade(task, user, visited);
    }

    private void cascade(Task blocker, User user, Set<Long> visited) {
        for (TaskLink link : links.findForTask(blocker.getId())) {
            if (link.getType() != LinkType.BLOCKS || !link.getSource().getId().equals(blocker.getId())) {
                continue;
            }
            Task blocked = link.getTarget();
            if (!visited.add(blocked.getId()) || blocked.getStatus() == TaskStatus.DONE
                    || !blocked.getProject().getId().equals(blocker.getProject().getId())) {
                continue;
            }
            LocalDate start = blocked.getStartDate() != null ? blocked.getStartDate() : blocked.getDueDate();
            LocalDate earliest = blocker.getDueDate().plusDays(1);
            if (start == null || !start.isBefore(earliest)) {
                continue;
            }
            long shift = ChronoUnit.DAYS.between(start, earliest);
            if (blocked.getStartDate() != null) {
                blocked.setStartDate(blocked.getStartDate().plusDays(shift));
            }
            if (blocked.getDueDate() != null) {
                blocked.setDueDate(blocked.getDueDate().plusDays(shift));
            }
            support.record(blocked, user, "rescheduled by " + shift + " day" + (shift == 1 ? "" : "s") + " because "
                    + blocker.getKey() + " slipped");
            support.notifyParticipants(blocked, user, "rescheduled");
            tasks.saveAndFlush(blocked);
            live.taskChanged(blocked);
            cascade(blocked, user, visited);
        }
    }

    static String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return h == 0 ? m + "m" : m == 0 ? h + "h" : h + "h " + m + "m";
    }
}
