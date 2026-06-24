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

public class RoleDAO {

    private static final Logger LOG = LoggerFactory.getLogger(RoleDAO.class);

    // ── Permissions ───────────────────────────────────────────────

    /** All permissions grouped by module name — for permission tree UI. */
    public Map<String, List<Permission>> getPermissionsGroupedByModule() {
        try (Session session = HibernateUtil.getSession()) {
            List<Permission> allPermissions = session.createNativeQuery(
                    "SELECT * FROM cts_permissions ORDER BY module, display_name",
                    Permission.class).list();

            Map<String, List<Permission>> grouped = new LinkedHashMap<>();
            for (Permission permission : allPermissions) {
                String module = permission.getModule();

                if (!grouped.containsKey(module)) {
                    grouped.put(module, new ArrayList<>());
                }

                grouped.get(module).add(permission);
            }
            return grouped;
        }
    }

    // ── Role Queries ──────────────────────────────────────────────

    /** All roles visible in main list — excludes PENDING and REJECTED. */
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

    /** Roles waiting for checker approval. */
    public List<Role> getPendingRoles() {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_roles WHERE status = :pending ORDER BY role_name",
                    Role.class)
                    .setParameter("pending", RoleStatus.PENDING.name())
                    .list();
        }
    }

    public Role findByName(String roleName) {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_roles WHERE role_name = :name", Role.class)
                    .setParameter("name", roleName)
                    .uniqueResult();
        }
    }

    public Role findById(Long roleId) {
        try (Session session = HibernateUtil.getSession()) {
            return session.get(Role.class, roleId);
        }
    }

    /** Permission keys currently assigned to a role. */
    //here is performing n+1 query
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

    public void insertRoleAsPending(String roleName, String description,
            Set<String> permissionKeys, String createdBy) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            Role role = new Role();
            role.setRoleName(roleName);
            role.setDescription(description);
            role.setStatus(RoleStatus.PENDING);
            role.setCreatedBy(createdBy);
            session.persist(role);
            session.flush();

            replacePermissions(session, role.getId(), permissionKeys);
            session.getTransaction().commit();
            LOG.info("RoleDAO: role '{}' inserted as PENDING by '{}'", roleName, createdBy);
        }
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /** Approve PENDING role → ACTIVE. */
    public void approveRole(long roleId) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            Role role = requirePendingRole(session, roleId);
            role.setStatus(RoleStatus.ACTIVE);
            session.merge(role);
            session.getTransaction().commit();
        }
    }

    /** Reject PENDING role → REJECTED. */
    public void rejectRole(long roleId, String rejectionReason) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            Role role = requirePendingRole(session, roleId);
            role.setStatus(RoleStatus.REJECTED);
            role.setRejectedReason(rejectionReason);
            session.merge(role);
            session.getTransaction().commit();
            LOG.info("RoleDAO: role '{}' REJECTED — reason: {}", role.getRoleName(), rejectionReason);
        }
    }

    // ── Status Actions ────────────────────────────────────────────

    public void setRoleStatus(long roleId, RoleStatus newStatus) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

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
                default -> throw new IllegalArgumentException("Unsupported role status change: " + newStatus);
            }

            session.merge(role);
            session.getTransaction().commit();
        }
    }

    // ── Private Helpers ───────────────────────────────────────────

    private void replacePermissions(Session session, long roleId, Set<String> permissionKeys) {
        session.createNativeQuery("DELETE FROM cts_role_permissions WHERE role_id = ?1")
                .setParameter(1, roleId)
                .executeUpdate();

        for (String key : permissionKeys) {
            session.createNativeQuery(
                    "INSERT INTO cts_role_permissions(role_id, permission_key) " +
                            "VALUES(?1, ?2) ON CONFLICT DO NOTHING")
                    .setParameter(1, roleId)
                    .setParameter(2, key)
                    .executeUpdate();
        }
    }

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

    private Role requirePendingRole(Session session, long roleId) {
        Role role = session.get(Role.class, roleId);
        if (role == null)
            throw new IllegalStateException("Role #" + roleId + " not found");
        if (role.getStatus() != RoleStatus.PENDING)
            throw new IllegalStateException("Role #" + roleId + " is not PENDING");
        return role;
    }

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