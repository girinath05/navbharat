package com.cts.uam.enums;

/**
 * Workflow + lifecycle status for a User.
 * PENDING  — newly created, awaiting checker approval
 * ACTIVE   — approved, normal usable account
 * REJECTED — checker rejected the creation request (row kept, reason stored)
 * INACTIVE — reserved for future use (not currently set by the app)
 * LOCKED   — locked out (failed logins or admin action)
 * DISABLED — disabled by admin action
 * TERMINATED — terminated by admin action
 */
public enum UserStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    INACTIVE,
    LOCKED,
    DISABLED,
    TERMINATED
}
