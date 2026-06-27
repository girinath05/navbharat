package com.cts.uam.enums;

/**
 * RoleStatus - lifecycle status for a Role in the maker-checker workflow.
 *
 * PENDING  - maker just created this role, waiting for checker to review
 * ACTIVE   - checker approved it; role can now be assigned to users
 * REJECTED - checker rejected the creation request; row stays in DB for audit
 * INACTIVE - admin disabled this role; it cannot be assigned to new users
 */
public enum RoleStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    INACTIVE
}
