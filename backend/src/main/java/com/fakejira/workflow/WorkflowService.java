package com.fakejira.workflow;

import com.fakejira.approval.TaskApproval;
import com.fakejira.approval.TaskApprovalRepository;
import com.fakejira.board.BoardColumn;
import com.fakejira.board.BoardService;
import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.field.CustomField;
import com.fakejira.field.CustomFieldRepository;
import com.fakejira.field.CustomFieldValueRepository;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Resolution;
import com.fakejira.task.Task;
import com.fakejira.task.TaskEvents;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom workflows on top of board columns: which moves are allowed, and what a task needs before it may
 * enter a column. Columns already are the project's statuses (each mapped to one of the four categories).
 */
@Service
@Transactional
public class WorkflowService {

    /** Requirement keys besides field:&lt;id&gt;. */
    public static final Map<String, String> REQUIREMENTS = new LinkedHashMap<>();

    static {
        REQUIREMENTS.put("assignee", "an assignee");
        REQUIREMENTS.put("points", "story points");
        REQUIREMENTS.put("due", "a due date");
        REQUIREMENTS.put("estimate", "a time estimate");
        REQUIREMENTS.put("resolution", "a resolution");
        REQUIREMENTS.put("approval", "all approvals granted");
    }

    public record ColumnRules(Long id, String name, TaskStatus status, List<String> required) {
    }

    public record Transition(Long fromId, Long toId) {
    }

    public record WorkflowResponse(boolean restricted, List<ColumnRules> columns, List<Transition> transitions,
                                   Map<String, String> requirements) {
    }

    public record WorkflowRequest(boolean restricted, List<ColumnRules> columns, List<Transition> transitions) {
    }

    private final WorkflowTransitionRepository transitions;
    private final BoardService board;
    private final ProjectAccess access;
    private final TaskApprovalRepository approvals;
    private final CustomFieldRepository fields;
    private final CustomFieldValueRepository values;
    private final LiveEvents live;

    public WorkflowService(WorkflowTransitionRepository transitions, BoardService board, ProjectAccess access,
                           TaskApprovalRepository approvals, CustomFieldRepository fields,
                           CustomFieldValueRepository values, LiveEvents live) {
        this.transitions = transitions;
        this.board = board;
        this.access = access;
        this.approvals = approvals;
        this.fields = fields;
        this.values = values;
        this.live = live;
    }

    @Transactional
    public WorkflowResponse get(User user, String key) {
        return response(access.memberProject(key, user));
    }

    public WorkflowResponse save(User user, String key, WorkflowRequest request) {
        Project project = access.editorProject(key, user);
        List<BoardColumn> columns = board.ensureDefaults(project);
        Map<Long, BoardColumn> byId = new LinkedHashMap<>();
        columns.forEach(c -> byId.put(c.getId(), c));
        Set<Long> projectFields = new LinkedHashSet<>();
        fields.findByProjectIdOrderByPositionAscIdAsc(project.getId()).forEach(f -> projectFields.add(f.getId()));
        if (request.columns() != null) {
            for (ColumnRules rules : request.columns()) {
                BoardColumn column = byId.get(rules.id());
                if (column == null) {
                    throw ApiException.badRequest("That column does not belong to " + project.getKey() + ".");
                }
                Set<String> required = new LinkedHashSet<>();
                for (String r : rules.required() == null ? List.<String>of() : rules.required()) {
                    String clean = r == null ? "" : r.trim().toLowerCase(java.util.Locale.ROOT);
                    if (clean.startsWith("field:")) {
                        Long fieldId = parse(clean.substring(6));
                        if (fieldId == null || !projectFields.contains(fieldId)) {
                            throw ApiException.badRequest("Unknown custom field in the requirements of " + column.getName() + ".");
                        }
                    } else if (!REQUIREMENTS.containsKey(clean)) {
                        throw ApiException.badRequest("Unknown requirement '" + r + "'.");
                    }
                    if (clean.equals("resolution") && column.getStatus() != TaskStatus.DONE) {
                        throw ApiException.badRequest("Only Done columns can require a resolution.");
                    }
                    required.add(clean);
                }
                column.setRequiredFields(required);
            }
        }
        transitions.deleteForProject(project.getId());
        transitions.flush();
        Set<String> seen = new LinkedHashSet<>();
        for (Transition t : request.transitions() == null ? List.<Transition>of() : request.transitions()) {
            BoardColumn to = byId.get(t.toId());
            BoardColumn from = t.fromId() == null ? null : byId.get(t.fromId());
            if (to == null || (t.fromId() != null && from == null)) {
                throw ApiException.badRequest("A transition refers to a column outside " + project.getKey() + ".");
            }
            if (from != null && from.getId().equals(to.getId())) {
                continue;
            }
            if (seen.add(t.fromId() + ">" + t.toId())) {
                transitions.save(new WorkflowTransition(project, from, to));
            }
        }
        if (request.restricted() && seen.isEmpty()) {
            throw ApiException.badRequest("Add at least one transition before restricting moves.");
        }
        project.setRestrictTransitions(request.restricted());
        live.projectChanged(project);
        return response(project);
    }

