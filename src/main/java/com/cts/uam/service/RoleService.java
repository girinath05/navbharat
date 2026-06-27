package com.cts.uam.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cts.uam.model.Permission;
import com.cts.uam.model.Role;

/**
 * RoleService - business logic layer for all Role and Permission operations.
 *
 * Implementation: RoleServiceImpl
 *
 * This layer sits between the UI (Composers) and the DB (RoleDAO).
 * It enforces business rules like:
 * - Maker-checker (self-approval blocked)
 * - At least one permission required for every role
 * - Role name format and uniqueness
 * - Logging audit entries for every sensitive action
 */
public interface RoleService {

        // ── Queries ───────────────────────────────────────────────────

        // Get all roles shown in the main role list (excludes PENDING and REJECTED)
        List<Role> getAllRoles();

        // Search roles by name/description with pagination
        List<Role> searchRoles(String query, int offset, int limit);

        // Count matching roles for pagination
        long countRoles(String query);

        // Get only ACTIVE roles - used in the user creation form's role dropdown
        List<Role> getActiveRoles();

        // Get all roles waiting for checker approval
        List<Role> getPendingRoles();

        // Same but paginated
        List<Role> getPendingRoles(int offset, int limit);

        // Count of pending roles (for pagination and badge display)
        long countPendingRoles();

        // Find a role by its numeric id
        Role findById(Long roleId);

        // Get all permissions grouped by module (for building the permission tree UI)
        Map<String, List<Permission>> getPermissionsGroupedByModule();

        // Get the set of permission keys currently assigned to a given role
        Set<String> getAssignedPermissionKeys(Long roleId);

        // ── Create (needs checker approval) ──────────────────────────

        // Submit a new role for checker approval - inserts with PENDING status
        void submitNewRoleForApproval(String roleName, String description,
                        Set<String> permissionKeys, String requestedBy);

        // ── Approve / Reject ──────────────────────────────────────────

        // Checker approves a PENDING role -> status becomes ACTIVE
        void approveRole(long roleId, String checkerId);

        // Checker rejects a PENDING role -> status becomes REJECTED, reason stored
        void rejectRole(long roleId, String checkerId, String rejectionReason);

        // ── Status actions (direct, no maker-checker) ─────────────────

        // Admin activates an INACTIVE role directly
        void activateRole(long roleId);

        // Admin deactivates an ACTIVE role directly (blocked if active users are
        // assigned)
        void deactivateRole(long roleId);

        // ── Update permissions (direct) ────────────────────────────────

        // Update description and permissions of an existing role (no approval needed)
        void updateRoleDirectly(long roleId, String newDescription,
                        Set<String> newPermissionKeys, String requestedBy);

        // ── Validation ────────────────────────────────────────────────

        /**
         * Validates a proposed new role name.
         * Returns an error message string if invalid, or null if the name is
         * acceptable.
         * Rules: uppercase letters, digits, underscores only. Max 50 chars. Must be
         * unique.
         */
        String validateNewRoleName(String roleName);

        /**
         * Validates a role description.
         * Returns an error message string if too long (>200 chars), or null if valid.
         */
        String validateDescription(String description);
}
