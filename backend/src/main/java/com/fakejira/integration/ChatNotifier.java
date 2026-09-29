package com.fakejira.integration;

import com.fakejira.sprint.Sprint;
import com.fakejira.task.Task;
import com.fakejira.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Describes project events for chat webhooks. Messages are built here, inside the request's transaction,
 * and sent by {@link ChatSender} after it commits.
 */
@Component
public class ChatNotifier {

    /** A chat update. {@code link} is a path such as /tasks/5; {@code quote} is optional extra text. */
    public record ChatMessage(Long projectId, ChatEventType type, String actor, String verb, String subject,
                              String link, String quote) {
    }

    private final ApplicationEventPublisher publisher;

    public ChatNotifier(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void created(Task task, User actor) {
        publish(task, ChatEventType.TASK_CREATED, actor, "created", null);
    }

    public void statusChanged(Task task, User actor, String from, String to) {
        if ("Done".equals(to)) {
            publish(task, ChatEventType.TASK_DONE, actor, "completed", null);
        }
        publish(task, ChatEventType.STATUS_CHANGED, actor, "moved", from + " → " + to);
    }

    public void commented(Task task, User actor, String body) {
        String excerpt = body.length() > 300 ? body.substring(0, 299) + "…" : body;
        publish(task, ChatEventType.COMMENT_ADDED, actor, "commented on", excerpt);
    }

    public void sprint(Sprint sprint, User actor, String verb, String details) {
        publisher.publishEvent(new ChatMessage(sprint.getProject().getId(), ChatEventType.SPRINT, actor.getName(), verb,
                sprint.getName() + " in " + sprint.getProject().getName(), "/p/" + sprint.getProject().getKey() + "/board",
                details));
    }

    private void publish(Task task, ChatEventType type, User actor, String verb, String quote) {
        publisher.publishEvent(new ChatMessage(task.getProject().getId(), type, actor.getName(), verb,
                task.getKey() + " " + task.getTitle(), "/tasks/" + task.getId(), quote));
    }
}
