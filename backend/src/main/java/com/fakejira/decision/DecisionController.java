package com.fakejira.decision;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Polls on tasks and the project's decision log. Guests may vote, like they may comment. */
@RestController
@Transactional
public class DecisionController {

    private final PollRepository polls;
    private final PollVoteRepository votes;
    private final DecisionRepository decisions;
    private final TaskSupport taskSupport;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public DecisionController(PollRepository polls, PollVoteRepository votes, DecisionRepository decisions,
                              TaskSupport taskSupport, ProjectAccess access, CurrentUser currentUser, LiveEvents live) {
        this.polls = polls;
        this.votes = votes;
        this.decisions = decisions;
        this.taskSupport = taskSupport;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record PollRequest(@NotBlank(message = "Ask a question") @Size(max = 200) String question,
                              @NotNull @Size(min = 2, max = 10, message = "Give 2 to 10 options")
                              List<@NotBlank @Size(max = 100, message = "Options are at most 100 characters") String> options,
                              boolean multiple) {
    }

    public record VoteRequest(@NotNull @Size(max = 10) List<Integer> options) {
    }

    public record CloseRequest(boolean recordDecision) {
    }

    public record Option(String text, int votes, List<String> voters, boolean mine) {
    }

    public record PollResponse(Long id, String question, boolean multiple, List<Option> options, int voters,
                               UserSummary createdBy, Instant createdAt, Instant closedAt, boolean canClose) {
    }

    public record DecisionRequest(@NotBlank(message = "What was decided?") @Size(max = 500) String text,
                                  @Size(max = 2000) String context, Long taskId) {
    }

    public record DecisionResponse(Long id, String text, String context, TaskRef task, UserSummary decidedBy,
                                   Instant decidedAt) {
        static DecisionResponse of(Decision d) {
            return new DecisionResponse(d.getId(), d.getText(), d.getContext(), TaskRef.of(d.getTask()),
                    UserSummary.of(d.getDecidedBy()), d.getDecidedAt());
        }
    }

    // ---------------------------------------------------------------- polls

    @GetMapping("/api/tasks/{id}/polls")
    @Transactional(readOnly = true)
    public List<PollResponse> polls(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        return polls.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream().map(p -> response(p, user)).toList();
    }

    @PostMapping("/api/tasks/{id}/polls")
    @ResponseStatus(HttpStatus.CREATED)
    public PollResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody PollRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        if (polls.findByTaskIdOrderByCreatedAtAsc(id).size() >= 10) {
            throw ApiException.badRequest("A task can have at most 10 polls.");
        }
        List<String> options = request.options().stream().map(String::trim).toList();
        if (new LinkedHashSet<>(options.stream().map(String::toLowerCase).toList()).size() != options.size()) {
            throw ApiException.field("options", "Options must be different from each other.");
        }
        Poll poll = polls.save(new Poll(task, request.question().trim(), options, request.multiple(), user));
        taskSupport.record(task, user, "started a poll: " + poll.getQuestion());
        taskSupport.notifyParticipants(task, user, "started a poll on");
        live.taskChanged(task);
        return response(poll, user);
    }

