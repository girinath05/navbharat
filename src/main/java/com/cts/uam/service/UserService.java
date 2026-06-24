package com.cts.uam.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cts.uam.dao.UserDAO;
import com.cts.uam.dto.UserDTO;
import com.cts.uam.enums.AuthResultReason;
import com.cts.uam.enums.UserStatus;
import com.cts.uam.model.AuditLog;
import com.cts.uam.model.AuthResult;
import com.cts.uam.model.User;

public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);
    private final UserDAO userDAO = new UserDAO();

    // ── Authentication ────────────────────────────────────────────

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
            User refreshed = userDAO.findByUsername(username).orElse(user);
            return refreshed.isLocked()
                    ? AuthResult.failure(AuthResultReason.ACCOUNT_LOCKED)
                    : AuthResult.wrongPassword(refreshed);
        }

        userDAO.recordSuccessfulLogin(user);
        return AuthResult.success(user);
    }

    // ── Lookup ────────────────────────────────────────────────────

    public Optional<User> findByUsername(String username) {
        return userDAO.findByUsername(username);
    }

    public Optional<UserDTO> findByUsernameAsDTO(String username) {
        return userDAO.findByUsername(username).map(UserDTO::fromEntity);
    }

    // ── Create User (needs checker approval) ──────────────────────

    /**
     * Submits a new user for checker approval — inserts as PENDING.
     * Returns plain-text temp password to be shown at approval time.
     */
    public void submitNewUserForApproval(String username, String fullName, String email,
            String mobile, Long roleId, String roleLabel, String requestedBy) {

        validateNewUsername(username);
        User blueprint = buildUserBlueprint(username, fullName, email, mobile, roleId, roleLabel);
        userDAO.insertUserAsPending(blueprint, requestedBy);
    }

    // ── Approve / Reject ──────────────────────────────────────────

    /**
     * Checker approves a PENDING user → ACTIVE.
     * Returns tempPassword (passed in from session) so checker can display it once.
     */
    public String approveUser(long userId, String checkerId) {
        User user = requireExistingUser(userId);
        requirePendingStatus(user);
        blockSelfApproval(checkerId, user.getRequestedBy());

        String tempPassword = userDAO.approveUser(userId); // ← DB se aata hai
        LOG.info("UserService: user #{} approved by '{}'", userId, checkerId);
        return tempPassword;
    }

    /** Checker rejects a PENDING user → REJECTED. Keeps row for audit. */
    public void rejectUser(long userId, String checkerId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty())
            throw new IllegalArgumentException("Rejection reason is required.");

        User user = requireExistingUser(userId);
        requirePendingStatus(user);
        blockSelfApproval(checkerId, user.getRequestedBy());

        userDAO.rejectUser(userId, rejectionReason.trim());
        LOG.info("UserService: user #{} rejected by '{}' — reason: {}", userId, checkerId, rejectionReason);
    }

    // ── Direct Status Actions (no maker-checker) ──────────────────

    /**
     * Apply status action immediately: LOCK | UNLOCK | DISABLE | ENABLE | TERMINATE
     * | OPEN.
     * No checker approval needed.
     */
    public void applyStatusAction(long userId, String action, Integer lockMinutes) {
        requireExistingUser(userId);
        userDAO.applyStatusAction(userId, action, lockMinutes);
        LOG.info("UserService: user #{} action '{}' applied directly", userId, action);
    }

    // ── Edit User ─────────────────────────────────────────────────

    public void editUser(long userId, String fullName, String email,
            String mobile, Long roleId, String roleLabel) {
        if (fullName == null || fullName.isEmpty())
            throw new IllegalArgumentException("Full name is required.");
        userDAO.updateUserDetails(userId, fullName, email, mobile, roleId, roleLabel);
        LOG.info("UserService: user #{} details updated", userId);
    }

    // ── Change Password ───────────────────────────────────────────

    public void changePassword(long userId, String newPassword) {
        validatePasswordStrength(newPassword);
        String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        userDAO.updatePasswordHash(userId, hash);
        LOG.info("UserService: password changed for user #{}", userId);
    }

    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        User user = userDAO.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        if (!BCrypt.checkpw(currentPassword, user.getPasswordHash()))
            throw new IllegalArgumentException("Current password is incorrect.");

        validatePasswordStrength(newPassword);

        String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        userDAO.updatePasswordHash(user.getId(), newHash);
        LOG.info("UserService: user '{}' changed own password", username);
    }
    // ── Pending List ──────────────────────────────────────────────

    public List<UserDTO> getPendingUsers() {
        return userDAO.findPendingUsers().stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // ── Search ────────────────────────────────────────────────────

    public List<User> searchUsers(String usernameFilter, String nameFilter, String statusFilter) {
        return userDAO.searchUsers(usernameFilter, nameFilter, parseStatus(statusFilter));
    }

    public List<UserDTO> searchUsersAsDTO(String usernameFilter, String nameFilter, String statusFilter) {
        return userDAO.searchUsers(usernameFilter, nameFilter, parseStatus(statusFilter))
                .stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // ── Lock Helpers (used by scheduler) ─────────────────────────

    public void lockUser(long userId, int lockMinutes) {
        userDAO.lockUser(userId, lockMinutes);
    }

    public void unlockUser(long userId) {
        userDAO.unlockUser(userId);
    }

    // ── Audit ─────────────────────────────────────────────────────

    public void saveAuditLog(AuditLog auditLog) {
        try {
            userDAO.saveAuditLog(auditLog);
        } catch (Exception ex) {
            LOG.warn("Could not save audit log: {}", ex.getMessage());
        }
    }

    // ── Validation (public — used by Composer) ────────────────────

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

    private User requireExistingUser(long userId) {
        User user = userDAO.findById(userId);
        if (user == null)
            throw new IllegalStateException("User #" + userId + " not found");
        return user;
    }

    private void requirePendingStatus(User user) {
        if (user.getStatus() != UserStatus.PENDING)
            throw new IllegalStateException("User #" + user.getId() + " is not PENDING");
    }

    private void blockSelfApproval(String checkerId, String requestedBy) {
        if (checkerId != null && checkerId.equals(requestedBy))
            throw new IllegalStateException(
                    "Four-eyes violation: maker and checker cannot be the same user.");
    }

    private void validatePasswordStrength(String password) {
        if (!password.matches("^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$"))
            throw new IllegalArgumentException(
                    "Password must be 8+ chars with 1 uppercase, 1 digit, and 1 special character.");
    }

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