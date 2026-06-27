package com.cts.uam.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cts.uam.dao.UserDAO;
import com.cts.uam.dao.UserDAOImpl;
import com.cts.uam.dto.UserDTO;
import com.cts.uam.enums.AuthResultReason;
import com.cts.uam.enums.UserStatus;
import com.cts.uam.model.AuditLog;
import com.cts.uam.model.AuthResult;
import com.cts.uam.model.User;
import com.cts.util.SecurityUtil;

/**
 * UserServiceImpl - implements all user account business logic.
 *
 * Enforces:
 * - Maker-checker: the person who creates a request cannot approve it
 * themselves
 * - Password strength: min 8 chars, 1 uppercase, 1 digit, 1 special char
 * - Username rules: 3-50 chars, lowercase/digits and . _ @ - only
 * - Audit logging: every sensitive action writes to cts_audit_log
 */
public class UserServiceImpl implements UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserServiceImpl.class);
    private final UserDAO userDAO = new UserDAOImpl();

    // ── Authentication ────────────────────────────────────────────

    /**
     * Checks username and password, returns the result.
     *
     * Steps:
     * 1. Find user by username - fail with USER_NOT_FOUND if missing
     * 2. Check if account is locked - fail with ACCOUNT_LOCKED
     * 3. Check if account is active - fail with ACCOUNT_INACTIVE
     * 4. Check the password with BCrypt
     * 5. On wrong password: increment failed attempts (may trigger auto-lock)
     * 6. On success: reset failed attempts and record last login time
     */
    @Override
    public AuthResult authenticate(String username, String password) {
        Optional<User> found = userDAO.findByUsername(username);
        if (found.isEmpty())
            return AuthResult.failure(AuthResultReason.USER_NOT_FOUND);

        User user = found.get();

        if (user.isLocked())
            return AuthResult.failure(AuthResultReason.ACCOUNT_LOCKED);
        if (!user.isActive())
            return AuthResult.failure(AuthResultReason.ACCOUNT_INACTIVE);

        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            userDAO.incrementFailedAttempts(user);
            // Refresh the user from DB to get the updated lock status after increment
            User refreshed = userDAO.findByUsername(username).orElse(user);
            return refreshed.isLocked()
                    ? AuthResult.failure(AuthResultReason.ACCOUNT_LOCKED)
                    : AuthResult.wrongPassword(refreshed);
        }

        userDAO.recordSuccessfulLogin(user);
        return AuthResult.success(user);
    }

    // ── Lookup ────────────────────────────────────────────────────

    // Returns the raw User entity (contains passwordHash - use carefully)
    @Override
    public Optional<User> findByUsername(String username) {
        return userDAO.findByUsername(username);
    }

    // Returns a safe UserDTO (no passwordHash) for the given username
    @Override
    public Optional<UserDTO> findByUsernameAsDTO(String username) {
        return userDAO.findByUsername(username).map(UserDTO::fromEntity);
    }

    // ── Create User (needs checker approval) ──────────────────────

    /**
     * Creates a new user in PENDING state and writes an audit entry.
     * Validates the username before inserting.
     * The new user cannot log in until a checker approves.
     */
    @Override
    public void submitNewUserForApproval(String username, String fullName, String email,
            String mobile, Long roleId, String roleLabel, String requestedBy) {

        validateNewUsername(username);
        User blueprint = buildUserBlueprint(username, fullName, email, mobile, roleId, roleLabel);
        userDAO.insertUserAsPending(blueprint, requestedBy);
        logAudit(requestedBy, "USER_SUBMIT_PENDING", "New user '" + username + "' submitted for checker approval");
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /**
     * Checker approves a PENDING user.
     *
     * Checks:
     * - User exists and is PENDING
     * - Checker is not the same as the maker (four-eyes rule)
     *
     * On success: user status -> ACTIVE, a temp password is generated and returned.
     * The checker must give this temp password to the new user (shown once only).
     */
    @Override
    public String approveUser(long userId, String checkerId) {
        User user = requireExistingUser(userId);
        requirePendingStatus(user);
        blockSelfApproval(checkerId, user.getRequestedBy());

        String tempPassword = userDAO.approveUser(userId);
        LOG.info("UserServiceImpl: user #{} approved by '{}'", userId, checkerId);
        logAudit(checkerId, "USER_APPROVED", "User #" + userId + " (" + user.getUsername() + ") approved");
        return tempPassword;
    }

    /**
     * Checker rejects a PENDING user.
     *
     * Checks:
     * - Rejection reason must not be empty
     * - User exists and is PENDING
     * - Checker is not the same as the maker (four-eyes rule)
     *
     * The user row stays in DB with REJECTED status for audit purposes.
     */
    @Override
    public void rejectUser(long userId, String checkerId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty())
            throw new IllegalArgumentException("Rejection reason is required.");

        User user = requireExistingUser(userId);
        requirePendingStatus(user);
        blockSelfApproval(checkerId, user.getRequestedBy());

        userDAO.rejectUser(userId, rejectionReason.trim());
        LOG.info("UserServiceImpl: user #{} rejected by '{}' — reason: {}", userId, checkerId, rejectionReason);
        logAudit(checkerId, "USER_REJECTED",
                "User #" + userId + " (" + user.getUsername() + ") rejected — reason: " + rejectionReason.trim());
    }

    // ── Direct Status Actions (no maker-checker) ──────────────────

    /**
     * Apply a status change directly (no approval needed).
     * Supported: LOCK, UNLOCK, OPEN, ENABLE, DISABLE, TERMINATE.
     * lockMinutes only applies to LOCK (0 = indefinite).
     */
    @Override
    public void applyStatusAction(long userId, String action, Integer lockMinutes) {
        User user = requireExistingUser(userId);
        userDAO.applyStatusAction(userId, action, lockMinutes);
        LOG.info("UserServiceImpl: user #{} action '{}' applied directly", userId, action);
        logAudit(null, "USER_STATUS_" + action,
                "User #" + userId + " (" + user.getUsername() + ") action '" + action + "'"
                        + (lockMinutes != null ? " for " + lockMinutes + " min" : ""));
    }

    // ── Edit User ─────────────────────────────────────────────────

    // Update user profile fields directly (no approval needed). Full name is
    // required.
    @Override
    public void editUser(long userId, String fullName, String email,
            String mobile, Long roleId, String roleLabel) {
        if (fullName == null || fullName.isEmpty())
            throw new IllegalArgumentException("Full name is required.");
        userDAO.updateUserDetails(userId, fullName, email, mobile, roleId, roleLabel);
        LOG.info("UserServiceImpl: user #{} details updated", userId);
        logAudit(null, "USER_EDITED", "User #" + userId + " details updated (name='" + fullName + "')");
    }

    // ── Change Password ───────────────────────────────────────────

    // Admin resets a user's password (no current password needed, but strength is
    // checked)
    @Override
    public void changePassword(long userId, String newPassword) {
        validatePasswordStrength(newPassword);
        String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        userDAO.updatePasswordHash(userId, hash);
        LOG.info("UserServiceImpl: password changed for user #{}", userId);
        logAudit(null, "USER_PASSWORD_RESET_BY_ADMIN", "Password reset for user #" + userId);
    }

    /**
     * User changes their own password.
     * Must provide the correct current password first (verified with BCrypt).
     * New password must meet strength requirements.
     */
    @Override
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        User user = userDAO.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        if (!BCrypt.checkpw(currentPassword, user.getPasswordHash()))
            throw new IllegalArgumentException("Current password is incorrect.");

        validatePasswordStrength(newPassword);

        String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        userDAO.updatePasswordHash(user.getId(), newHash);
        LOG.info("UserServiceImpl: user '{}' changed own password", username);
        logAudit(username, "USER_PASSWORD_SELF_CHANGE", "User '" + username + "' changed their own password");
    }

    // ── Pending List ──────────────────────────────────────────────

    // Get all pending users as DTOs (no pagination)
    @Override
    public List<UserDTO> getPendingUsers() {
        return userDAO.findPendingUsers().stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // Get paginated pending users as DTOs
    @Override
    public List<UserDTO> getPendingUsers(int offset, int limit) {
        return userDAO.findPendingUsers(offset, limit).stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // Count of users waiting for approval
    @Override
    public long countPendingUsers() {
        return userDAO.countPendingUsers();
    }

    // ── Search ────────────────────────────────────────────────────

    // Search users with filters, returns raw entity list
    @Override
    public List<User> searchUsers(String usernameFilter, String nameFilter, String statusFilter) {
        return userDAO.searchUsers(usernameFilter, nameFilter, parseStatus(statusFilter));
    }

    // Same but returns DTOs (safe for UI - no passwordHash)
    @Override
    public List<UserDTO> searchUsersAsDTO(String usernameFilter, String nameFilter, String statusFilter) {
        return userDAO.searchUsers(usernameFilter, nameFilter, parseStatus(statusFilter))
                .stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // Same but paginated
    @Override
    public List<UserDTO> searchUsersAsDTO(String usernameFilter, String nameFilter, String statusFilter,
            int offset, int limit) {
        return userDAO.searchUsers(usernameFilter, nameFilter, parseStatus(statusFilter), offset, limit)
                .stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // Count of users matching filters (for pagination math)
    @Override
    public long countUsers(String usernameFilter, String nameFilter, String statusFilter) {
        return userDAO.countUsers(usernameFilter, nameFilter, parseStatus(statusFilter));
    }

    // ── Lock Helpers (used by scheduler) ─────────────────────────

    // Lock a user for N minutes (0 = indefinite)
    @Override
    public void lockUser(long userId, int lockMinutes) {
        userDAO.lockUser(userId, lockMinutes);
    }

    // Unlock a user and restore their ACTIVE status
    @Override
    public void unlockUser(long userId) {
        userDAO.unlockUser(userId);
    }

    // ── Audit ─────────────────────────────────────────────────────

    /**
     * Internal helper: writes an audit entry for a sensitive UAM action.
     * actor = who did it (if null, falls back to the currently logged-in user).
     * Errors are swallowed - audit must never block the real business action.
     */
    private void logAudit(String actor, String eventType, String details) {
        String who = actor != null ? actor : SecurityUtil.getCurrentUserId();
        try {
            userDAO.saveAuditLog(new AuditLog(who, eventType, null, null, details));
        } catch (Exception ex) {
            LOG.warn("Could not save audit log [{}] for '{}': {}", eventType, who, ex.getMessage());
        }
    }

    // Public version used by LoginComposer to log login/logout events
    @Override
    public void saveAuditLog(AuditLog auditLog) {
        try {
            userDAO.saveAuditLog(auditLog);
        } catch (Exception ex) {
            LOG.warn("Could not save audit log: {}", ex.getMessage());
        }
    }

    // ── Validation (public — used by Composer) ────────────────────

    /**
     * Validates a proposed new username.
     * Returns an error message if invalid, or null if the username can be used.
     * Rules: 3-50 chars, lowercase letters/digits and . _ @ - only, must not
     * already exist.
     */
    @Override
    public String validateNewUsername(String username) {
        if (username == null || username.isEmpty())
            return "User ID is required.";
        if (!username.matches("[a-z0-9._@-]{3,50}"))
            return "User ID: 3–50 chars, lowercase/digits and . _ @ - only.";
        if (userDAO.findByUsername(username).isPresent())
            return "User ID \"" + username + "\" is already taken.";
        return null;
    }

    // ── Private Helpers ───────────────────────────────────────────

    // Load a user by id, throws if not found
    private User requireExistingUser(long userId) {
        User user = userDAO.findById(userId);
        if (user == null)
            throw new IllegalStateException("User #" + userId + " not found");
        return user;
    }

    // Throws if the user is not in PENDING status
    private void requirePendingStatus(User user) {
        if (user.getStatus() != UserStatus.PENDING)
            throw new IllegalStateException("User #" + user.getId() + " is not PENDING");
    }

    /**
     * Blocks the four-eyes violation: maker and checker must be different people.
     * If checkerId equals requestedBy (the maker), throws an error.
     */
    private void blockSelfApproval(String checkerId, String requestedBy) {
        if (checkerId != null && checkerId.equals(requestedBy))
            throw new IllegalStateException(
                    "Four-eyes violation: maker and checker cannot be the same user.");
    }

    /**
     * Validates password strength.
     * Rules: min 8 chars, at least 1 uppercase letter, 1 digit, 1 special
     * character.
     * Throws IllegalArgumentException if not met.
     */
    private void validatePasswordStrength(String password) {
        if (!password.matches("^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$"))
            throw new IllegalArgumentException(
                    "Password must be 8+ chars with 1 uppercase, 1 digit, and 1 special character.");
    }

    // Builds an unsaved User object from the given field values (no status/password
    // set yet)
    private User buildUserBlueprint(String username, String fullName, String email,
            String mobile, Long roleId, String roleLabel) {
        User blueprint = new User();
        blueprint.setUsername(username);
        blueprint.setFullName(fullName);
        blueprint.setEmail(email != null ? email : "");
        blueprint.setMobile(mobile);
        blueprint.setRoleId(roleId);
        blueprint.setRoleLabel(roleLabel != null ? roleLabel : "");
        return blueprint;
    }

    // Converts a status string (e.g. "ACTIVE") to UserStatus enum, returns null if
    // empty/invalid
    private UserStatus parseStatus(String status) {
        if (status == null || status.isEmpty())
            return null;
        try {
            return UserStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
