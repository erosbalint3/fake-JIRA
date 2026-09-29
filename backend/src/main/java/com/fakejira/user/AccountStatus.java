package com.fakejira.user;

public enum AccountStatus {
    ACTIVE,
    /** Registered while sign-up required admin approval; cannot sign in yet. */
    PENDING
}
