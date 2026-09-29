package com.fakejira.project;

/**
 * Published inside the transaction that deletes a project, after its tasks are gone and before its sprints,
 * epics and the project row are removed. Modules that keep per-project data delete it on this event.
 */
public record ProjectDeleting(Long projectId) {
}
