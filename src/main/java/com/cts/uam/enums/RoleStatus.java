package com.cts.uam.enums;

/**
 * Workflow + lifecycle status for a Role.
 * PENDING  — newly created, awaiting checker approval
 * ACTIVE   — approved, usable role
 * REJECTED — checker rejected the creation request (row kept, reason stored)
 * INACTIVE — disabled by admin action
 */
public enum RoleStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    INACTIVE
}
