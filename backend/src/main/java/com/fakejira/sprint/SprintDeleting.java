package com.fakejira.sprint;

/** Published before a planned sprint is deleted, so per-sprint data can go with it. */
public record SprintDeleting(Long sprintId) {
}
