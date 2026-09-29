package com.fakejira.notification;

/**
 * Published when a notification is stored; after the transaction commits it triggers an
 * instant email (if the recipient chose that) and browser push notifications.
 */
public record NotificationCreated(Long recipientId, String email, boolean emailEnabled, String message, Long taskId) {
}
