package com.fakejira.notification;

/** How much a user hears from a project (or, as the default, from everything). */
public enum NotificationLevel {
    /** Every notification. */
    ALL,
    /** Only things aimed at you: mentions, assignments, replies, approvals, reminders. */
    DIRECT,
    /** Nothing, not even in the app. */
    MUTED
}
