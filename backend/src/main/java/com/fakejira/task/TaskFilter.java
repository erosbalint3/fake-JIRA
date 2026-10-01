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
        TaskType type,
        /** Default: hide archived tasks; "include" shows them too; "only" shows just those. */
        String archived) {

    public TaskFilter(String project, TaskScope scope, String q, TaskPriority priority, TaskStatus status, String label,
                      String sprint, String assignee, String epic) {
        this(project, scope, q, priority, status, label, sprint, assignee, epic, null, null);
    }

    public TaskFilter(String project, TaskScope scope, String q, TaskPriority priority, TaskStatus status, String label,
                      String sprint, String assignee, String epic, TaskType type) {
        this(project, scope, q, priority, status, label, sprint, assignee, epic, type, null);
    }
}
