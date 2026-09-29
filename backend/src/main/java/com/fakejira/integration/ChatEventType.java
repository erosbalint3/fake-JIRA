package com.fakejira.integration;

/** What a chat webhook can be told about. */
public enum ChatEventType {
    TASK_CREATED,
    TASK_DONE,
    STATUS_CHANGED,
    COMMENT_ADDED,
    SPRINT
}
