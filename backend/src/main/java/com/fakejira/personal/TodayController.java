package com.fakejira.personal;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvent;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Today list: the tasks someone plans to work on today, with suggestions and an end-of-day summary. */
@RestController
@RequestMapping("/api/today")
@Transactional
public class TodayController {

    static final int MAX_PICKS = 50;

    private final TodayPickRepository picks;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;

    public TodayController(TodayPickRepository picks, TaskSupport taskSupport, CurrentUser currentUser,
                           ApplicationEventPublisher events) {
        this.picks = picks;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.events = events;
    }

    /** {@code carryOver}: unfinished picks from the last day you planned, not yet on this day. */
    public record TodayResponse(LocalDate date, List<TaskResponse> picks, List<TaskResponse> suggestions,
                                LocalDate previousDay, List<TaskResponse> carryOver) {
    }

    public record PickRequest(@NotNull(message = "Choose a task") Long taskId, LocalDate date) {
    }

    public record OrderRequest(@NotNull @Size(max = MAX_PICKS) List<Long> taskIds, LocalDate date) {
    }

    public record Summary(LocalDate date, List<TaskResponse> completed, List<TaskResponse> unfinished,
                          long minutesLogged, long comments, String text) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public TodayResponse today(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate date) {
        User user = currentUser.from(jwt);
        LocalDate day = day(user, date);
        List<Task> picked = visible(user, picks.forDay(user.getId(), day));
        Set<Long> pickedIds = ids(picked);
        List<Task> suggested = picks.suggestions(user.getId(), day.plusDays(1), TaskStatus.DONE, TaskStatus.IN_PROGRESS,
                        Pageable.ofSize(20)).stream()
                .filter(t -> !pickedIds.contains(t.getId())).limit(10).toList();
        LocalDate previous = picks.previousDay(user.getId(), day);
        List<Task> carry = previous == null ? List.of() : visible(user, picks.forDay(user.getId(), previous)).stream()
                .filter(t -> t.getStatus() != TaskStatus.DONE && !t.isArchived() && !pickedIds.contains(t.getId()))
                .toList();
        return new TodayResponse(day, taskSupport.responses(picked),
                taskSupport.responses(suggested.stream().filter(t -> carry.stream().noneMatch(c -> c.getId().equals(t.getId()))).toList()),
                carry.isEmpty() ? null : previous, taskSupport.responses(carry));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodayResponse add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PickRequest request) {
        User user = currentUser.from(jwt);
        LocalDate day = day(user, request.date());
        Task task = taskSupport.memberTask(request.taskId(), user);
        if (picks.findByUserIdAndTaskIdAndDay(user.getId(), task.getId(), day).isEmpty()) {
            List<TodayPick> current = picks.forDay(user.getId(), day);
            if (current.size() >= MAX_PICKS) {
                throw ApiException.badRequest("Your day already has " + MAX_PICKS + " tasks.");
            }
            int next = current.stream().mapToInt(TodayPick::getPosition).max().orElse(-1) + 1;
            picks.save(new TodayPick(user, task, day, next));
            changed(user);
        }
        return today(jwt, day);
    }

    /** Adds every unfinished task from the previous planned day. */
    @PostMapping("/carry-over")
    public TodayResponse carryOver(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate date) {
        User user = currentUser.from(jwt);
        LocalDate day = day(user, date);
        TodayResponse current = today(jwt, day);
        int position = current.picks().size();
        for (TaskResponse task : current.carryOver()) {
            if (position >= MAX_PICKS) {
                break;
            }
            picks.save(new TodayPick(user, taskSupport.memberTask(task.id(), user), day, position++));
        }
        changed(user);
        return today(jwt, day);
    }

    @DeleteMapping("/{taskId}")
    public TodayResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable Long taskId,
                                @RequestParam(required = false) LocalDate date) {
        User user = currentUser.from(jwt);
        LocalDate day = day(user, date);
        picks.findByUserIdAndTaskIdAndDay(user.getId(), taskId, day).ifPresent(picks::delete);
        changed(user);
        return today(jwt, day);
    }

    @PutMapping("/order")
    public TodayResponse reorder(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OrderRequest request) {
        User user = currentUser.from(jwt);
        LocalDate day = day(user, request.date());
        List<TodayPick> current = picks.forDay(user.getId(), day);
        for (TodayPick pick : current) {
            int index = request.taskIds().indexOf(pick.getTask().getId());
            pick.setPosition(index < 0 ? request.taskIds().size() + pick.getPosition() : index);
        }
        changed(user);
        return today(jwt, day);
    }

    /** What got done: finished tasks, what is left, time logged and comments, plus a ready-to-share text. */
    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public Summary summary(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate date) {
        User user = currentUser.from(jwt);
        LocalDate day = day(user, date);
        ZoneId zone = user.zone();
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        List<Task> completed = new ArrayList<>(picks.completedBetween(user.getId(), from, to).stream()
                .filter(t -> t.getProject().hasMember(user)).toList());
        Set<Long> completedIds = ids(completed);
        for (Task t : visible(user, picks.forDay(user.getId(), day))) {
            if (t.getStatus() == TaskStatus.DONE && !completedIds.contains(t.getId()) && t.getCompletedAt() != null
                    && !t.getCompletedAt().isBefore(from) && t.getCompletedAt().isBefore(to)) {
                completed.add(t);
                completedIds.add(t.getId());
            }
        }
        List<Task> unfinished = visible(user, picks.forDay(user.getId(), day)).stream()
                .filter(t -> t.getStatus() != TaskStatus.DONE).toList();
        long minutes = picks.minutesLogged(user.getId(), day);
        long comments = picks.commentsBetween(user.getId(), from, to);
        StringBuilder text = new StringBuilder("**" + day + "**\n");
        text.append("\nDone:\n");
        if (completed.isEmpty()) {
            text.append("- nothing finished yet\n");
        }
        completed.forEach(t -> text.append("- ").append(t.getKey()).append(" ").append(t.getTitle()).append('\n'));
        if (!unfinished.isEmpty()) {
            text.append("\nStill open:\n");
            unfinished.forEach(t -> text.append("- ").append(t.getKey()).append(" ").append(t.getTitle())
                    .append(" (").append(t.getStatus().name().toLowerCase().replace('_', ' ')).append(")\n"));
        }
        text.append("\nTime logged: ").append(minutes / 60).append("h ").append(minutes % 60).append("m · ")
                .append(comments).append(comments == 1 ? " comment" : " comments");
        return new Summary(day, taskSupport.responses(completed), taskSupport.responses(unfinished), minutes, comments,
                text.toString());
    }

    private static LocalDate day(User user, LocalDate date) {
        return date != null ? date : LocalDate.now(user.zone());
    }

    private static List<Task> visible(User user, List<TodayPick> list) {
        return list.stream().map(TodayPick::getTask).filter(t -> t.getProject().hasMember(user)).toList();
    }

    private static Set<Long> ids(List<Task> list) {
        Set<Long> ids = new HashSet<>();
        list.forEach(t -> ids.add(t.getId()));
        return ids;
    }

    private void changed(User user) {
        events.publishEvent(new LiveEvent(Set.of(user.getId()), "today", Map.of()));
    }
}
