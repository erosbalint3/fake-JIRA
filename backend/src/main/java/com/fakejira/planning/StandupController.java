package com.fakejira.planning;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.Task;
import com.fakejira.task.TaskActivity;
import com.fakejira.task.TaskActivityRepository;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskLinkRepository;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TimeEntry;
import com.fakejira.task.TimeEntryRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The daily stand-up, built from what actually happened: per person, what they finished and worked on since
 * the previous working day, what is in progress now, and what is blocked.
 */
@RestController
public class StandupController {

    private final TaskRepository tasks;
    private final TaskActivityRepository activity;
    private final TaskLinkRepository links;
    private final TimeEntryRepository time;
    private final ProjectAccess access;
    private final CurrentUser currentUser;

    public StandupController(TaskRepository tasks, TaskActivityRepository activity, TaskLinkRepository links,
                             TimeEntryRepository time, ProjectAccess access, CurrentUser currentUser) {
        this.tasks = tasks;
        this.activity = activity;
        this.links = links;
        this.time = time;
        this.access = access;
        this.currentUser = currentUser;
    }

    public record Person(UserSummary user, boolean away, List<TaskRef> finished, List<TaskRef> workedOn,
                         List<TaskRef> today, List<TaskRef> blocked, int minutesLogged) {
    }

    public record Standup(LocalDate date, LocalDate since, List<Person> people) {
    }

    @GetMapping("/api/projects/{key}/standup")
    @Transactional(readOnly = true)
    public Standup standup(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                           @RequestParam(required = false) LocalDate date) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDate since = previousWorkday(day);
        ZoneId zone = ZoneId.systemDefault();
        List<TaskActivity> events = activity.inProject(project.getId(), since.atStartOfDay(zone).toInstant(),
                day.atStartOfDay(zone).toInstant());
        List<Task> all = tasks.findByProjectId(project.getId()).stream().filter(t -> !t.isArchived()).toList();
        Set<Long> blockedIds = all.isEmpty() ? Set.of()
                : new HashSet<>(links.blockedAmong(all.stream().map(Task::getId).toList()));
        Map<Long, Integer> minutes = new LinkedHashMap<>();
        for (TimeEntry entry : time.findInProject(project.getId(), since, day.minusDays(1))) {
            minutes.merge(entry.getUser().getId(), entry.getMinutes(), Integer::sum);
        }
        List<Person> people = new ArrayList<>();
        for (User member : project.getMembers().stream().sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER)).toList()) {
            if (project.isViewer(member)) {
                continue;
            }
            List<TaskRef> finished = new ArrayList<>();
            Set<Long> finishedIds = new HashSet<>();
            for (Task t : all) {
                if (t.getStatus() == TaskStatus.DONE && t.getCompletedAt() != null && owns(t, member)
                        && !t.getCompletedAt().isBefore(since.atStartOfDay(zone).toInstant())
                        && t.getCompletedAt().isBefore(day.atStartOfDay(zone).toInstant())) {
                    finished.add(TaskRef.of(t));
                    finishedIds.add(t.getId());
                }
            }
            Map<Long, TaskRef> worked = new LinkedHashMap<>();
            for (TaskActivity a : events) {
                if (a.getActor().getId().equals(member.getId()) && !finishedIds.contains(a.getTask().getId())
                        && !a.getTask().isArchived()) {
                    worked.putIfAbsent(a.getTask().getId(), TaskRef.of(a.getTask()));
                }
            }
            List<TaskRef> today = new ArrayList<>();
            List<TaskRef> blocked = new ArrayList<>();
            for (Task t : all) {
                if (!owns(t, member) || t.getStatus() == TaskStatus.DONE) {
                    continue;
                }
                if (blockedIds.contains(t.getId())) {
                    blocked.add(TaskRef.of(t));
                } else if (t.getStatus() != TaskStatus.TODO) {
                    today.add(TaskRef.of(t));
                }
            }
            people.add(new Person(UserSummary.of(member), member.isAwayOn(day), finished, new ArrayList<>(worked.values()),
                    today, blocked, minutes.getOrDefault(member.getId(), 0)));
        }
        return new Standup(day, since, people);
    }

    private static boolean owns(Task task, User user) {
        return task.isAssignee(user) || task.isHelper(user);
    }

    static LocalDate previousWorkday(LocalDate day) {
        LocalDate d = day.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
