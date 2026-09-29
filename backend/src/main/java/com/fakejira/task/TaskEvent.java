package com.fakejira.task;

import java.util.Map;

/**
 * Something happened to a task. Published inside the transaction; automation rules and outgoing webhooks react
 * after commit. {@code automated} is true when an automation rule caused it (rules ignore those, so they cannot
 * loop).
 */
public record TaskEvent(Kind kind, Long taskId, Long projectId, Long actorId, Map<String, String> details,
                        boolean automated) {

    public enum Kind {
        CREATED("task.created"),
        UPDATED("task.updated"),
        STATUS_CHANGED("task.status_changed"),
        ASSIGNED("task.assigned"),
        COMMENTED("comment.created"),
        DELETED("task.deleted");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** The name used in webhook payloads. */
        public String wireName() {
            return wireName;
        }
    }
}