    /** The column a task currently shows in. */
    public BoardColumn currentColumn(Task task) {
        if (task.getBoardColumn() != null) {
            return task.getBoardColumn();
        }
        return board.ensureDefaults(task.getProject()).stream().filter(c -> c.getStatus() == task.getStatus())
                .findFirst().orElse(null);
    }

    /** The first column of a status (where a plain status change lands). */
    public BoardColumn firstColumn(Project project, TaskStatus status) {
        return board.ensureDefaults(project).stream().filter(c -> c.getStatus() == status).findFirst().orElse(null);
    }

    /**
     * Rejects a move that the workflow does not allow or whose target column's requirements are not met.
     * Automation rules are trusted (they were configured by an editor) and only skip the checks.
     */
    public void check(Task task, BoardColumn to, Resolution resolution) {
        if (to == null || TaskEvents.currentRule() != null) {
            return;
        }
        BoardColumn from = currentColumn(task);
        if (from != null && from.getId().equals(to.getId())) {
            return;
        }
        Project project = task.getProject();
        if (project.isRestrictTransitions()) {
            List<WorkflowTransition> all = transitions.findByProjectIdOrderByIdAsc(project.getId());
            boolean allowed = all.stream().anyMatch(t -> t.getTo().getId().equals(to.getId())
                    && (t.getFrom() == null || from != null && t.getFrom().getId().equals(from.getId())));
            if (!allowed) {
                List<String> options = all.stream()
                        .filter(t -> t.getFrom() == null || from != null && t.getFrom().getId().equals(from.getId()))
                        .map(t -> t.getTo().getName()).distinct().toList();
                throw new ApiException(HttpStatus.BAD_REQUEST, "The workflow does not allow moving from "
                        + (from == null ? "here" : from.getName()) + " to " + to.getName() + "."
                        + (options.isEmpty() ? "" : " Allowed: " + String.join(", ", options) + "."),
                        Map.of("transition", String.join(",", options)));
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String requirement : to.getRequiredFields()) {
            boolean ok = switch (requirement) {
                case "assignee" -> task.getAssignee() != null;
                case "points" -> task.getStoryPoints() != null;
                case "due" -> task.getDueDate() != null;
                case "estimate" -> task.getEstimateMinutes() != null;
                case "resolution" -> resolution != null;
                case "approval" -> approved(task);
                default -> requirement.startsWith("field:") && fieldSet(task, parse(requirement.substring(6)));
            };
            if (!ok) {
                missing.add(requirement);
                labels.add(label(requirement));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Before moving to " + to.getName() + ", the task needs "
                    + String.join(", ", labels) + ".", Map.of("required", String.join(",", missing),
                    "column", to.getName()));
        }
    }

    private boolean approved(Task task) {
        List<TaskApproval> list = approvals.findByTaskIdOrderByCreatedAtAsc(task.getId());
        return !list.isEmpty() && list.stream().allMatch(a -> a.getState() == TaskApproval.State.APPROVED);
    }

    private boolean fieldSet(Task task, Long fieldId) {
        return fieldId != null && values.findByTaskIdAndFieldId(task.getId(), fieldId)
                .map(v -> v.getValue() != null && !v.getValue().isBlank()).orElse(false);
    }

    private String label(String requirement) {
        if (requirement.startsWith("field:")) {
            Long id = parse(requirement.substring(6));
            return id == null ? requirement : fields.findById(id).map(CustomField::getName).orElse("a custom field");
        }
        return REQUIREMENTS.getOrDefault(requirement, requirement);
    }

    private WorkflowResponse response(Project project) {
        List<ColumnRules> columns = board.ensureDefaults(project).stream()
                .map(c -> new ColumnRules(c.getId(), c.getName(), c.getStatus(), c.getRequiredFields())).toList();
        List<Transition> list = transitions.findByProjectIdOrderByIdAsc(project.getId()).stream()
                .map(t -> new Transition(t.getFrom() == null ? null : t.getFrom().getId(), t.getTo().getId())).toList();
        return new WorkflowResponse(project.isRestrictTransitions(), columns, list, REQUIREMENTS);
    }

    private static Long parse(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void columnDeleted(Long columnId) {
        transitions.deleteForColumn(columnId);
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        transitions.deleteForProject(event.projectId());
    }
}
