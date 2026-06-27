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

/**
 * UserDAOImpl - handles all DB operations for User accounts.
 *
 * Each public method opens its own Hibernate session and closes it after use
 * (try-with-resources). Write methods also begin and commit a transaction.
 *
 * Key rules enforced here:
 * - Max 5 failed login attempts before auto-lock (AUTO_LOCK_MINUTES = 30)
 * - Lock duration 0 means indefinitely locked (year 9999 sentinel)
 * - Temp passwords follow pattern: "Tmp@" + 5 digits + 3 random chars
 */
public class UserDAOImpl implements UserDAO {

    private static final Logger LOG = LoggerFactory.getLogger(UserDAOImpl.class);

    // Used to generate cryptographically secure random temp passwords
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Character set for the random part of temp passwords
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // Sentinel year - lockedUntil set to this year means "locked forever"
    private static final int INDEFINITE_LOCK_YEAR = 9999;

    // How long to auto-lock after MAX_FAILED_ATTEMPTS (in minutes)
    private static final int AUTO_LOCK_MINUTES = 30;

    // How many wrong passwords before the account is automatically locked
    private static final int MAX_FAILED_ATTEMPTS = 5;

    // ── Search / Filter ───────────────────────────────────────────

    /**
     * Returns users matching the given filters.
     * PENDING and REJECTED users are always excluded from results.
     * Empty string filters are ignored (not added to the WHERE clause).
     */
    @Override
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

    /**
     * Same as searchUsers above but with LIMIT and OFFSET for pagination.
     * offset = how many rows to skip, limit = how many rows to return.
     */
    @Override
    public List<User> searchUsers(String usernameFilter, String nameFilter, UserStatus statusFilter,
            int offset, int limit) {
        try (Session session = HibernateUtil.getSession()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM cts_users WHERE status NOT IN (:pending, :rejected)");
            if (!usernameFilter.isEmpty())
                sql.append(" AND lower(username) LIKE :uid");
            if (!nameFilter.isEmpty())
                sql.append(" AND lower(full_name) LIKE :nm");
            if (statusFilter != null)
                sql.append(" AND status = :st");
            sql.append(" ORDER BY username LIMIT :lim OFFSET :off");

            var query = session.createNativeQuery(sql.toString(), User.class);
            query.setParameter("pending", UserStatus.PENDING.name());
            query.setParameter("rejected", UserStatus.REJECTED.name());
            if (!usernameFilter.isEmpty())
                query.setParameter("uid", "%" + usernameFilter.toLowerCase() + "%");
            if (!nameFilter.isEmpty())
                query.setParameter("nm", "%" + nameFilter.toLowerCase() + "%");
            if (statusFilter != null)
                query.setParameter("st", statusFilter.name());
            query.setParameter("lim", limit);
            query.setParameter("off", offset);
            return query.list();
        }
    }

    /**
     * Returns the total count of users matching the filters.
     * Used by the pagination logic to calculate total pages.
     */
    @Override
    public long countUsers(String usernameFilter, String nameFilter, UserStatus statusFilter) {
        try (Session session = HibernateUtil.getSession()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT COUNT(*) FROM cts_users WHERE status NOT IN (:pending, :rejected)");
            if (!usernameFilter.isEmpty())
                sql.append(" AND lower(username) LIKE :uid");
            if (!nameFilter.isEmpty())
                sql.append(" AND lower(full_name) LIKE :nm");
            if (statusFilter != null)
                sql.append(" AND status = :st");

            var query = session.createNativeQuery(sql.toString());
            query.setParameter("pending", UserStatus.PENDING.name());
            query.setParameter("rejected", UserStatus.REJECTED.name());
            if (!usernameFilter.isEmpty())
                query.setParameter("uid", "%" + usernameFilter.toLowerCase() + "%");
            if (!nameFilter.isEmpty())
                query.setParameter("nm", "%" + nameFilter.toLowerCase() + "%");
            if (statusFilter != null)
                query.setParameter("st", statusFilter.name());
            return ((Number) query.getSingleResult()).longValue();
        }
    }

