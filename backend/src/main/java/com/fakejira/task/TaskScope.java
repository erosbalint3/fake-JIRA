package com.fakejira.task;

public enum TaskScope {
    /** Unassigned tasks anyone can pick up. */
    AVAILABLE,
    /** Tasks assigned to the current user. */
    MINE,
    /** Tasks created by the current user. */
    REPORTED,
    ALL
}
