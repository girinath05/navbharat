package com.cts.uam.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cts.uam.dao.RoleDAO;
import com.cts.uam.enums.RoleStatus;
import com.cts.uam.model.Permission;
import com.cts.uam.model.Role;

public class RoleService {

    private static final Logger LOG = LoggerFactory.getLogger(RoleService.class);
    private final RoleDAO roleDAO = new RoleDAO();

    // ── Queries ───────────────────────────────────────────────────

    /** All roles visible in main list — excludes PENDING and REJECTED. */
    public List<Role> getAllRoles() {
        return roleDAO.getActiveAndInactiveRoles();
    }

    /** Only ACTIVE roles — used in user-role assignment dropdowns. */
    public List<Role> getActiveRoles() {
        return roleDAO.getActiveAndInactiveRoles().stream()
                .filter(Role::isActive)
                .toList();
    }

    /** Roles waiting for checker approval. */
    public List<Role> getPendingRoles() {
        return roleDAO.getPendingRoles();
    }

    public Role findById(Long roleId) {
        return roleDAO.findById(roleId);
    }

    public Map<String, List<Permission>> getPermissionsGroupedByModule() {
        return roleDAO.getPermissionsGroupedByModule();
    }

    public Set<String> getAssignedPermissionKeys(Long roleId) {
        return roleDAO.getAssignedPermissionKeys(roleId);
    }

    // ── Create (needs checker approval) ──────────────────────────

    /** Submit new role for checker approval. Inserts as PENDING. */
    public void submitNewRoleForApproval(String roleName, String description,
            Set<String> permissionKeys, String requestedBy) {

        validateRoleName(roleName);
        validateDescription(description);
        requireAtLeastOnePermission(permissionKeys);

        roleDAO.insertRoleAsPending(roleName, description, permissionKeys, requestedBy);
        LOG.info("RoleService: role '{}' CREATE pending submitted by '{}'", roleName, requestedBy);
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /** Checker approves a PENDING role → ACTIVE. */
    public void approveRole(long roleId, String checkerId) {
        Role role = requireExistingRole(roleId);
        requirePendingStatus(role);
        blockSelfApproval(checkerId, role.getCreatedBy());

        roleDAO.approveRole(roleId);
        LOG.info("RoleService: role #{} approved by '{}'", roleId, checkerId);
    }

    /** Checker rejects a PENDING role → REJECTED. Keeps the row for audit. */
    public void rejectRole(long roleId, String checkerId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty())
            throw new IllegalArgumentException("Rejection reason is required.");

        Role role = requireExistingRole(roleId);
        requirePendingStatus(role);
        blockSelfApproval(checkerId, role.getCreatedBy());

        roleDAO.rejectRole(roleId, rejectionReason.trim());
        LOG.info("RoleService: role #{} rejected by '{}' — reason: {}", roleId, checkerId, rejectionReason);
    }

    // ── Status actions (direct, no maker-checker) ─────────────────

    /** Enable or Disable a role immediately. No checker approval needed. */
    public void activateRole(long roleId) {
        roleDAO.setRoleStatus(roleId, RoleStatus.ACTIVE);
        LOG.info("RoleService: role #{} activated directly", roleId);
    }

    public void deactivateRole(long roleId) {
        roleDAO.setRoleStatus(roleId, RoleStatus.INACTIVE);
        LOG.info("RoleService: role #{} deactivated directly", roleId);
    }

    // ── Update permissions (direct) ────────────────────────────────

    /**
     * Update role description and permissions directly.
     * NOTE: In future, this will be routed through maker-checker via
     * submitRoleEditRequest().
     */
    public void updateRoleDirectly(long roleId, String newDescription,
            Set<String> newPermissionKeys, String requestedBy) {

        requireExistingRole(roleId);
        validateDescription(newDescription);
        requireAtLeastOnePermission(newPermissionKeys);

        roleDAO.updateRoleDirectly(roleId, newDescription, newPermissionKeys);
        LOG.info("RoleService: role #{} updated directly by '{}'", roleId, requestedBy);
    }

    // ── Validation ────────────────────────────────────────────────

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

    public String validateDescription(String description) {
        if (description != null && description.length() > 200)
            return "Description must be 200 characters or fewer.";
        return null;
    }

    // ── Private Guards ────────────────────────────────────────────

    private Role requireExistingRole(long roleId) {
        Role role = roleDAO.findById(roleId);
        if (role == null)
            throw new IllegalStateException("Role #" + roleId + " not found");
        return role;
    }

    private void requirePendingStatus(Role role) {
        if (role.getStatus() != RoleStatus.PENDING)
            throw new IllegalStateException("Role #" + role.getId() + " is not PENDING");
    }

    private void blockSelfApproval(String checkerId, String requestedBy) {
        if (checkerId != null && checkerId.equals(requestedBy))
            throw new IllegalStateException(
                    "Four-eyes violation: maker and checker cannot be the same user.");
    }

    private void validateRoleName(String roleName) {
        String error = validateNewRoleName(roleName);
        if (error != null)
            throw new IllegalArgumentException(error);
    }

    private void requireAtLeastOnePermission(Set<String> permissionKeys) {
        if (permissionKeys == null || permissionKeys.isEmpty())
            throw new IllegalArgumentException("Please select at least one permission for this role.");
    }
}