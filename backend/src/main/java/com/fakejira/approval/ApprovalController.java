package com.fakejira.approval;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.MemberRemoved;
import com.fakejira.project.Project;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Approvals on tasks: anyone who can edit asks a member; only that member decides. */
@RestController
@Transactional
public class ApprovalController {

    private final TaskApprovalRepository approvals;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final NotificationService notifications;
    private final LiveEvents live;

    public ApprovalController(TaskApprovalRepository approvals, TaskSupport taskSupport, CurrentUser currentUser,
                              NotificationService notifications, LiveEvents live) {
        this.approvals = approvals;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.notifications = notifications;
        this.live = live;
    }

    public record ApprovalRequest(@NotNull(message = "Choose who should approve") Long approverId,
                                  @Size(max = 500, message = "At most 500 characters") String note) {
    }

    public record DecisionRequest(boolean approve, @Size(max = 500, message = "At most 500 characters") String note) {
    }

    public record ApprovalResponse(Long id, UserSummary approver, UserSummary requestedBy, TaskApproval.State state,
                                   String request, String decisionNote, Instant createdAt, Instant decidedAt,
                                   boolean canDecide, TaskRef task) {
        static ApprovalResponse of(TaskApproval a, User viewer) {
            return new ApprovalResponse(a.getId(), UserSummary.of(a.getApprover()), UserSummary.of(a.getRequestedBy()),
                    a.getState(), a.getRequest(), a.getDecisionNote(), a.getCreatedAt(), a.getDecidedAt(),
                    a.getApprover().getId().equals(viewer.getId()), TaskRef.of(a.getTask()));
        }
    }

    @GetMapping("/api/tasks/{id}/approvals")
    @Transactional(readOnly = true)
    public List<ApprovalResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        taskSupport.memberTask(id, user);
        return approvals.findByTaskIdOrderByCreatedAtAsc(id).stream().map(a -> ApprovalResponse.of(a, user)).toList();
    }

    @PostMapping("/api/tasks/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalResponse request(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody ApprovalRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        Project project = task.getProject();
        User approver = project.getMembers().stream().filter(m -> m.getId().equals(request.approverId())).findFirst()
                .orElseThrow(() -> ApiException.field("approverId", "The approver must be a member of " + project.getKey() + "."));
        List<TaskApproval> existing = approvals.findByTaskIdOrderByCreatedAtAsc(id);
        TaskApproval approval = existing.stream().filter(a -> a.getApprover().getId().equals(approver.getId()))
                .findFirst().orElse(null);
        if (approval != null && approval.getState() == TaskApproval.State.PENDING) {
            throw ApiException.conflict(approver.getUsername() + " has already been asked.");
        }
        if (approval == null) {
            if (existing.size() >= 10) {
                throw ApiException.badRequest("A task can have at most 10 approvals.");
            }
            approval = approvals.save(new TaskApproval(task, approver, user, clean(request.note())));
        } else {
            approval.reopen();
        }
        taskSupport.record(task, user, "asked " + approver.getUsername() + " for approval");
        notifications.notify(approver, user, task, "asked for your approval on");
        task.touch();
        live.taskChanged(task);
        return ApprovalResponse.of(approval, user);
    }

    @PostMapping("/api/approvals/{id}/decision")
    public ApprovalResponse decide(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                   @Valid @RequestBody DecisionRequest request) {
        User user = currentUser.from(jwt);
        TaskApproval approval = approvals.findById(id).orElseThrow(() -> ApiException.notFound("Approval not found."));
        Task task = taskSupport.memberTask(approval.getTask().getId(), user);
        if (!approval.getApprover().getId().equals(user.getId())) {
            throw ApiException.forbidden("Only " + approval.getApprover().getUsername() + " can decide this approval.");
        }
        approval.decide(request.approve(), clean(request.note()));
        taskSupport.record(task, user, (request.approve() ? "approved" : "rejected") + " the task"
                + (approval.getDecisionNote() == null ? "" : ": " + approval.getDecisionNote()));
        notifications.notify(approval.getRequestedBy(), user, task, request.approve() ? "approved" : "rejected");
        task.touch();
        live.taskChanged(task);
        return ApprovalResponse.of(approval, user);
    }

    @DeleteMapping("/api/approvals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        TaskApproval approval = approvals.findById(id).orElseThrow(() -> ApiException.notFound("Approval not found."));
        Task task = taskSupport.editableTask(approval.getTask().getId(), user);
        if (!approval.getRequestedBy().getId().equals(user.getId()) && !task.getProject().isOwner(user)) {
            throw ApiException.forbidden("Only whoever asked, or the project owner, can withdraw an approval.");
        }
        approvals.delete(approval);
        taskSupport.record(task, user, "withdrew the approval request to " + approval.getApprover().getUsername());
        task.touch();
        live.taskChanged(task);
    }

    /** Approvals waiting for the current user. */
    @GetMapping("/api/approvals/mine")
    @Transactional(readOnly = true)
    public List<ApprovalResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return approvals.pendingFor(user.getId()).stream()
                .filter(a -> a.getTask().getProject().hasMember(user))
                .map(a -> ApprovalResponse.of(a, user)).toList();
    }

    private static String clean(String note) {
        return note == null ? "" : note.trim();
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        approvals.deleteForTask(event.taskId());
    }

    @EventListener
    public void onMemberRemoved(MemberRemoved event) {
        approvals.deletePendingFor(event.projectId(), event.userId());
    }
}
