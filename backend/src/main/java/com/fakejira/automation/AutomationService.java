package com.fakejira.automation;

import com.fakejira.notification.NotificationService;
import com.fakejira.project.Project;
import com.fakejira.search.SearchService;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.sprint.SprintState;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskDetailsService;
import com.fakejira.task.TaskDtos.UpdateTaskRequest;
import com.fakejira.task.TaskEvent;
import com.fakejira.task.TaskEvents;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Runs automation rules: on task events (after the change is committed) and on a schedule. */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    static final int LOG_SIZE = 30;
    /** Most tasks one scheduled rule acts on per run, so a broad condition cannot flood the system. */
    static final int SCHEDULED_BATCH = 50;

    private final AutomationRuleRepository rules;
    private final RuleRunRepository runs;
    private final RuleFiringRepository firings;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final TaskDetailsService details;
    private final SprintRepository sprints;
    private final NotificationService notifications;
    private final SearchService search;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public AutomationService(AutomationRuleRepository rules, RuleRunRepository runs, RuleFiringRepository firings,
                             TaskRepository tasks, TaskService taskService, TaskDetailsService details,
                             SprintRepository sprints, NotificationService notifications, SearchService search,
                             ObjectMapper json, PlatformTransactionManager transactions) {
        this.rules = rules;
        this.runs = runs;
        this.firings = firings;
        this.tasks = tasks;
        this.taskService = taskService;
        this.details = details;
        this.sprints = sprints;
        this.notifications = notifications;
        this.search = search;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ triggers

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskEvent(TaskEvent event) {
        if (event.automated() || event.kind() == TaskEvent.Kind.DELETED) {
            return;
        }
        // Rules can email or post outside the team, so internal comments do not trigger them.
        if (event.kind() == TaskEvent.Kind.COMMENTED && "true".equals(event.details().get("internal"))) {
            return;
        }
        AutomationRule.Trigger trigger = switch (event.kind()) {
            case CREATED -> AutomationRule.Trigger.CREATED;
            case UPDATED -> AutomationRule.Trigger.UPDATED;
            case STATUS_CHANGED -> AutomationRule.Trigger.STATUS_CHANGED;
            case ASSIGNED -> AutomationRule.Trigger.ASSIGNED;
            case COMMENTED -> AutomationRule.Trigger.COMMENTED;
            default -> null;
        };
        List<Long> matching = tx.execute(status -> rules.findByProjectIdAndEnabledTrueAndTrigger(event.projectId(), trigger)
                .stream()
                .filter(r -> r.getTriggerStatus() == null || r.getTriggerStatus().name().equals(event.details().get("to")))
                .map(AutomationRule::getId).toList());
        for (Long ruleId : matching == null ? List.<Long>of() : matching) {
            runSafely(ruleId, event.taskId(), true);
        }
    }

    /** Every few minutes: scheduled rules act on tasks that newly match their condition. */
    @Scheduled(cron = "${app.automation.cron:0 */5 * * * *}")
    public void runScheduled() {
        List<Long> ids = tx.execute(status -> rules.findByEnabledTrueAndTrigger(AutomationRule.Trigger.SCHEDULED).stream()
                .map(AutomationRule::getId).toList());
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                runScheduledRule(id);
            } catch (RuntimeException e) {
                log.warn("Scheduled rule {} failed: {}", id, e.getMessage());
            }
        }
    }

    /** Runs one scheduled rule now; returns how many tasks it acted on. */
    public int runScheduledRule(Long ruleId) {
        List<Long> fresh = tx.execute(status -> {
            AutomationRule rule = rules.findById(ruleId).orElse(null);
            if (rule == null || !rule.isEnabled() || !rule.getProject().hasMember(rule.getOwner())) {
                return List.<Long>of();
            }
            List<Task> matching = search.run(rule.getOwner(), scopedQuery(rule, null), SearchService.MAX_RESULTS).tasks();
            Set<Long> matchingIds = new HashSet<>();
            matching.forEach(t -> matchingIds.add(t.getId()));
            Set<Long> fired = new HashSet<>();
            for (RuleFiring f : firings.findByRuleId(ruleId)) {
                if (matchingIds.contains(f.getTaskId())) {
                    fired.add(f.getTaskId());
                } else {
                    // Stopped matching: may fire again if it matches later.
                    firings.delete(f);
                }
            }
            List<Long> todo = new ArrayList<>();
            for (Task t : matching) {
                if (!fired.contains(t.getId()) && todo.size() < SCHEDULED_BATCH) {
                    todo.add(t.getId());
                    firings.save(new RuleFiring(ruleId, t.getId()));
                }
            }
            return todo;
        });
        int count = 0;
        for (Long taskId : fresh == null ? List.<Long>of() : fresh) {
            if (runSafely(ruleId, taskId, false)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ execution

    /** Checks the condition and applies the actions in their own transaction; failures are logged on the rule. */
    private boolean runSafely(Long ruleId, Long taskId, boolean checkCondition) {
        try {
            Boolean ran = tx.execute(status -> run(ruleId, taskId, checkCondition));
            return Boolean.TRUE.equals(ran);
        } catch (RuntimeException e) {
            log.info("Automation rule {} failed on task {}: {}", ruleId, taskId, e.getMessage());
            tx.executeWithoutResult(status -> rules.findById(ruleId).ifPresent(rule -> {
                String key = tasks.findById(taskId).map(Task::getKey).orElse("#" + taskId);
                rule.ran(e.getMessage());
                log(rule, taskId, key, false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }));
            return false;
        }
    }

    private boolean run(Long ruleId, Long taskId, boolean checkCondition) {
        AutomationRule rule = rules.findById(ruleId).orElse(null);
        Task task = tasks.findById(taskId).orElse(null);
        if (rule == null || task == null || !rule.isEnabled()) {
            return false;
        }
        User owner = rule.getOwner();
        if (!rule.getProject().canEdit(owner)) {
            throw new IllegalStateException(owner.getUsername() + " can no longer edit " + rule.getProject().getKey()
                    + "; the rule is paused until someone else re-creates it.");
        }
        if (checkCondition && !rule.getCondition().isBlank()
                && search.run(owner, scopedQuery(rule, task), 1).total() == 0) {
            return false;
        }
        List<String> done = new ArrayList<>();
        TaskEvents.asRule(rule.getName(), () -> {
            for (JsonNode action : actions(rule)) {
                done.add(apply(rule, owner, taskId, action.path("type").asText(), action.path("value").asText("")));
            }
        });
        rule.ran(null);
        log(rule, taskId, task.getKey(), true, String.join("; ", done));
        return true;
    }

    /** The rule's condition limited to its project (and to one task when given). */
    static String scopedQuery(AutomationRule rule, Task task) {
        String base = "project = " + rule.getProject().getKey() + (task == null ? "" : " AND key = " + task.getKey());
        return rule.getCondition().isBlank() ? base : base + " AND (" + rule.getCondition() + ")";
    }

    private List<JsonNode> actions(AutomationRule rule) {
        try {
            List<JsonNode> list = new ArrayList<>();
            json.readTree(rule.getActions()).forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new IllegalStateException("The rule's actions are not valid.");
        }
    }

    /** Applies one action; returns a short description for the log. */
    private String apply(AutomationRule rule, User owner, Long taskId, String type, String value) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new IllegalStateException("The task was deleted."));
        return switch (type) {
            case "set_status" -> {
                TaskStatus status = TaskStatus.valueOf(value);
                taskService.changeStatus(owner, taskId, status);
                yield "status → " + status.label();
            }
            case "set_priority" -> {
                TaskPriority priority = TaskPriority.valueOf(value);
                taskService.update(owner, taskId, update(task, priority, null, task.getDueDate()));
                yield "priority → " + priority.label();
            }
            case "add_label", "remove_label" -> {
                Set<String> labels = new TreeSet<>(task.getLabels());
                String label = value.trim().toLowerCase(Locale.ROOT);
                if (type.equals("add_label")) {
                    labels.add(label);
                } else {
                    labels.remove(label);
                }
                taskService.update(owner, taskId, update(task, task.getPriority(), new ArrayList<>(labels), task.getDueDate()));
                yield (type.equals("add_label") ? "added label " : "removed label ") + label;
            }
            case "set_due_in_days" -> {
                LocalDate due = LocalDate.now().plusDays(Integer.parseInt(value.trim()));
                taskService.update(owner, taskId, update(task, task.getPriority(), null, due));
                yield "due → " + due;
            }
            case "assign" -> {
                User assignee = pickAssignee(rule, task, value);
                taskService.assign(owner, taskId, assignee == null ? null : assignee.getId());
                yield assignee == null ? "unassigned" : "assigned to " + assignee.getUsername();
            }
            case "comment" -> {
                details.addComment(owner, taskId, fill(value, task));
                yield "commented";
            }
            case "notify" -> {
                Set<User> recipients = recipients(task, value);
                String message = "Rule “" + rule.getName() + "” flagged " + task.getKey() + " · " + task.getTitle();
                recipients.forEach(r -> notifications.notify(r, owner, message, task.getId()));
                yield "notified " + recipients.size();
            }
            case "move_to_active_sprint" -> {
                var active = sprints.findFirstByProjectIdAndState(task.getProject().getId(), SprintState.ACTIVE)
                        .orElseThrow(() -> new IllegalStateException("There is no active sprint."));
                taskService.moveToSprint(owner, taskId, active.getId());
                yield "moved to " + active.getName();
            }
            default -> throw new IllegalStateException("Unknown action " + type + ".");
        };
    }

    private static UpdateTaskRequest update(Task t, TaskPriority priority, List<String> labels, LocalDate due) {
        return new UpdateTaskRequest(t.getTitle(), t.getDescription(), priority, due,
                labels == null ? new ArrayList<>(t.getLabels()) : labels, t.getStoryPoints(),
                t.getEpic() == null ? null : t.getEpic().getId(), t.getType());
    }

    /**
     * reporter, unassign, a username, round_robin (next member in turn) or least_loaded (member with the fewest
     * open tasks). Viewers and people who are out of office are skipped.
     */
    private User pickAssignee(AutomationRule rule, Task task, String value) {
        Project project = task.getProject();
        LocalDate today = LocalDate.now();
        List<User> candidates = project.getMembers().stream()
                .filter(project::canEdit).filter(u -> !u.isAwayOn(today))
                .sorted(Comparator.comparing(User::getId)).toList();
        return switch (value) {
            case "unassign" -> null;
            case "reporter" -> task.getReporter();
            case "round_robin" -> {
                if (candidates.isEmpty()) {
                    throw new IllegalStateException("Nobody is available to assign.");
                }
                yield candidates.get(rule.nextRoundRobin(candidates.size()));
            }
            case "least_loaded" -> {
                Map<Long, Integer> open = new HashMap<>();
                for (Task t : tasks.findByProjectId(project.getId())) {
                    if (t.getAssignee() != null && t.getStatus() != TaskStatus.DONE && !t.getId().equals(task.getId())) {
                        open.merge(t.getAssignee().getId(), 1, Integer::sum);
                    }
                }
                yield candidates.stream().min(Comparator.comparingInt((User u) -> open.getOrDefault(u.getId(), 0))
                                .thenComparing(User::getId))
                        .orElseThrow(() -> new IllegalStateException("Nobody is available to assign."));
            }
            default -> project.getMembers().stream().filter(u -> u.getUsername().equalsIgnoreCase(value.trim()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(value + " is not a member of " + project.getKey() + "."));
        };
    }

    private static Set<User> recipients(Task task, String value) {
        Set<User> out = new java.util.LinkedHashSet<>();
        for (String who : value.split(",")) {
            switch (who.trim().toLowerCase(Locale.ROOT)) {
                case "assignee" -> {
                    if (task.getAssignee() != null) {
                        out.add(task.getAssignee());
                    }
                }
                case "reporter" -> out.add(task.getReporter());
                case "watchers" -> out.addAll(task.getWatchers());
                case "owner" -> out.add(task.getProject().getOwner());
                default -> task.getProject().getMembers().stream().filter(u -> u.getUsername().equalsIgnoreCase(who.trim()))
                        .findFirst().ifPresent(out::add);
            }
        }
        return out;
    }

    /** {{key}}, {{title}}, {{assignee}}, {{reporter}}, {{status}} in comment templates. */
    static String fill(String template, Task task) {
        return template.replace("{{key}}", task.getKey())
                .replace("{{title}}", task.getTitle())
                .replace("{{assignee}}", task.getAssignee() == null ? "nobody" : "@" + task.getAssignee().getUsername())
                .replace("{{reporter}}", "@" + task.getReporter().getUsername())
                .replace("{{status}}", task.getStatus().label());
    }

    private void log(AutomationRule rule, Long taskId, String key, boolean success, String message) {
        RuleRun saved = runs.save(new RuleRun(rule.getId(), taskId, key, success, message.isBlank() ? "no changes" : message));
        List<RuleRun> recent = runs.findByRuleIdOrderByIdDesc(rule.getId(), PageRequest.of(0, LOG_SIZE));
        if (recent.size() == LOG_SIZE && saved.getId() != null) {
            runs.trim(rule.getId(), recent.get(recent.size() - 1).getId());
        }
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        firings.deleteForTask(event.taskId());
    }

    @EventListener
    public void onProjectDeleting(com.fakejira.project.ProjectDeleting event) {
        for (AutomationRule rule : rules.findByProjectIdOrderByIdAsc(event.projectId())) {
            runs.deleteForRule(rule.getId());
            firings.deleteForRule(rule.getId());
            rules.delete(rule);
        }
    }
}
