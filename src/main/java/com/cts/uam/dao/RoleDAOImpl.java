package com.cts.uam.dao;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cts.uam.enums.RoleStatus;
import com.cts.uam.model.Permission;
import com.cts.uam.model.Role;
import com.cts.util.HibernateUtil;

/**
 * RoleDAOImpl - handles all DB operations for Roles and Permissions.
 *
 * Each method opens its own Hibernate session (try-with-resources).
 * Write methods begin and commit their own transaction.
 *
 * Key rule: roles cannot be set to INACTIVE if any active users still
 * have that role assigned (blockIfActiveUsersExist enforces this).
 */
public class RoleDAOImpl implements RoleDAO {

    private static final Logger LOG = LoggerFactory.getLogger(RoleDAOImpl.class);

    // ── Permissions ───────────────────────────────────────────────

    /**
     * Loads all permissions from DB and groups them by module name.
     * Module order is preserved by using LinkedHashMap.
     * Used to build the permission checkbox tree in the role editor.
     */
    @Override
    public Map<String, List<Permission>> getPermissionsGroupedByModule() {
        try (Session session = HibernateUtil.getSession()) {
            List<Permission> allPermissions = session.createNativeQuery(
                    "SELECT * FROM cts_permissions ORDER BY module, display_name",
                    Permission.class).list();

            Map<String, List<Permission>> grouped = new LinkedHashMap<>();
            for (Permission permission : allPermissions) {
                String module = permission.getModule();
                // computeIfAbsent creates the list on first encounter of a module
                grouped.computeIfAbsent(module, k -> new ArrayList<>()).add(permission);
            }
            return grouped;
        }
    }

    // ── Role Queries ──────────────────────────────────────────────

