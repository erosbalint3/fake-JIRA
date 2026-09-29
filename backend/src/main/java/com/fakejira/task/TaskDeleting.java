package com.fakejira.task;

/**
 * Published inside the transaction deleting a task, before its comments, history and row are removed.
 * Modules keeping per-task data delete it on this event.
 */
public record TaskDeleting(Long taskId) {
}
