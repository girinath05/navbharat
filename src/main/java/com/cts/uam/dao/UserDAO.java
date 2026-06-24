package com.cts.uam.dao;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.hibernate.Session;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cts.uam.enums.UserStatus;
import com.cts.uam.model.AuditLog;
import com.cts.uam.model.User;
import com.cts.util.HibernateUtil;

public class UserDAO {

    private static final Logger LOG = LoggerFactory.getLogger(UserDAO.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final int INDEFINITE_LOCK_YEAR = 9999;
    private static final int AUTO_LOCK_MINUTES = 30;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int TERMINATE_LOCK_YEARS = 100;

    // ── Search / Filter ───────────────────────────────────────────

    public List<User> searchUsers(String usernameFilter, String nameFilter, UserStatus statusFilter) {
        try (Session session = HibernateUtil.getSession()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM cts_users WHERE status NOT IN (:pending, :rejected)");
            if (!usernameFilter.isEmpty())
                sql.append(" AND lower(username) LIKE :uid");
            if (!nameFilter.isEmpty())
                sql.append(" AND lower(full_name) LIKE :nm");
            if (statusFilter != null)
                sql.append(" AND status = :st");
            sql.append(" ORDER BY username");

            var query = session.createNativeQuery(sql.toString(), User.class);
            query.setParameter("pending", UserStatus.PENDING.name());
            query.setParameter("rejected", UserStatus.REJECTED.name());
            if (!usernameFilter.isEmpty())
                query.setParameter("uid", "%" + usernameFilter.toLowerCase() + "%");
            if (!nameFilter.isEmpty())
                query.setParameter("nm", "%" + nameFilter.toLowerCase() + "%");
            if (statusFilter != null)
                query.setParameter("st", statusFilter.name());
            return query.list();
        }
    }

    public Optional<User> findByUsername(String username) {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_users WHERE username = :username", User.class)
                    .setParameter("username", username)
                    .uniqueResultOptional();
        }
    }

    public User findById(long userId) {
        try (Session session = HibernateUtil.getSession()) {
            return session.get(User.class, userId);
        }
    }

    // ── Create User (PENDING) ─────────────────────────────────────

    public void insertUserAsPending(User blueprint, String requestedBy) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            blueprint.setStatus(UserStatus.PENDING);
            blueprint.setFailedAttempts(0);
            blueprint.setRequestedBy(requestedBy);
            blueprint.setPasswordHash(""); // blank — abhi set nahi