    // Returns all roles except PENDING and REJECTED, sorted by name
    @Override
    public List<Role> getActiveAndInactiveRoles() {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_roles WHERE status NOT IN (:pending, :rejected) ORDER BY role_name",
                    Role.class)
                    .setParameter("pending", RoleStatus.PENDING.name())
                    .setParameter("rejected", RoleStatus.REJECTED.name())
                    .list();
        }
    }

    /**
     * Search roles by name or description (case-insensitive LIKE), paginated.
     * Excludes PENDING and REJECTED roles.
     * Empty query string returns all visible roles.
     */
    @Override
    public List<Role> searchRoles(String query, int offset, int limit) {
        try (Session session = HibernateUtil.getSession()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM cts_roles WHERE status NOT IN (:pending, :rejected)");
            if (query != null && !query.isEmpty())
                sql.append(" AND (lower(role_name) LIKE :q OR lower(description) LIKE :q)");
            sql.append(" ORDER BY role_name LIMIT :lim OFFSET :off");

            var nativeQuery = session.createNativeQuery(sql.toString(), Role.class);
            nativeQuery.setParameter("pending", RoleStatus.PENDING.name());
            nativeQuery.setParameter("rejected", RoleStatus.REJECTED.name());
            if (query != null && !query.isEmpty())
                nativeQuery.setParameter("q", "%" + query.toLowerCase() + "%");
            nativeQuery.setParameter("lim", limit);
            nativeQuery.setParameter("off", offset);
            return nativeQuery.list();
        }
    }

    // Count of roles matching the search query (for pagination math)
    @Override
    public long countRoles(String query) {
        try (Session session = HibernateUtil.getSession()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT COUNT(*) FROM cts_roles WHERE status NOT IN (:pending, :rejected)");
            if (query != null && !query.isEmpty())
                sql.append(" AND (lower(role_name) LIKE :q OR lower(description) LIKE :q)");

            var nativeQuery = session.createNativeQuery(sql.toString());
            nativeQuery.setParameter("pending", RoleStatus.PENDING.name());
            nativeQuery.setParameter("rejected", RoleStatus.REJECTED.name());
            if (query != null && !query.isEmpty())
                nativeQuery.setParameter("q", "%" + query.toLowerCase() + "%");
            return ((Number) nativeQuery.getSingleResult()).longValue();
        }
    }

    // Returns all roles waiting for checker approval, sorted by name
    @Override
    public List<Role> getPendingRoles() {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_roles WHERE status = :pending ORDER BY role_name",
                    Role.class)
                    .setParameter("pending", RoleStatus.PENDING.name())
                    .list();
        }
    }

    // Same but paginated
    @Override
    public List<Role> getPendingRoles(int offset, int limit) {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_roles WHERE status = :pending ORDER BY role_name LIMIT :lim OFFSET :off",
                    Role.class)
                    .setParameter("pending", RoleStatus.PENDING.name())
                    .setParameter("lim", limit)
                    .setParameter("off", offset)
                    .list();
        }
    }

    // Count of PENDING roles (for pagination and badge display)
    @Override
    public long countPendingRoles() {
        try (Session session = HibernateUtil.getSession()) {
            return ((Number) session.createNativeQuery(
                    "SELECT COUNT(*) FROM cts_roles WHERE status = :pending")
                    .setParameter("pending", RoleStatus.PENDING.name())
                    .getSingleResult()).longValue();
        }
    }

    // Find a role by exact name - returns null if not found
    @Override
    public Role findByName(String roleName) {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_roles WHERE role_name = :name AND status != 'REJECTED'",
                    Role.class)
                    .setParameter("name", roleName)
                    .uniqueResult();
        }
    }

    // Find a role by numeric id - returns null if not found
    @Override
    public Role findById(Long roleId) {
        try (Session session = HibernateUtil.getSession()) {
            return session.get(Role.class, roleId);
        }
    }

    // Returns all permission keys assigned to the given role
    @Override
    public Set<String> getAssignedPermissionKeys(Long roleId) {
        try (Session session = HibernateUtil.getSession()) {
            List<String> keys = session.createNativeQuery(
                    "SELECT permission_key FROM cts_role_permissions WHERE role_id = :roleId",
                    String.class)
                    .setParameter("roleId", roleId)
                    .list();
            return new HashSet<>(keys);
        }
    }

    // ── Create (PENDING) ──────────────────────────────────────────

    /**
     * Inserts a new role with PENDING status and assigns its permissions.
     * Both the role row and its permission rows are saved in one transaction.
     */
    @Override
    public void insertRoleAsPending(String roleName, String description,
            Set<String> permissionKeys, String createdBy) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            session.createNativeQuery(
                    "DELETE FROM cts_roles WHERE role_name = :name AND status = 'REJECTED'")
                    .setParameter("name", roleName)
                    .executeUpdate();

            Role role = new Role();
            role.setRoleName(roleName);
            role.setDescription(description);
            role.setStatus(RoleStatus.PENDING);
            role.setCreatedBy(createdBy);
            session.persist(role);
            session.flush(); // flush to get the generated role id before inserting permissions

            replacePermissions(session, role.getId(), permissionKeys);
            session.getTransaction().commit();
            LOG.info("RoleDAOImpl: role '{}' inserted as PENDING by '{}'", roleName, createdBy);
        }
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /**
     * Approves a PENDING role by setting its status to ACTIVE.
     * Throws IllegalStateException if role is not PENDING.
     */
    @Override
    public void approveRole(long roleId) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            Role role = requirePendingRole(session, roleId);
            role.setStatus(RoleStatus.ACTIVE);
            session.merge(role);
            session.getTransaction().commit();
        }
    }

    /**
     * Rejects a PENDING role. Sets status to REJECTED and stores the reason.
     * The row is kept in DB for audit purposes.
     * Throws IllegalStateException if role is not PENDING.
     */
    @Override
    public void rejectRole(long roleId, String rejectionReason) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            Role role = requirePendingRole(session, roleId);
            role.setStatus(RoleStatus.REJECTED);
            role.setRejectedReason(rejectionReason);
            session.merge(role);
            session.getTransaction().commit();
            LOG.info("RoleDAOImpl: role '{}' REJECTED — reason: {}", role.getRoleName(), rejectionReason);
        }
    }

    // ── Status Actions ────────────────────────────────────────────

    /**
     * Directly sets a role to ACTIVE or INACTIVE (no maker-checker).
     * If setting to INACTIVE, first checks that no active users still use this
     * role.
     * Throws if role not found or if active users exist (for INACTIVE case).
     */
    @Override
    public void setRoleStatus(long roleId, RoleStatus newStatus) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            // Safety check before deactivating - cannot deactivate if users still assigned
            if (newStatus == RoleStatus.INACTIVE) {
                blockIfActiveUsersExist(session, roleId);
            }

            Role role = session.get(Role.class, roleId);
            if (role == null) {
                throw new IllegalStateException("Role #" + roleId + " not found");
            }

            switch (newStatus) {
                case ACTIVE -> role.setStatus(RoleStatus.ACTIVE);
                case INACTIVE -> role.setStatus(RoleStatus.INACTIVE);
                default -> throw new IllegalArgumentException(
                        "Unsupported role status change: " + newStatus);
            }

            session.merge(role);
            session.getTransaction().commit();
        }
    }

    // ── Update ────────────────────────────────────────────────────

    /**
     * Updates a role's description and replaces its full permission set.
     * Both changes happen in one transaction - old permissions are deleted first,
     * then the new set is inserted.
     */
    @Override
    public void updateRoleDirectly(long roleId, String newDescription, Set<String> newPermissionKeys) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            Role role = session.get(Role.class, roleId);
            if (role == null)
                throw new IllegalStateException("Role #" + roleId + " not found");

            role.setDescription(newDescription);
            session.merge(role);

            replacePermissions(session, roleId, newPermissionKeys);
            session.getTransaction().commit();
        }
    }

    // ── Private Helpers ───────────────────────────────────────────

    /**
     * Replaces all permissions for a role in one batch operation.
     * Deletes all existing permissions first, then inserts the new set.
     * Uses PostgreSQL unnest() to insert all keys in one SQL statement
     * instead of looping with one INSERT per key.
     */
    private void replacePermissions(Session session, long roleId, Set<String> permissionKeys) {
        session.createNativeQuery("DELETE FROM cts_role_permissions WHERE role_id = ?1")
                .setParameter(1, roleId)
                .executeUpdate();

        if (permissionKeys.isEmpty()) {
            return;
        }

        String[] keysArray = permissionKeys.toArray(new String[0]);
        session.createNativeQuery(
                "INSERT INTO cts_role_permissions(role_id, permission_key) " +
                        "SELECT :roleId, unnest(CAST(:keys AS text[])) " +
                        "ON CONFLICT DO NOTHING")
                .setParameter("roleId", roleId)
                .setParameter("keys", keysArray)
                .executeUpdate();
    }

    // Load a role by id, throws if not found or not PENDING
    private Role requirePendingRole(Session session, long roleId) {
        Role role = session.get(Role.class, roleId);
        if (role == null)
            throw new IllegalStateException("Role #" + roleId + " not found");
        if (role.getStatus() != RoleStatus.PENDING)
            throw new IllegalStateException("Role #" + roleId + " is not PENDING");
        return role;
    }

    /**
     * Counts active users who are assigned to this role.
     * Throws if any are found - you must reassign them before deactivating the
     * role.
     * "Active" here means not TERMINATED, DISABLED, REJECTED, or PENDING.
     */
    private void blockIfActiveUsersExist(Session session, long roleId) {
        long activeUserCount = ((Number) session.createNativeQuery(
                "SELECT COUNT(*) FROM cts_users " +
                        "WHERE role_id = :roleId " +
                        "AND status NOT IN ('TERMINATED','DISABLED','REJECTED','PENDING')")
                .setParameter("roleId", roleId)
                .uniqueResult()).longValue();

        if (activeUserCount > 0)
            throw new IllegalStateException(
                    "Cannot disable role #" + roleId + ": "
                            + activeUserCount + " active user(s) still assigned. Reassign them first.");
    }
}