    // Find a user by exact username match - returns Optional.empty() if not found
    @Override
    public Optional<User> findByUsername(String username) {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_users WHERE username = :username", User.class)
                    .setParameter("username", username)
                    .uniqueResultOptional();
        }
    }

    // Find a user by numeric id - returns null if not found
    @Override
    public User findById(long userId) {
        try (Session session = HibernateUtil.getSession()) {
            return session.get(User.class, userId);
        }
    }

    // ── Create User (PENDING) ─────────────────────────────────────

    /**
     * Inserts a new user with status PENDING.
     * Password is blank - it gets set when the checker approves.
     * requestedBy = the maker's username who submitted this request.
     */
    @Override
    public void insertUserAsPending(User blueprint, String requestedBy) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            blueprint.setStatus(UserStatus.PENDING);
            blueprint.setFailedAttempts(0);
            blueprint.setRequestedBy(requestedBy);
            blueprint.setPasswordHash(""); // blank until approved

            session.persist(blueprint);
            session.getTransaction().commit();
            LOG.info("UserDAOImpl: user '{}' inserted as PENDING by '{}'",
                    blueprint.getUsername(), requestedBy);
        }
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /**
     * Approves a PENDING user.
     * Generates a random temp password, hashes it with BCrypt, sets status =
     * ACTIVE.
     * Returns the plain-text temp password (shown once to the checker).
     *
     * Throws IllegalStateException if user is not PENDING.
     */
    @Override
    public String approveUser(long userId) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            User user = requirePendingUser(session, userId);

            String tempPassword = generateTempPassword();
            user.setPasswordHash(BCrypt.hashpw(tempPassword, BCrypt.gensalt()));
            user.setStatus(UserStatus.ACTIVE);

            session.merge(user);
            session.getTransaction().commit();

            LOG.info("UserDAOImpl: user '{}' approved, temp password set", user.getUsername());
            return tempPassword;
        }
    }

    /**
     * Rejects a PENDING user.
     * Sets status = REJECTED and stores the reason.
     * The row is kept in DB for audit purposes.
     *
     * Throws IllegalStateException if user is not PENDING.
     */
    @Override
    public void rejectUser(long userId, String rejectionReason) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User user = requirePendingUser(session, userId);
            user.setStatus(UserStatus.REJECTED);
            user.setRejectedReason(rejectionReason);
            session.merge(user);
            session.getTransaction().commit();
            LOG.info("UserDAOImpl: user '{}' REJECTED — reason: {}", user.getUsername(), rejectionReason);
        }
    }

    // ── Pending List ──────────────────────────────────────────────

    // Get all users with status = PENDING, sorted by username
    @Override
    public List<User> findPendingUsers() {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_users WHERE status = :pending ORDER BY username",
                    User.class)
                    .setParameter("pending", UserStatus.PENDING.name())
                    .list();
        }
    }

    // Same but paginated - offset rows from the start, up to limit rows
    @Override
    public List<User> findPendingUsers(int offset, int limit) {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT * FROM cts_users WHERE status = :pending ORDER BY username LIMIT :lim OFFSET :off",
                    User.class)
                    .setParameter("pending", UserStatus.PENDING.name())
                    .setParameter("lim", limit)
                    .setParameter("off", offset)
                    .list();
        }
    }

    // Count of users with status = PENDING
    @Override
    public long countPendingUsers() {
        try (Session session = HibernateUtil.getSession()) {
            return ((Number) session.createNativeQuery(
                    "SELECT COUNT(*) FROM cts_users WHERE status = :pending")
                    .setParameter("pending", UserStatus.PENDING.name())
                    .getSingleResult()).longValue();
        }
    }

    // ── Direct Status Actions ─────────────────────────────────────

    /**
     * Applies a status change action directly to a user (no maker-checker
     * workflow).
     * Supported actions: LOCK, UNLOCK, OPEN, ENABLE, DISABLE, TERMINATE.
     * lockMinutes is only used for LOCK (0 = indefinite, positive = timed).
     */
    @Override
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

    /**
     * Updates profile fields for an existing user.
     * Role is only updated if roleId is not null.
     */
    @Override
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

    // Replaces the stored password hash (BCrypt hash of the new password)
    @Override
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

    /**
     * Locks a user for the given number of minutes.
     * lockMinutes = 0 means lock indefinitely (sets lockedUntil to year 9999).
     */
    @Override
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

    // Removes the lock on a user, resets failed attempts, sets status back to
    // ACTIVE
    @Override
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

    /**
     * Adds 1 to the user's failed attempt counter.
     * If the counter reaches MAX_FAILED_ATTEMPTS (5), the account is auto-locked
     * for AUTO_LOCK_MINUTES (30) minutes.
     */
    @Override
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

    /**
     * Resets failed attempts to 0, clears any lock, and records the current time as
     * last login.
     * Called after a successful password check.
     */
    @Override
    public void recordSuccessfulLogin(User user) {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();
            User managed = session.get(User.class, user.getId());
            if (managed == null)
                return;
            managed.setFailedAttempts(0);
            managed.setLockedUntil(null);
            managed.setLastLogin(LocalDateTime.now());
            // If status was LOCKED (e.g. timed lock expired during login), restore to
            // ACTIVE
            if (managed.getStatus() == UserStatus.LOCKED) {
                managed.setStatus(UserStatus.ACTIVE);
            }
            session.merge(managed);
            session.getTransaction().commit();
        }
    }

    /**
     * Finds all users who are LOCKED but whose timed lock has now expired,
     * then unlocks them automatically. Ignores indefinitely-locked users (year
     * 9999).
     *
     * Returns the count of users that were unlocked.
     * Called by the UserUnlockScheduler on a schedule.
     */
    @Override
    public int autoUnlockExpiredLocks() {
        try (Session session = HibernateUtil.getSession()) {
            session.beginTransaction();

            // Sentinel time to detect indefinite locks - do NOT unlock these
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

    /**
     * Saves one audit log entry to the DB.
     * Errors are logged as warnings but do not propagate - audit must never
     * block the real business action that triggered it.
     */
    @Override
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

    // Load a user by id, throws if not found
    private User requireExistingUser(Session session, long userId) {
        User user = session.get(User.class, userId);
        if (user == null)
            throw new IllegalStateException("User #" + userId + " not found");
        return user;
    }

    // Load a user by id, throws if not found or not in PENDING status
    private User requirePendingUser(Session session, long userId) {
        User user = requireExistingUser(session, userId);
        if (user.getStatus() != UserStatus.PENDING)
            throw new IllegalStateException("User #" + userId + " is not PENDING");
        return user;
    }

    /**
     * Applies the requested action to the user object in memory.
     * The caller is responsible for persisting the change.
     */
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

    // Resets lock fields and sets status back to ACTIVE
    private void clearLock(User user) {
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
    }

    /**
     * Calculates when a lock should expire.
     * lockMinutes = 0 -> lock until year 9999 (indefinite)
     * lockMinutes > 0 -> lock until now + N minutes
     */
    private LocalDateTime computeLockExpiry(int lockMinutes) {
        return lockMinutes == 0
                ? LocalDateTime.of(INDEFINITE_LOCK_YEAR, 12, 31, 23, 59, 59)
                : LocalDateTime.now().plusMinutes(lockMinutes);
    }

    /**
     * Generates a temp password in the format: Tmp@DDDDDCCC
     * D = random digit, C = random letter or digit.
     * Example: Tmp@48271xKa
     */
    private String generateTempPassword() {
        String digits = buildRandomString("0123456789", 5);
        String chars = buildRandomString(CHARS, 3);
        return "Tmp@" + digits + chars;
    }

    // Picks `length` random characters from `charset` and returns them as a string
    private String buildRandomString(String charset, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            sb.append(charset.charAt(SECURE_RANDOM.nextInt(charset.length())));
        return sb.toString();
    }
}
