package com.fakejira.template;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Recurring tasks: a schedule per project that creates a task on the chosen days. */
@RestController
public class RecurringController {

    private static final Logger log = LoggerFactory.getLogger(RecurringController.class);
    private static final int MAX_RULES = 30;

    private final RecurringTaskRepository rules;
    private final TaskService taskService;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RecurringController(RecurringTaskRepository rules, TaskService taskService, ProjectAccess access,
                               CurrentUser currentUser, TransactionTemplate tx) {
        this.rules = rules;
        this.taskService = taskService;
        this.access = access;
        this.currentUser = currentUser;
        this.tx = tx;
        this.clock = Clock.systemDefaultZone();
    }

    public record RecurringRequest(
            @NotBlank(message = "Title is required") @Size(max = 120, message = "Title must be at most 120 characters") String title,
            @Size(max = 5000) String description,
            TaskType type,
            TaskPriority priority,
            @Size(max = 10) List<@Size(max = 30) String> labels,
            @Size(max = 50) List<@Size(max = 200) String> checklist,
            Long assigneeId,
            RecurringTask.Frequency frequency,
            @Min(1) @Max(7) Integer dayOfWeek,
            @Min(1) @Max(28) Integer dayOfMonth,
            @Min(0) @Max(365) Integer dueInDays,
            Boolean active) {
    }

    public record RecurringResponse(Long id, String title, String description, TaskType type, TaskPriority priority,
                                    List<String> labels, List<String> checklist, UserSummary assignee,
                                    RecurringTask.Frequency frequency, int dayOfWeek, int dayOfMonth, Integer dueInDays,
                                    LocalDate nextRun, boolean active, String lastTaskKey) {
        static RecurringResponse of(RecurringTask r) {
            return new RecurringResponse(r.getId(), r.getTitle(), r.getDescription(), r.getType(), r.getPriority(),
                    r.getLabels(), r.getChecklist(), UserSummary.of(r.getAssignee()), r.getFrequency(), r.getDayOfWeek(),
                    r.getDayOfMonth(), r.getDueInDays(), r.getNextRun(), r.isActive(), r.getLastTaskKey());
        }
    }

    @GetMapping("/api/projects/{key}/recurring")
    @Transactional(readOnly = true)
    public List<RecurringResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return rules.findByProjectIdOrderByIdAsc(project.getId()).stream().map(RecurringResponse::of).toList();
    }

    @PostMapping("/api/projects/{key}/recurring")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RecurringResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                    @Valid @RequestBody RecurringRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        if (rules.findByProjectIdOrderByIdAsc(project.getId()).size() >= MAX_RULES) {
            throw ApiException.badRequest("A project can have at most " + MAX_RULES + " recurring tasks.");
        }
        RecurringTask rule = new RecurringTask(project, user);
        apply(rule, project, request);
        return RecurringResponse.of(rules.save(rule));
    }

    @PutMapping("/api/recurring/{id}")
    @Transactional
    public RecurringResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody RecurringRequest request) {
        RecurringTask rule = editable(id, currentUser.from(jwt));
        apply(rule, rule.getProject(), request);
        return RecurringResponse.of(rule);
    }

    @DeleteMapping("/api/recurring/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        rules.delete(editable(id, currentUser.from(jwt)));
    }

    /** Creates the task right away, without changing the schedule. */
    @PostMapping("/api/recurring/{id}/run")
    @Transactional
    public TaskResponse runNow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        RecurringTask rule = editable(id, currentUser.from(jwt));
        return createTask(rule);
    }

    /** Hourly: creates tasks whose day has come, then moves each schedule to its next day. */
    @Scheduled(cron = "${app.recurring.cron:0 5 * * * *}")
    public void runDue() {
        LocalDate today = LocalDate.now(clock);
        List<Long> due = tx.execute(status -> rules.findDue(today).stream().map(RecurringTask::getId).toList());
        if (due == null) {
            return;
        }
        for (Long id : due) {
            try {
                tx.executeWithoutResult(status -> rules.findById(id).ifPresent(rule -> {
                    createTask(rule);
                    // After downtime, create one task and continue from the next future day.
                    while (!rule.getNextRun().isAfter(today)) {
                        rule.advance();
                    }
                }));
            } catch (RuntimeException e) {
                log.warn("Recurring task {} failed: {}", id, e.getMessage());
            }
        }
    }

    private TaskResponse createTask(RecurringTask rule) {
        Project project = rule.getProject();
        User creator = project.canEdit(rule.getCreatedBy()) ? rule.getCreatedBy() : project.getOwner();
        Long assignee = rule.getAssignee() != null && project.canEdit(rule.getAssignee()) ? rule.getAssignee().getId() : null;
        LocalDate today = LocalDate.now(clock);
        CreateTaskRequest request = CreateTaskRequest.of(project.getKey(), rule.getTitle(), rule.getDescription(),
                        rule.getPriority(), rule.getType())
                .withLabels(rule.getLabels())
                .withAssignee(assignee)
                .withDetails(rule.getDueInDays() == null ? null : today.plusDays(rule.getDueInDays()), null, null, null,
                        rule.getChecklist());
        TaskResponse created = taskService.create(creator, request);
        rule.setLastTaskKey(created.key());
        return created;
    }

    private void apply(RecurringTask rule, Project project, RecurringRequest r) {
        rule.update(r.title().trim(), r.description() == null ? "" : r.description().trim(), r.type(), r.priority(),
                r.labels() == null ? List.of() : r.labels().stream().map(l -> l.trim().toLowerCase())
                        .filter(l -> !l.isEmpty() && !l.contains(",")).distinct().toList(),
                r.checklist() == null ? List.of() : r.checklist().stream().map(String::trim).filter(l -> !l.isEmpty()).toList(),
                r.frequency(), r.dayOfWeek() == null ? 1 : r.dayOfWeek(), r.dayOfMonth() == null ? 1 : r.dayOfMonth(),
                r.dueInDays(), LocalDate.now(clock));
        rule.setActive(r.active() == null || r.active());
        if (r.assigneeId() == null) {
            rule.setAssignee(null);
        } else {
            User assignee = project.getMembers().stream().filter(m -> m.getId().equals(r.assigneeId())).findFirst()
                    .filter(project::canEdit)
                    .orElseThrow(() -> ApiException.badRequest("The assignee must be a member who can edit tasks."));
            rule.setAssignee(assignee);
        }
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        rules.deleteAll(rules.findByProjectIdOrderByIdAsc(event.projectId()));
    }

    private RecurringTask editable(Long id, User user) {
        RecurringTask rule = rules.findById(id).orElseThrow(() -> ApiException.notFound("Recurring task not found."));
        access.requireEditor(access.memberProjectById(rule.getProject().getId()), user);
        return rule;
    }
}
