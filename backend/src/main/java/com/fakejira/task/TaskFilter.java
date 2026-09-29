package com.fakejira.task;

/**
 * Optional list filters. {@code sprint} is a sprint id or "backlog" (tasks in no sprint);
 * {@code assignee} is a user id, "me" or "none".
 */
public record TaskFilter(
        String project,
        TaskScope scope,
        String q,
        TaskPriority priority,
        TaskStatus status,
        String label,
        String sprint,
        String assignee,
        /** Epic id or "none". */
        String epic,
        TaskType type) {

    public TaskFilter(String project, TaskScope scope, String q, TaskPriority priority, TaskStatus status, String label,
                      String sprint, String assignee, String epic) {
        this(project, scope, q, priority, status, label, sprint, assignee, epic, null);
    }
}
