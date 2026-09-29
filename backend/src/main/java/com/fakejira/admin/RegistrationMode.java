package com.fakejira.admin;

public enum RegistrationMode {
    /** Anyone can sign up. */
    OPEN,
    /** Sign-up needs an invite code. */
    INVITE,
    /** Anyone can sign up, but an admin must approve the account before it can sign in. */
    APPROVAL
}
