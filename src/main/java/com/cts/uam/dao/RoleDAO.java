package com.cts.uam.dao;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cts.uam.enums.RoleStatus;
import com.cts.uam.model.Permission;
import com.cts.uam.model.Role;

/**
 * RoleDAO - defines all database operations for Roles and Permissions.
 *
 * Implementation: RoleDAOImpl
 *
 * Covers:
 * - Load all permissions (grouped by module for the permission tree UI)
 * - Search and filter roles
 * - Create a new role in PENDING state
 * - Approve or reject a pending role
 * - Activate or deactivate a role
 * - Update a role's description and permissions
 */
public interface RoleDAO {

        // ── Permissions ───────────────────────────────────────────────

        // Returns all permissions from DB, grouped by module name.
        // Used to build the permission tree in the role editor UI.
        Map<String, List<Permission>> getPermissionsGroupedByModule();

        // ── Role Queries ──────────────────────────────────────────────

        // Returns all roles except PENDING and REJECTED (shown in the main role list)
        List<Role> getActiveAndInactiveRoles();

        // Search roles by name or description, with pagination (excludes
        // PENDING/REJECTED)
        List<Role> searchRoles(String query, int offset, int limit);

        // Count of roles matching the search query (for pagination math)
        long countRoles(String query);

        // Returns all roles waiting for checker approval (status = PENDING)
        List<Role> getPendingRoles();

        // Same but paginated
        List<Role> getPendingRoles(int offset, int limit);

        // Count of pending roles (for pagination and badge display)
        long countPendingRoles();

        // Find one role by exact name - returns null if not found
        Role findByName(String roleName);

        // Find one role by numeric DB id - returns null if not found
        Role findById(Long roleId);

        // Returns the set of permission keys currently assigned to a given role
        Set<String> getAssignedPermissionKeys(Long roleId);

        // ── Create (PENDING) ──────────────────────────────────────────

        // Insert a new role with PENDING status and assign its permissions
        void insertRoleAsPending(String roleName, String description,
                        Set<String> permissionKeys, String createdBy);

        // ── Approve / Reject ──────────────────────────────────────────

        // Approve a PENDING role -> status becomes ACTIVE
        void approveRole(long roleId);

        // Reject a PENDING role -> status becomes REJECTED, reason is stored
        void rejectRole(long roleId, String rejectionReason);

        // ── Status Actions ────────────────────────────────────────────

        // Directly set a role's status to ACTIVE or INACTIVE (no maker-checker needed)
        void setRoleStatus(long roleId, RoleStatus newStatus);

        // ── Update ────────────────────────────────────────────────────

        // Update an existing role's description and replace its permission set (no
        // approval needed)
        void updateRoleDirectly(long roleId, String newDescription,
                        Set<String> newPermissionKeys);
}
