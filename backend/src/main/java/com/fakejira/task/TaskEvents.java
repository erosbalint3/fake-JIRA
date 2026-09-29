package com.fakejira.task;

import com.fakejira.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes {@link TaskEvent}s; knows whether an automation rule is currently acting. */
@Component
public class TaskEvents {

    private static final ThreadLocal<String> RULE = new ThreadLocal<>();

    private final ApplicationEventPublisher publisher;

    public TaskEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(TaskEvent.Kind kind, Task task, User actor, String... keyValues) {
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                details.put(keyValues[i], keyValues[i + 1]);
            }
        }
        details.putIfAbsent("key", task.getKey());
        details.putIfAbsent("title", task.getTitle());
        publisher.publishEvent(new TaskEvent(kind, task.getId(), task.getProject().getId(),
                actor == null ? null : actor.getId(), Map.copyOf(details), RULE.get() != null));
    }

    /** Runs {@code action} as automation rule {@code ruleName}: activity says so and events are marked automated. */
    public static void asRule(String ruleName, Runnable action) {
        String previous = RULE.get();
        RULE.set(ruleName);
        try {
            action.run();
        } finally {
            if (previous == null) {
                RULE.remove();
            } else {
                RULE.set(previous);
            }
        }
    }

    /** The automation rule acting right now, or null. */
    public static String currentRule() {
        return RULE.get();
    }
}
