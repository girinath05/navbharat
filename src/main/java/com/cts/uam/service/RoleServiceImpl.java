package com.cts.uam.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cts.uam.dao.RoleDAO;
import com.cts.uam.dao.RoleDAOImpl;
import com.cts.uam.dao.UserDAO;
import com.cts.uam.dao.UserDAOImpl;
import com.cts.uam.enums.RoleStatus;
import com.cts.uam.model.AuditLog;
import com.cts.uam.model.Permission;
import com.cts.uam.model.Role;
import com.cts.util.SecurityUtil;

/**
 * RoleServiceImpl - implements all role management business logic.
 *
 * Enforces:
 * - Maker-checker: the person who creates a role cannot approve it themselves
 * - At least one permission must be selected for every role
 * - Role name format and uniqueness rules
 * - Audit logging for every sensitive action
 *
 * Note: cts_audit_log is shared with the user module.
 * We reuse UserDAO's saveAuditLog() here rather than duplicating it.
 */
public class RoleServiceImpl implements RoleService {

    private static final Logger LOG = LoggerFactory.getLogger(RoleServiceImpl.class);
    private final RoleDAO roleDAO = new RoleDAOImpl();

    // Reuse UserDAO just for audit logging - audit table is shared between user and
    // role modules
    private final UserDAO auditDAO = new UserDAOImpl();

    /**
     * Internal helper: writes an audit entry for a sensitive role action.
     * actor = who did it (if null, falls back to the currently logged-in user).
     * Errors are swallowed - audit must never block the real business action.
     */
    private void logAudit(String actor, String eventType, String details) {
        String who = actor != null ? actor : SecurityUtil.getCurrentUserId();
        try {
            auditDAO.saveAuditLog(new AuditLog(who, eventType, null, null, details));
        } catch (Exception ex) {
            LOG.warn("Could not save audit log [{}] for '{}': {}", eventType, who, ex.getMessage());
        }
    }

    // ── Queries ───────────────────────────────────────────────────

    // Get all roles visible in the main role list (excludes PENDING and REJECTED)
    @Override
    public List<Role> getAllRoles() {
        return roleDAO.getActiveAndInactiveRoles();
    }

    // Search roles with pagination
    @Override
    public List<Role> searchRoles(String query, int offset, int limit) {
        return roleDAO.searchRoles(query, offset, limit);
    }

    // Count matching roles for pagination math
    @Override
    public long countRoles(String query) {
        return roleDAO.countRoles(query);
    }

    // Filter only ACTIVE roles - used in the user creation form's role dropdown
    @Override
    public List<Role> getActiveRoles() {
        return roleDAO.getActiveAndInactiveRoles().stream()
                .filter(Role::isActive)
                .toList();
    }

    // Get all roles waiting for checker approval
    @Override
    public List<Role> getPendingRoles() {
        return roleDAO.getPendingRoles();
    }

    // Same but paginated
    @Override
    public List<Role> getPendingRoles(int offset, int limit) {
        return roleDAO.getPendingRoles(offset, limit);
    }

    // Count of pending roles (for pagination and badge display)
    @Override
    public long countPendingRoles() {
        return roleDAO.countPendingRoles();
    }

    // Find a role by numeric id
    @Override
    public Role findById(Long roleId) {
        return roleDAO.findById(roleId);
    }

    // Get all permissions grouped by module (for the permission tree UI)
    @Override
    public Map<String, List<Permission>> getPermissionsGroupedByModule() {
        return roleDAO.getPermissionsGroupedByModule();
    }

    // Get permission keys assigned to a specific role
    @Override
    public Set<String> getAssignedPermissionKeys(Long roleId) {
        return roleDAO.getAssignedPermissionKeys(roleId);
    }

    // ── Create (needs checker approval) ──────────────────────────

    /**
     * Submits a new role for checker approval.
     * Validates name, description, and requires at least one permission.
     * Inserts with PENDING status - cannot be used until a checker approves.
     */
    @Override
    public void submitNewRoleForApproval(String roleName, String description,
            Set<String> permissionKeys, String requestedBy) {

        validateRoleName(roleName);
        validateDescription(description);
        requireAtLeastOnePermission(permissionKeys);

        roleDAO.insertRoleAsPending(roleName, description, permissionKeys, requestedBy);
        LOG.info("RoleServiceImpl: role '{}' CREATE pending submitted by '{}'", roleName, requestedBy);
        logAudit(requestedBy, "ROLE_SUBMIT_PENDING", "New role '" + roleName + "' submitted for checker approval");
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /**
     * Checker approves a PENDING role -> status becomes ACTIVE.
     *
     * Checks:
     * - Role exists and is PENDING
     * - Checker is not the same as the maker (four-eyes rule)
     */
    @Override
    public void approveRole(long roleId, String checkerId) {
        Role role = requireExistingRole(roleId);
        requirePendingStatus(role);
        blockSelfApproval(checkerId, role.getCreatedBy());

        roleDAO.approveRole(roleId);
        LOG.info("RoleServiceImpl: role #{} approved by '{}'", roleId, checkerId);
        logAudit(checkerId, "ROLE_APPROVED", "Role #" + roleId + " (" + role.getRoleName() + ") approved");
    }

    /**
     * Checker rejects a PENDING role -> status becomes REJECTED, reason is stored.
     *
     * Checks:
     * - Rejection reason must not be empty
     * - Role exists and is PENDING
     * - Checker is not the same as the maker (four-eyes rule)
     */
    @Override
    public void rejectRole(long roleId, String checkerId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty())
            throw new IllegalArgumentException("Rejection reason is required.");

        Role role = requireExistingRole(roleId);
        requirePendingStatus(role);
        blockSelfApproval(checkerId, role.getCreatedBy());

        roleDAO.rejectRole(roleId, rejectionReason.trim());
        LOG.info("RoleServiceImpl: role #{} rejected by '{}' — reason: {}", roleId, checkerId, rejectionReason);
        logAudit(checkerId, "ROLE_REJECTED",
                "Role #" + roleId + " (" + role.getRoleName() + ") rejected — reason: " + rejectionReason.trim());
    }

