package com.cts.uam.service;

import java.util.List;
import java.util.Optional;

import com.cts.uam.dto.UserDTO;
import com.cts.uam.model.AuditLog;
import com.cts.uam.model.AuthResult;
import com.cts.uam.model.User;

/**
 * UserService - business logic layer for all user account operations.
 *
 * Implementation: UserServiceImpl
 *
 * This layer sits between the UI (Composers) and the DB (UserDAO).
 * It enforces business rules like:
 * - Maker-checker (self-approval blocked)
 * - Password strength rules
 * - Username format and uniqueness
 * - Logging audit entries for every sensitive action
 */
public interface UserService {

        // ── Authentication ────────────────────────────────────────────

        // Checks username + password and returns the result (success or failure reason)
        AuthResult authenticate(String username, String password);

        // ── Lookup ────────────────────────────────────────────────────

        // Find a user entity by username (for internal use)
        Optional<User> findByUsername(String username);

        // Find a user DTO by username (safe version without passwordHash)
        Optional<UserDTO> findByUsernameAsDTO(String username);

        // ── Create User (needs checker approval) ──────────────────────

        // Submit a new user for checker approval - inserts with PENDING status
        void submitNewUserForApproval(String username, String fullName, String email,
                        String mobile, Long roleId, String roleLabel, String requestedBy);

        // ── Approve / Reject ──────────────────────────────────────────

        // Checker approves a PENDING user -> user becomes ACTIVE, returns the temp
        // password
        String approveUser(long userId, String checkerId);

        // Checker rejects a PENDING user -> status becomes REJECTED, reason is stored
        void rejectUser(long userId, String checkerId, String rejectionReason);

        // ── Direct Status Actions (no maker-checker) ──────────────────

        // Apply a status change directly (LOCK, UNLOCK, DISABLE, TERMINATE,
        // OPEN/ENABLE)
        void applyStatusAction(long userId, String action, Integer lockMinutes);

        // ── Edit User ─────────────────────────────────────────────────

        // Update an existing user's profile fields (no approval needed)
        void editUser(long userId, String fullName, String email,
                        String mobile, Long roleId, String roleLabel);

        // ── Change Password ───────────────────────────────────────────

        // Admin resets a user's password directly (no current password required)
        void changePassword(long userId, String newPassword);

        // User changes their own password (must provide current password to verify)
        void changeOwnPassword(String username, String currentPassword, String newPassword);

        // ── Pending List ──────────────────────────────────────────────

        // Get all users waiting for approval
        List<UserDTO> getPendingUsers();

        // Same but paginated
        List<UserDTO> getPendingUsers(int offset, int limit);

        // Count of pending users (for pagination and badge display)
        long countPendingUsers();

        // ── Search ────────────────────────────────────────────────────

        // Search users with filters, returns entity list
        List<User> searchUsers(String usernameFilter, String nameFilter, String statusFilter);

        // Same but returns DTOs (safe for UI)
        List<UserDTO> searchUsersAsDTO(String usernameFilter, String nameFilter, String statusFilter);

        // Same but paginated
        List<UserDTO> searchUsersAsDTO(String usernameFilter, String nameFilter, String statusFilter,
                        int offset, int limit);

        // Count matching users for pagination
        long countUsers(String usernameFilter, String nameFilter, String statusFilter);

        // ── Lock Helpers (used by scheduler) ─────────────────────────

        // Lock a user for a given number of minutes
        void lockUser(long userId, int lockMinutes);

        // Unlock a user immediately
        void unlockUser(long userId);

        // ── Audit ─────────────────────────────────────────────────────

        // Save an audit log entry (used by LoginComposer for login events)
        void saveAuditLog(AuditLog auditLog);

        // ── Validation (public — used by Composer) ────────────────────

        /**
         * Validates a proposed new username.
         * Returns an error message string if invalid, or null if the username is
         * acceptable.
         * Rules: 3-50 chars, lowercase/digits and . _ @ - only, must not already exist.
         */
        String validateNewUsername(String username);
}
