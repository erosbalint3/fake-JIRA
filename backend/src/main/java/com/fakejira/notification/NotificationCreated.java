package com.fakejira.notification;

/** Published when a notification is stored; used to send email after the transaction commits. */
public record NotificationCreated(String email, boolean emailEnabled, String message, Long taskId) {
}