    // ── Status actions (direct, no maker-checker) ─────────────────

    // Activate a role directly (admin action, no approval needed)
    @Override
    public void activateRole(long roleId) {
        roleDAO.setRoleStatus(roleId, RoleStatus.ACTIVE);
        LOG.info("RoleServiceImpl: role #{} activated directly", roleId);
        logAudit(null, "ROLE_ACTIVATED", "Role #" + roleId + " activated");
    }

    // Deactivate a role directly (will fail if active users are still assigned)
    @Override
    public void deactivateRole(long roleId) {
        roleDAO.setRoleStatus(roleId, RoleStatus.INACTIVE);
        LOG.info("RoleServiceImpl: role #{} deactivated directly", roleId);
        logAudit(null, "ROLE_DEACTIVATED", "Role #" + roleId + " deactivated");
    }

    // ── Update permissions (direct) ────────────────────────────────

    /**
     * Update a role's description and permission set directly (no approval needed).
     * Validates description and requires at least one permission.
     */
    @Override
    public void updateRoleDirectly(long roleId, String newDescription,
            Set<String> newPermissionKeys, String requestedBy) {

        requireExistingRole(roleId);
        validateDescription(newDescription);
        requireAtLeastOnePermission(newPermissionKeys);

        roleDAO.updateRoleDirectly(roleId, newDescription, newPermissionKeys);
        LOG.info("RoleServiceImpl: role #{} updated directly by '{}'", roleId, requestedBy);
        logAudit(requestedBy, "ROLE_EDITED", "Role #" + roleId + " permissions/description updated");
    }

    // ── Validation ────────────────────────────────────────────────

    /**
     * Validates a proposed new role name.
     * Returns an error message if invalid, null if the name is acceptable.
     * Rules: uppercase letters, digits, underscores only. Max 50 chars. Must be
     * unique.
     */
    @Override
    public String validateNewRoleName(String roleName) {
        if (roleName == null || roleName.isEmpty())
            return "Role name is required.";
        if (!roleName.matches("[A-Z0-9_]+"))
            return "Role name may only contain uppercase letters, digits and underscores.";
        if (roleName.length() > 50)
            return "Role name must be 50 characters or fewer.";
        if (roleDAO.findByName(roleName) != null)
            return "A role named \"" + roleName + "\" already exists.";
        return null;
    }

    /**
     * Validates a role description.
     * Returns an error message if longer than 200 chars, or null if valid.
     */
    @Override
    public String validateDescription(String description) {
        if (description != null && description.length() > 200)
            return "Description must be 200 characters or fewer.";
        return null;
    }

    // ── Private Guards ────────────────────────────────────────────

    // Load a role by id, throws if not found
    private Role requireExistingRole(long roleId) {
        Role role = roleDAO.findById(roleId);
        if (role == null)
            throw new IllegalStateException("Role #" + roleId + " not found");
        return role;
    }

    // Throws if the role is not in PENDING status
    private void requirePendingStatus(Role role) {
        if (role.getStatus() != RoleStatus.PENDING)
            throw new IllegalStateException("Role #" + role.getId() + " is not PENDING");
    }

    /**
     * Blocks the four-eyes violation: maker and checker must be different people.
     * Throws if checkerId equals the maker's id.
     */
    private void blockSelfApproval(String checkerId, String requestedBy) {
        if (checkerId != null && checkerId.equals(requestedBy))
            throw new IllegalStateException(
                    "Four-eyes violation: maker and checker cannot be the same user.");
    }

    // Runs validateNewRoleName and throws if there is an error (internal use)
    private void validateRoleName(String roleName) {
        String error = validateNewRoleName(roleName);
        if (error != null)
            throw new IllegalArgumentException(error);
    }

    // Throws if the permission set is null or empty - every role needs at least one
    // permission
    private void requireAtLeastOnePermission(Set<String> permissionKeys) {
        if (permissionKeys == null || permissionKeys.isEmpty())
            throw new IllegalArgumentException("Please select at least one permission for this role.");
    }
}
