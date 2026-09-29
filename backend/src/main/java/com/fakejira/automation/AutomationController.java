package com.fakejira.automation;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.search.Fql;
import com.fakejira.search.SearchService;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Project automation rules. Project editors manage them; each rule acts as the person who created it. */
@RestController
public class AutomationController {

    static final int MAX_RULES = 50;
    static final int MAX_ACTIONS = 8;
    static final Set<String> ACTIONS = Set.of("set_status", "set_priority", "add_label", "remove_label", "set_due_in_days",
            "assign", "comment", "notify", "move_to_active_sprint");

    private final AutomationRuleRepository rules;
    private final RuleRunRepository runs;
    private final RuleFiringRepository firings;
    private final AutomationService automation;
    private final SearchService search;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public AutomationController(AutomationRuleRepository rules, RuleRunRepository runs, RuleFiringRepository firings,
                                AutomationService automation, SearchService search, ProjectAccess access,
                                CurrentUser currentUser, ObjectMapper json, AuditLog audit,
                                org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.rules = rules;
        this.runs = runs;
        this.firings = firings;
        this.automation = automation;
        this.search = search;
        this.access = access;
        this.currentUser = currentUser;
        this.json = json;
        this.audit = audit;
    }

    public record RuleRequest(
            @NotBlank(message = "Name is required") @Size(max = 80, message = "Name must be at most 80 characters") String name,
            @NotNull(message = "Pick a trigger") AutomationRule.Trigger trigger,
            TaskStatus triggerStatus,
            @Size(max = 1000, message = "The condition must be at most 1000 characters") String condition,
            @NotNull(message = "Add at least one action") JsonNode actions,
            Boolean enabled) {
    }

    public record RuleResponse(Long id, String name, boolean enabled, AutomationRule.Trigger trigger,
                               TaskStatus triggerStatus, String condition, JsonNode actions, UserSummary owner,
                               Instant lastRunAt, int runCount, String lastError, boolean mine) {
    }

    public record RunResponse(Long id, Long taskId, String taskKey, Instant ranAt, boolean success, String message) {
    }

    @GetMapping("/api/projects/{key}/automations")
    @Transactional(readOnly = true)
    public List<RuleResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        return rules.findByProjectIdOrderByIdAsc(project.getId()).stream().map(r -> response(r, user)).toList();
    }

    @PostMapping("/api/projects/{key}/automations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RuleResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody RuleRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        if (rules.findByProjectIdOrderByIdAsc(project.getId()).size() >= MAX_RULES) {
            throw ApiException.badRequest("A project can have at most " + MAX_RULES + " rules.");
        }
        AutomationRule rule = new AutomationRule(project, user);
        apply(rule, request, user);
        rules.save(rule);
        audit.record(user, "automation.create", project.getKey(), rule.getName());
        return response(rule, user);
    }

    @PutMapping("/api/automations/{id}")
    @Transactional
    public RuleResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody RuleRequest request) {
        User user = currentUser.from(jwt);
        AutomationRule rule = editable(id, user);
        boolean conditionChanged = !rule.getCondition().equals(trim(request.condition()));
        apply(rule, request, user);
        if (conditionChanged) {
            firings.deleteForRule(rule.getId());
        }
        return response(rule, user);
    }

    @DeleteMapping("/api/automations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        AutomationRule rule = editable(id, user);
        runs.deleteForRule(id);
        firings.deleteForRule(id);
        audit.record(user, "automation.delete", rule.getProject().getKey(), rule.getName());
        rules.delete(rule);
    }

    /** Runs a scheduled rule right away (instead of waiting for the next check). */
    @PostMapping("/api/automations/{id}/run")
    public Map<String, Integer> runNow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        tx.executeWithoutResult(status -> {
            AutomationRule rule = editable(id, user);
            if (rule.getTrigger() != AutomationRule.Trigger.SCHEDULED) {
                throw ApiException.badRequest("Only scheduled rules can be run on demand.");
            }
        });
        return Map.of("tasks", automation.runScheduledRule(id));
    }

    @GetMapping("/api/automations/{id}/log")
    @Transactional(readOnly = true)
    public List<RunResponse> log(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        AutomationRule rule = rules.findById(id).orElseThrow(() -> ApiException.notFound("Rule not found."));
        access.requireMember(rule.getProject(), user);
        return runs.findByRuleIdOrderByIdDesc(id, PageRequest.of(0, AutomationService.LOG_SIZE)).stream()
                .map(r -> new RunResponse(r.getId(), r.getTaskId(), r.getTaskKey(), r.getRanAt(), r.isSuccess(), r.getMessage()))
                .toList();
    }

    private AutomationRule editable(Long id, User user) {
        AutomationRule rule = rules.findById(id).orElseThrow(() -> ApiException.notFound("Rule not found."));
        access.requireEditor(rule.getProject(), user);
        return rule;
    }

    private void apply(AutomationRule rule, RuleRequest request, User user) {
        String condition = trim(request.condition());
        if (request.trigger() == AutomationRule.Trigger.SCHEDULED && condition.isEmpty()) {
            throw ApiException.field("condition", "Scheduled rules need a condition, e.g. status = todo AND updated < -30d.");
        }
        if (!condition.isEmpty()) {
            try {
                search.run(user, "project = " + rule.getProject().getKey() + " AND (" + condition + ")", 1);
            } catch (Fql.FqlException e) {
                throw ApiException.field("condition", e.getMessage());
            }
        }
        JsonNode actions = request.actions();
        if (!actions.isArray() || actions.isEmpty()) {
            throw ApiException.field("actions", "Add at least one action.");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw ApiException.field("actions", "A rule can have at most " + MAX_ACTIONS + " actions.");
        }
        for (JsonNode action : actions) {
            validate(action);
        }
        rule.setName(request.name().trim());
        rule.setTrigger(request.trigger());
        rule.setTriggerStatus(request.trigger() == AutomationRule.Trigger.STATUS_CHANGED ? request.triggerStatus() : null);
        rule.setCondition(condition);
        rule.setActions(actions.toString());
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
    }

    private static void validate(JsonNode action) {
        String type = action.path("type").asText("");
        String value = action.path("value").asText("").trim();
        if (!ACTIONS.contains(type)) {
            throw ApiException.field("actions", "Unknown action “" + type + "”.");
        }
        try {
            switch (type) {
                case "set_status" -> TaskStatus.valueOf(value);
                case "set_priority" -> TaskPriority.valueOf(value);
                case "set_due_in_days" -> {
                    int days = Integer.parseInt(value);
                    if (days < 0 || days > 365) {
                        throw new IllegalArgumentException();
                    }
                }
                case "add_label", "remove_label" -> {
                    if (!value.matches("^[^,;|]{1,30}$")) {
                        throw new IllegalArgumentException();
                    }
                }
                case "assign", "notify" -> {
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "comment" -> {
                    if (value.isEmpty() || value.length() > 2000) {
                        throw new IllegalArgumentException();
                    }
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.field("actions", "“" + value + "” is not a valid value for " + type.replace('_', ' ') + ".");
        }
    }

    private RuleResponse response(AutomationRule rule, User user) {
        JsonNode actions;
        try {
            actions = json.readTree(rule.getActions());
        } catch (Exception e) {
            actions = json.createArrayNode();
        }
        return new RuleResponse(rule.getId(), rule.getName(), rule.isEnabled(), rule.getTrigger(), rule.getTriggerStatus(),
                rule.getCondition(), actions, UserSummary.of(rule.getOwner()), rule.getLastRunAt(), rule.getRunCount(),
                rule.getLastError(), rule.getOwner().getId().equals(user.getId()));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