    @PostMapping("/api/polls/{id}/vote")
    public PollResponse vote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody VoteRequest request) {
        User user = currentUser.from(jwt);
        Poll poll = poll(id);
        taskSupport.commentableTask(poll.getTask().getId(), user);
        if (poll.getClosedAt() != null) {
            throw ApiException.badRequest("This poll is closed.");
        }
        Set<Integer> picked = new LinkedHashSet<>(request.options());
        if (!poll.isMultiple() && picked.size() > 1) {
            throw ApiException.badRequest("Pick one option.");
        }
        for (int option : picked) {
            if (option < 0 || option >= poll.getOptions().size()) {
                throw ApiException.badRequest("That option does not exist.");
            }
        }
        votes.deleteMine(poll.getId(), user.getId());
        votes.flush();
        picked.forEach(option -> votes.save(new PollVote(poll, user, option)));
        live.taskChanged(poll.getTask());
        return response(poll, user);
    }

    /** Closing freezes the result; optionally the winner goes into the decision log. */
    @PostMapping("/api/polls/{id}/close")
    public PollResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @RequestBody CloseRequest request) {
        User user = currentUser.from(jwt);
        Poll poll = poll(id);
        Task task = taskSupport.editableTask(poll.getTask().getId(), user);
        if (!canClose(poll, user)) {
            throw ApiException.forbidden("Only whoever started the poll, or the project owner, can close it.");
        }
        if (poll.getClosedAt() == null) {
            poll.close();
            PollResponse result = response(poll, user);
            int top = result.options().stream().mapToInt(Option::votes).max().orElse(0);
            List<String> winners = result.options().stream().filter(o -> o.votes() == top && top > 0).map(Option::text).toList();
            taskSupport.record(task, user, "closed the poll “" + poll.getQuestion() + "”"
                    + (winners.isEmpty() ? " without votes" : ": " + String.join(" / ", winners)));
            if (request.recordDecision() && !winners.isEmpty()) {
                decisions.save(new Decision(task.getProject(), task, poll.getQuestion() + " → " + String.join(" / ", winners),
                        "Poll result: " + String.join(", ", result.options().stream().map(o -> o.text() + " " + o.votes()).toList()),
                        user));
            }
            live.taskChanged(task);
        }
        return response(poll, user);
    }

    @DeleteMapping("/api/polls/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePoll(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Poll poll = poll(id);
        taskSupport.editableTask(poll.getTask().getId(), user);
        if (!canClose(poll, user)) {
            throw ApiException.forbidden("Only whoever started the poll, or the project owner, can delete it.");
        }
        votes.deleteForPoll(poll.getId());
        polls.delete(poll);
        live.taskChanged(poll.getTask());
    }

    // ---------------------------------------------------------------- decisions

    @GetMapping("/api/projects/{key}/decisions")
    @Transactional(readOnly = true)
    public List<DecisionResponse> decisions(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return decisions.findByProjectIdOrderByDecidedAtDesc(project.getId()).stream().map(DecisionResponse::of).toList();
    }

    @GetMapping("/api/tasks/{id}/decisions")
    @Transactional(readOnly = true)
    public List<DecisionResponse> taskDecisions(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        taskSupport.memberTask(id, currentUser.from(jwt));
        return decisions.findByTaskIdOrderByDecidedAtDesc(id).stream().map(DecisionResponse::of).toList();
    }

    @PostMapping("/api/projects/{key}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionResponse record(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody DecisionRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        Task task = null;
        if (request.taskId() != null) {
            task = taskSupport.editableTask(request.taskId(), user);
            if (!task.getProject().getId().equals(project.getId())) {
                throw ApiException.badRequest("That task is not in " + project.getKey() + ".");
            }
            taskSupport.record(task, user, "recorded a decision: " + request.text().trim());
            live.taskChanged(task);
        }
        Decision decision = decisions.save(new Decision(project, task, request.text().trim(),
                request.context() == null ? "" : request.context().trim(), user));
        live.projectChanged(project);
        return DecisionResponse.of(decision);
    }

    @DeleteMapping("/api/decisions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDecision(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Decision decision = decisions.findById(id).orElseThrow(() -> ApiException.notFound("Decision not found."));
        access.requireEditor(decision.getProject(), user);
        if (!decision.getDecidedBy().getId().equals(user.getId()) && !decision.getProject().isOwner(user)) {
            throw ApiException.forbidden("Only whoever recorded it, or the project owner, can remove a decision.");
        }
        decisions.delete(decision);
        live.projectChanged(decision.getProject());
    }

    private PollResponse response(Poll poll, User user) {
        List<PollVote> all = votes.findForPoll(poll.getId());
        List<Option> options = new ArrayList<>();
        List<String> texts = poll.getOptions();
        for (int i = 0; i < texts.size(); i++) {
            int index = i;
            List<PollVote> forOption = all.stream().filter(v -> v.getOption() == index).toList();
            options.add(new Option(texts.get(i), forOption.size(),
                    forOption.stream().map(v -> v.getUser().getName()).toList(),
                    forOption.stream().anyMatch(v -> v.getUser().getId().equals(user.getId()))));
        }
        int voters = (int) all.stream().map(v -> v.getUser().getId()).distinct().count();
        return new PollResponse(poll.getId(), poll.getQuestion(), poll.isMultiple(), options, voters,
                UserSummary.of(poll.getCreatedBy()), poll.getCreatedAt(), poll.getClosedAt(), canClose(poll, user));
    }

    private static boolean canClose(Poll poll, User user) {
        return poll.getCreatedBy().getId().equals(user.getId()) || poll.getTask().getProject().isOwner(user);
    }

    private Poll poll(Long id) {
        return polls.findById(id).orElseThrow(() -> ApiException.notFound("Poll not found."));
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        for (Poll poll : polls.findByTaskIdOrderByCreatedAtAsc(event.taskId())) {
            votes.deleteForPoll(poll.getId());
            polls.delete(poll);
        }
        decisions.detachTask(event.taskId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        decisions.deleteForProject(event.projectId());
    }
}
