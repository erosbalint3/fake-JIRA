package com.fakejira.personal;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvent;
import com.fakejira.notification.NotificationService;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDetailsService;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskDtos.TimeEntryResponse;
import com.fakejira.task.TaskDtos.TimeRequest;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** A personal stopwatch: start it on a task, stop it to log the time. */
@RestController
@RequestMapping("/api/timer")
@Transactional
public class TimerController {

    /** Timers running this long get a "still running?" reminder. */
    static final Duration LONG_RUNNING = Duration.ofHours(8);

    private final RunningTimerRepository timers;
    private final TaskSupport taskSupport;
    private final TaskDetailsService details;
    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;

    public TimerController(RunningTimerRepository timers, TaskSupport taskSupport, TaskDetailsService details,
                           NotificationService notifications, CurrentUser currentUser, ApplicationEventPublisher events) {
        this.timers = timers;
        this.taskSupport = taskSupport;
        this.details = details;
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.events = events;
    }

    public record TimerResponse(TaskRef task, String projectKey, Instant startedAt, long elapsedSeconds) {
        static TimerResponse of(RunningTimer t) {
            return new TimerResponse(TaskRef.of(t.getTask()), t.getTask().getProject().getKey(), t.getStartedAt(),
                    Duration.between(t.getStartedAt(), Instant.now()).getSeconds());
        }
    }

    public record StartRequest(@NotNull(message = "Choose a task") Long taskId) {
    }

    /** {@code minutes} overrides the measured time (e.g. to drop time spent away); null logs it all. */
    public record StopRequest(@Min(value = 0, message = "Minutes cannot be negative")
                              @Max(value = 1440, message = "At most 24 hours per entry") Integer minutes,
                              @Size(max = 200, message = "Note must be at most 200 characters") String note) {
    }

    public record StopResponse(TimeEntryResponse logged, TimerResponse running) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<TimerResponse> current(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return timers.findByUserId(user.getId()).map(TimerResponse::of).map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /** Starts a timer; one already running on another task is stopped and its time logged first. */
    @PostMapping("/start")
    public StopResponse start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(request.taskId(), user);
        RunningTimer existing = timers.findByUserId(user.getId()).orElse(null);
        TimeEntryResponse logged = null;
        if (existing != null) {
            if (existing.getTask().getId().equals(task.getId())) {
                return new StopResponse(null, TimerResponse.of(existing));
            }
            logged = logAndRemove(user, existing, null, null);
            timers.flush();
        }
        RunningTimer started = timers.save(new RunningTimer(user, task, Instant.now()));
        changed(user);
        return new StopResponse(logged, TimerResponse.of(started));
    }

    @PostMapping("/stop")
    public StopResponse stop(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody(required = false) StopRequest request) {
        User user = currentUser.from(jwt);
        RunningTimer timer = timers.findByUserId(user.getId())
                .orElseThrow(() -> ApiException.notFound("No timer is running."));
        TimeEntryResponse logged = logAndRemove(user, timer, request == null ? null : request.minutes(),
                request == null ? null : request.note());
        changed(user);
        return new StopResponse(logged, null);
    }

    /** Stops the timer without logging anything. */
    @DeleteMapping
    public ResponseEntity<Void> discard(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        timers.findByUserId(user.getId()).ifPresent(timers::delete);
        changed(user);
        return ResponseEntity.noContent().build();
    }

    /** Logs the timer's time (rounded up to whole minutes) unless it comes to zero. */
    private TimeEntryResponse logAndRemove(User user, RunningTimer timer, Integer minutes, String note) {
        long measured = (Duration.between(timer.getStartedAt(), Instant.now()).getSeconds() + 59) / 60;
        int toLog = (int) Math.min(1440, minutes != null ? minutes : Math.max(1, measured));
        Long taskId = timer.getTask().getId();
        timers.delete(timer);
        if (toLog <= 0 || !timer.getTask().getProject().canEdit(user)) {
            return null;
        }
        LocalDate today = LocalDate.now(user.zone());
        return details.logTime(user, taskId, new TimeRequest(toLog, today,
                note == null || note.isBlank() ? "Timer" : note.trim()));
    }

    @Scheduled(fixedDelayString = "${app.timer.check-ms:600000}", initialDelay = 60_000)
    public void remindLongRunning() {
        Instant now = Instant.now();
        for (RunningTimer timer : timers.runningSince(now.minus(LONG_RUNNING))) {
            timer.setRemindedAt(now);
            notifications.notifySelf(timer.getUser(), "⏱ Your timer on " + timer.getTask().getKey() + " · "
                    + timer.getTask().getTitle() + " has been running for over " + LONG_RUNNING.toHours()
                    + " hours. Stop it or adjust the time.", timer.getTask().getId());
        }
    }

    private void changed(User user) {
        events.publishEvent(new LiveEvent(Set.of(user.getId()), "timer", Map.of()));
    }
}
