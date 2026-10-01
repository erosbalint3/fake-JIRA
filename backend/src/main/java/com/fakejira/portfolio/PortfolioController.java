package com.fakejira.portfolio;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.release.Release;
import com.fakejira.release.ReleaseRepository;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.sprint.SprintState;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Every project the user belongs to on one screen: progress, dates and what puts them at risk. */
@RestController
public class PortfolioController {

    public enum Risk { OK, WATCH, AT_RISK }

    public record SprintSummary(Long id, String name, LocalDate end, int done, int total, int percentDone,
                                int percentTime, boolean behind) {
    }

    public record ReleaseSummary(Long id, String name, LocalDate date, int open, boolean late) {
    }

    public record ProjectRow(String key, String name, String color, boolean kanban, int open, int inProgress, int done,
                             int percentDone, int overdue, int unassigned, SprintSummary sprint, ReleaseSummary release,
                             int lateEpics, Instant lastActivity, Risk risk, List<String> reasons) {
    }

    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final SprintRepository sprints;
    private final ReleaseRepository releases;
    private final com.fakejira.epic.EpicRepository epics;
    private final CurrentUser currentUser;
    private final Clock clock;

    public PortfolioController(ProjectRepository projects, TaskRepository tasks, SprintRepository sprints,
                               ReleaseRepository releases, com.fakejira.epic.EpicRepository epics,
                               CurrentUser currentUser) {
        this.projects = projects;
        this.tasks = tasks;
        this.sprints = sprints;
        this.releases = releases;
        this.epics = epics;
        this.currentUser = currentUser;
        this.clock = Clock.systemDefaultZone();
    }

    @GetMapping("/api/portfolio")
    @Transactional(readOnly = true)
    public List<ProjectRow> portfolio(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        LocalDate today = LocalDate.now(clock);
        List<ProjectRow> rows = new ArrayList<>();
        for (Project project : projects.findForMember(user.getId())) {
            List<Task> list = tasks.findByProjectId(project.getId()).stream().filter(t -> !t.isArchived()).toList();
            int done = 0;
            int inProgress = 0;
            int open = 0;
            int overdue = 0;
            int unassigned = 0;
            Instant last = project.getCreatedAt();
            for (Task t : list) {
                if (t.getStatus() == TaskStatus.DONE) {
                    done++;
                } else {
                    open++;
                    if (t.getStatus() != TaskStatus.TODO) {
                        inProgress++;
                    }
                    if (t.getDueDate() != null && t.getDueDate().isBefore(today)) {
                        overdue++;
                    }
                    if (t.getAssignee() == null) {
                        unassigned++;
                    }
                }
                if (t.getUpdatedAt().isAfter(last)) {
                    last = t.getUpdatedAt();
                }
            }
            int total = done + open;
            List<String> reasons = new ArrayList<>();
            SprintSummary sprintSummary = null;
            Sprint active = sprints.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                    .filter(s -> s.getState() == SprintState.ACTIVE).findFirst().orElse(null);
            boolean urgent = false;
            if (active != null) {
                List<Task> in = tasks.findBySprintId(active.getId());
                int sprintDone = (int) in.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
                int pctDone = in.isEmpty() ? 100 : sprintDone * 100 / in.size();
                int pctTime = 0;
                if (active.getStartDate() != null && active.getEndDate() != null) {
                    long length = Math.max(1, ChronoUnit.DAYS.between(active.getStartDate(), active.getEndDate()) + 1);
                    long elapsed = Math.max(0, Math.min(length, ChronoUnit.DAYS.between(active.getStartDate(), today) + 1));
                    pctTime = (int) (elapsed * 100 / length);
                }
                boolean behind = !in.isEmpty() && pctTime - pctDone >= 25;
                sprintSummary = new SprintSummary(active.getId(), active.getName(), active.getEndDate(), sprintDone,
                        in.size(), pctDone, pctTime, behind);
                if (behind) {
                    reasons.add(active.getName() + " is " + pctDone + "% done with " + pctTime + "% of its time gone");
                    urgent = active.getEndDate() != null && !active.getEndDate().isAfter(today.plusDays(3));
                }
                if (active.getEndDate() != null && active.getEndDate().isBefore(today)) {
                    reasons.add(active.getName() + " ended on " + active.getEndDate() + " but is still open");
                    urgent = true;
                }
            }
            ReleaseSummary releaseSummary = null;
            Release next = releases.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                    .filter(r -> !r.isReleased())
                    .min(Comparator.comparing(Release::getReleaseDate, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (next != null) {
                int releaseOpen = (int) list.stream().filter(t -> t.getRelease() != null
                        && t.getRelease().getId().equals(next.getId()) && t.getStatus() != TaskStatus.DONE).count();
                boolean late = next.getReleaseDate() != null && next.getReleaseDate().isBefore(today);
                releaseSummary = new ReleaseSummary(next.getId(), next.getName(), next.getReleaseDate(), releaseOpen, late);
                if (late) {
                    reasons.add(next.getName() + " was due " + next.getReleaseDate() + " (" + releaseOpen + " open)");
                    urgent = true;
                }
            }
            int lateEpics = 0;
            for (var epic : epics.findByProjectIdOrderByCreatedAtAsc(project.getId())) {
                if (epic.getDueDate() != null && epic.getDueDate().isBefore(today) && list.stream()
                        .anyMatch(t -> t.getEpic() != null && t.getEpic().getId().equals(epic.getId())
                                && t.getStatus() != TaskStatus.DONE)) {
                    lateEpics++;
                }
            }
            if (lateEpics > 0) {
                reasons.add(lateEpics + " epic" + (lateEpics == 1 ? " is" : "s are") + " past due");
            }
            if (overdue > 0) {
                reasons.add(overdue + " overdue task" + (overdue == 1 ? "" : "s"));
            }
            Risk risk = urgent || overdue > 5 || lateEpics > 1 ? Risk.AT_RISK : reasons.isEmpty() ? Risk.OK : Risk.WATCH;
            rows.add(new ProjectRow(project.getKey(), project.getName(), project.getColor(), project.isKanban(), open,
                    inProgress, done, total == 0 ? 0 : done * 100 / total, overdue, unassigned, sprintSummary,
                    releaseSummary, lateEpics, last, risk, reasons));
        }
        rows.sort(Comparator.comparing((ProjectRow r) -> -r.risk().ordinal()).thenComparing(ProjectRow::key));
        return rows;
    }
}