            session.persist(blueprint);
            session.getTransaction().commit();
            LOG.info("UserDAO: user '{}' inserted as PENDING by '{}'",
                    blueprint.getUsername(), requestedBy);
        }
    }

    // ── Approve / Reject ──────────────────────────────────────────

    public String approveUser(long userId) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            User user = requirePendingUser(session, userId);

            String tempPassword = generateTempPassword(); // ← yahan generate
            user.setPasswordHash(BCrypt.hashpw(tempPassword, BCrypt.gensalt()));
            user.setStatus(UserStatus.ACTIVE);

            session.merge(user);
            session.getTransaction().commit();

            LOG.info("UserDAO: user '{}' approved, temp password set", user.getUsername());
            return tempPassword; // ← checker ko dikhane ke liye return
        }
    }

    public void rejectUser(long userId, String rejectionReason) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = requirePendingUser(session, userId);
            user.setStatus(UserStatus.REJECTED);
            user.setRejectedReason(rejectionReason);
            session.merge(user);
            session.getTransaction().commit();
            LOG.info("UserDAO: user '{}' REJECTED — reason: {}", user.getUsername(), rejectionReason);
        }
    }

    // ── Pending List ──────────────────────────────────────────────

    public List<User> findPendingUsers() {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_users WHERE status = :pending ORDER BY username",
                    User.class)
                    .setParameter("pending", UserStatus.PENDING.name())
                    .list();
        }
    }

    // ── Direct Status Actions ─────────────────────────────────────

    public void applyStatusAction(long userId, String action, Integer lockMinutes) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = requireExistingUser(session, userId);
            applyActionToUser(user, action, lockMinutes);
            session.merge(user);
            session.getTransaction().commit();
        }
    }

    // ── Edit User Details ─────────────────────────────────────────

    public void updateUserDetails(long userId, String fullName, String email,
            String mobile, Long roleId, String roleLabel) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = requireExistingUser(session, userId);
            user.setFullName(fullName);
            user.setEmail(email);
            user.setMobile(mobile);
            if (roleId != null) {
                user.setRoleId(roleId);
                user.setRoleLabel(roleLabel);
            }
            session.merge(user);
            session.getTransaction().commit();
        }
    }

    public void updatePasswordHash(long userId, String newPasswordHash) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = requireExistingUser(session, userId);
            user.setPasswordHash(newPasswordHash);
            session.merge(user);
            session.getTransaction().commit();
        }
    }

    // ── Login Tracking ────────────────────────────────────────────

    public void lockUser(long userId, int lockMinutes) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = session.get(User.class, userId);
            if (user == null)
                return;
            user.setLockedUntil(computeLockExpiry(lockMinutes));
            user.setStatus(UserStatus.LOCKED);
            session.merge(user);
            session.getTransaction().commit();
        }
    }

    public void unlockUser(long userId) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = session.get(User.class, userId);
            if (user == null)
                return;
            clearLock(user);
            session.merge(user);
            session.getTransaction().commit();
        }
    }

    public void incrementFailedAttempts(User user) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User managed = session.get(User.class, user.getId());
            if (managed == null)
                return;
            managed.setFailedAttempts(managed.getFailedAttempts() + 1);
            if (managed.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                managed.setLockedUntil(LocalDateTime.now().plusMinutes(AUTO_LOCK_MINUTES));
                managed.setStatus(UserStatus.LOCKED);
            }
            session.merge(managed);
            session.getTransaction().commit();
        }
    }

    public void recordSuccessfulLogin(User user) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User managed = session.get(User.class, user.getId());
            if (managed == null)
                return;
            managed.setFailedAttempts(0);
            managed.setLockedUntil(null);
            managed.setLastLogin(LocalDateTime.now());
            if (managed.getStatus() == UserStatus.LOCKED) {
                managed.setStatus(UserStatus.ACTIVE);
            }
            session.merge(managed);
            session.getTransaction().commit();
        }
    }

    /** Auto-unlock users whose lock expiry has passed. Returns count unlocked. */
    public int autoUnlockExpiredLocks() {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            LocalDateTime sentinel = LocalDateTime.of(INDEFINITE_LOCK_YEAR, 1, 1, 0, 0);

            List<User> expiredLocks = session.createNativeQuery(
                    "SELECT * FROM cts_users " +
                            "WHERE status = :locked " +
                            "AND locked_until IS NOT NULL " +
                            "AND locked_until < :now " +
                            "AND locked_until < :sentinel",
                    User.class)
                    .setParameter("locked", UserStatus.LOCKED.name())
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("sentinel", sentinel)
                    .list();

            for (User u : expiredLocks) {
                clearLock(u);
                session.merge(u);
            }
            session.getTransaction().commit();
            return expiredLocks.size();
        }
    }

    // ── Audit ─────────────────────────────────────────────────────

    public void saveAuditLog(AuditLog auditLog) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            session.persist(auditLog);
            session.getTransaction().commit();
        } catch (Exception ex) {
            LOG.warn("Could not persist audit log: {}", ex.getMessage());
        }
    }

    // ── Private Helpers ───────────────────────────────────────────

    private User requireExistingUser(Session session, long userId) {
        User user = session.get(User.class, userId);
        if (user == null)
            throw new IllegalStateException("User #" + userId + " not found");
        return user;
    }

    private User requirePendingUser(Session session, long userId) {
        User user = requireExistingUser(session, userId);
        if (user.getStatus() != UserStatus.PENDING)
            throw new IllegalStateException("User #" + userId + " is not PENDING");
        return user;
    }

    private void applyActionToUser(User user, String action, Integer lockMinutes) {
        switch (action) {
            case "LOCK" -> {
                int minutes = lockMinutes != null ? lockMinutes : 0;
                user.setStatus(UserStatus.LOCKED);
                user.setFailedAttempts(0);
                user.setLockedUntil(computeLockExpiry(minutes));
            }
            case "UNLOCK", "OPEN", "ENABLE" -> clearLock(user);
            case "DISABLE" -> user.setStatus(UserStatus.DISABLED);
            case "TERMINATE" -> {
                user.setStatus(UserStatus.TERMINATED);
                user.setLockedUntil(null);
            }
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    private void clearLock(User user) {
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
    }

    private LocalDateTime computeLockExpiry(int lockMinutes) {
        return lockMinutes == 0
                ? LocalDateTime.of(INDEFINITE_LOCK_YEAR, 12, 31, 23, 59, 59)
                : LocalDateTime.now().plusMinutes(lockMinutes);
    }

    private String generateTempPassword() {
        String digits = buildRandomString("0123456789", 5);
        String chars = buildRandomString(CHARS, 3);
        return "Tmp@" + digits + chars;
    }

    private String buildRandomString(String charset, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            sb.append(charset.charAt(SECURE_RANDOM.nextInt(charset.length())));
        return sb.toString();
    }
}