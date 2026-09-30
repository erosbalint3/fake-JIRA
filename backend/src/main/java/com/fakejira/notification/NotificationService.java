package com.fakejira.notification;

import com.fakejira.events.LiveEvent;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.user.EmailFrequency;
import com.fakejira.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class NotificationService {

    /** Notifications about the recipient ("assigned you", "your comment") pass the "direct only" rule. */
    private static final Pattern DIRECT = Pattern.compile("\\byou(r)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DIRECT_ACTIONS = Set.of("approved", "rejected", "accepted", "released");

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final TaskRepository tasks;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository notifications, NotificationPreferenceRepository preferences,
                               TaskRepository tasks, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.tasks = tasks;
        this.events = events;
    }

    /**
     * Notifies {@code recipient} about something {@code actor} did to {@code task}.
     * Nobody is notified about their own actions.
     */
    public void notify(User recipient, User actor, Task task, String action) {
        boolean direct = DIRECT.matcher(action).find() || DIRECT_ACTIONS.contains(action);
        deliver(recipient, actor, actor.getUsername() + " " + action + " " + task.getKey() + " · " + task.getTitle(),
                task.getId(), task.getProject().getId(), direct);
    }

    public void notify(User recipient, User actor, String message, Long taskId) {
        Long projectId = taskId == null ? null
                : tasks.findById(taskId).map(task -> task.getProject().getId()).orElse(null);
        deliver(recipient, actor, message, taskId, projectId, DIRECT.matcher(message).find());
    }

    /** A notification the user set up for themselves (a reminder): always delivered, even from muted projects. */
    public void notifySelf(User user, String message, Long taskId) {
        store(user, message, taskId, new Rules(NotificationLevel.ALL,
                user.getEmailFrequency() == EmailFrequency.INSTANT, true));
    }

    private void deliver(User recipient, User actor, String message, Long taskId, Long projectId, boolean direct) {
        if (recipient == null || recipient.getId().equals(actor.getId())) {
            return;
        }
        Rules rules = rules(recipient, projectId);
        if (rules.level() == NotificationLevel.MUTED || rules.level() == NotificationLevel.DIRECT && !direct) {
            return;
        }
        store(recipient, message, taskId, rules);
    }

    private void store(User recipient, String message, Long taskId, Rules rules) {
        if (message.length() > 300) {
            message = message.substring(0, 297) + "...";
        }
        notifications.save(new Notification(recipient, message, taskId));
        events.publishEvent(new LiveEvent(Set.of(recipient.getId()), "notification", Map.of()));
        boolean quiet = recipient.isQuietAt(Instant.now());
        events.publishEvent(new NotificationCreated(recipient.getId(), recipient.getEmail(),
                rules.email() && !quiet, message, taskId, rules.push() && !quiet));
    }

    /** The effective rule: the project's settings, falling back to the user's defaults. */
    public Rules rules(User user, Long projectId) {
        NotificationPreference global = preferences.findByUserIdAndProjectIdIsNull(user.getId()).orElse(null);
        NotificationPreference project = projectId == null ? null
                : preferences.findByUserIdAndProjectId(user.getId(), projectId).orElse(null);
        NotificationLevel level = project != null ? project.getLevel()
                : global != null ? global.getLevel() : NotificationLevel.ALL;
        Boolean email = project != null && project.getEmail() != null ? project.getEmail()
                : global != null ? global.getEmail() : null;
        Boolean push = project != null && project.getPush() != null ? project.getPush()
                : global != null ? global.getPush() : null;
        return new Rules(level,
                email != null ? email : user.getEmailFrequency() == EmailFrequency.INSTANT,
                push == null || push);
    }

    public record Rules(NotificationLevel level, boolean email, boolean push) {
    }
}
