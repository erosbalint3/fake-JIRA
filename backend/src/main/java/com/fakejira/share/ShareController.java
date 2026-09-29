package com.fakejira.share;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.task.ChecklistItemRepository;
import com.fakejira.task.CommentRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

/** Read-only public links to single tasks. */
@RestController
public class ShareController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository shares;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final CommentRepository comments;
    private final ChecklistItemRepository checklist;
    private final CurrentUser currentUser;
    private final MailService mail;
    private final AuditLog audit;

    public ShareController(ShareLinkRepository shares, TaskRepository tasks, TaskSupport taskSupport, CommentRepository comments,
                           ChecklistItemRepository checklist, CurrentUser currentUser, MailService mail, AuditLog audit) {
        this.shares = shares;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.comments = comments;
        this.checklist = checklist;
        this.currentUser = currentUser;
        this.mail = mail;
        this.audit = audit;
    }

    public record ShareRequest(boolean includeComments,
                               @Min(value = 1, message = "At least 1 day") @Max(value = 365, message = "At most 365 days") Integer expiresInDays) {
    }

    public record ShareResponse(Long id, String url, boolean includeComments, Instant createdAt, Instant expiresAt,
                                boolean expired, int views, String createdBy) {
    }

    public record PublicComment(String author, String body, Instant createdAt) {
    }

    public record PublicItem(String text, boolean done) {
    }

    public record PublicSubtask(String key, String title, TaskStatus status) {
    }

    public record PublicTask(String key, String title, String description, TaskStatus status, TaskPriority priority,
                             TaskType type, String projectName, String assignee, LocalDate dueDate, List<String> labels,
                             Integer storyPoints, List<PublicItem> checklist, List<PublicSubtask> subtasks,
                             List<PublicComment> comments, Instant updatedAt, Instant sharedUntil) {
    }

    @GetMapping("/api/tasks/{id}/shares")
    @Transactional(readOnly = true)
    public List<ShareResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Task task = taskSupport.editableTask(id, currentUser.from(jwt));
        return shares.findByTaskIdOrderByIdDesc(task.getId()).stream().map(this::response).toList();
    }

    @PostMapping("/api/tasks/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ShareResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                @jakarta.validation.Valid @RequestBody ShareRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        if (shares.findByTaskIdOrderByIdDesc(task.getId()).size() >= 10) {
            throw ApiException.badRequest("A task can have at most 10 share links. Revoke one first.");
        }
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        Instant expires = request.expiresInDays() == null ? null : Instant.now().plus(Duration.ofDays(request.expiresInDays()));
        ShareLink link = shares.save(new ShareLink(task, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), user,
                request.includeComments(), expires));
        taskSupport.record(task, user, "created a public link to this task");
        audit.record(user, "task.share", task.getKey(), request.includeComments() ? "with comments" : "without comments");
        return response(link);
    }

    @DeleteMapping("/api/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        ShareLink link = shares.findById(id).orElseThrow(() -> ApiException.notFound("Link not found."));
        taskSupport.editableTask(link.getTask().getId(), user);
        taskSupport.record(link.getTask(), user, "revoked a public link");
        shares.delete(link);
    }

    /** No sign-in: anyone with the link. */
    @GetMapping("/api/public/share/{token}")
    @Transactional
    public ResponseEntity<PublicTask> view(@PathVariable String token) {
        ShareLink link = token.length() < 20 ? null : shares.findByToken(token).orElse(null);
        if (link == null || link.isExpired()) {
            return ResponseEntity.notFound().build();
        }
        link.viewed();
        Task t = link.getTask();
        List<PublicItem> items = checklist.findByTaskIdOrderByPositionAscIdAsc(t.getId()).stream()
                .map(i -> new PublicItem(i.getText(), i.isDone())).toList();
        List<PublicSubtask> subtasks = tasks.findByParentIdOrderByIdAsc(t.getId()).stream()
                .map(s -> new PublicSubtask(s.getKey(), s.getTitle(), s.getStatus())).toList();
        List<PublicComment> publicComments = !link.isIncludeComments() ? List.of() : comments.findForTask(t.getId()).stream()
                .map(c -> new PublicComment(c.getAuthor().getName(), c.getBody(), c.getCreatedAt())).toList();
        return ResponseEntity.ok()
                .header("X-Robots-Tag", "noindex")
                .header("Cache-Control", "no-store")
                .body(new PublicTask(t.getKey(), t.getTitle(), t.getDescription(), t.getStatus(), t.getPriority(), t.getType(),
                        t.getProject().getName(), t.getAssignee() == null ? null : t.getAssignee().getName(), t.getDueDate(),
                        List.copyOf(t.getLabels()), t.getStoryPoints(), items, subtasks, publicComments, t.getUpdatedAt(),
                        link.getExpiresAt()));
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        shares.deleteForTask(event.taskId());
    }

    private ShareResponse response(ShareLink link) {
        return new ShareResponse(link.getId(), mail.link("/share/" + link.getToken()), link.isIncludeComments(),
                link.getCreatedAt(), link.getExpiresAt(), link.isExpired(), link.getViews(), link.getCreatedBy().getName());
    }
}
