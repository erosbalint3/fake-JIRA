package com.fakejira.project;

/** Published when someone leaves or is removed from a project, so modules can drop their per-member data. */
public record MemberRemoved(Long projectId, Long userId) {
}
