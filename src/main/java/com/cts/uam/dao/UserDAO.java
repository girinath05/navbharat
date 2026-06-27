package com.cts.uam.dao;

import java.util.List;
import java.util.Optional;

import com.cts.uam.enums.UserStatus;
import com.cts.uam.model.AuditLog;
import com.cts.uam.model.User;

/**
 * UserDAO - defines all database operations for User accounts.
 *
 * Implementation: UserDAOImpl
 *
 * Covers:
 * - Search and filter users
 * - Create a new user in PENDING state
 * - Approve or reject a pending user
 * - Apply status actions (lock, unlock, disable, terminate)
 * - Track login success and failure
 * - Auto-unlock users whose timed lock has expired
 * - Save audit log entries
 */
public interface UserDAO {

        // ── Search / Filter ───────────────────────────────────────────

        // Find users matching given filters - skips PENDING and REJECTED by default
        List<User> searchUsers(String usernameFilter, String nameFilter, UserStatus statusFilter);

        // Same as above but with pagination (offset = start row, limit = max rows)
        List<User> searchUsers(String usernameFilter, String nameFilter, UserStatus statusFilter,
                        int offset, int limit);

        // Count of users matching the same filters - used for pagination math
        long countUsers(String usernameFilter, String nameFilter, UserStatus statusFilter);

        // Find one user by their exact username - returns empty if not found
        Optional<User> findByUsername(String username);

        // Find one user by their numeric DB id - returns null if not found
        User findById(long userId);

        // ── Create User (PENDING) ─────────────────────────────────────

        // Insert a new user with PENDING status - password is blank until checker
        // approves
        void insertUserAsPending(User blueprint, String requestedBy);

        // ── Approve / Reject ──────────────────────────────────────────

        // Approve a PENDING user -> sets status to ACTIVE, generates a temp password,
        // returns it
        String approveUser(long userId);

        // Reject a PENDING user -> sets status to REJECTED, stores the reason
        void rejectUser(long userId, String rejectionReason);

        // ── Pending List ──────────────────────────────────────────────

        // Get all users waiting for approval (status = PENDING)
        List<User> findPendingUsers();

        // Same but paginated
        List<User> findPendingUsers(int offset, int limit);

        // Count of pending users (for pagination and badge display)
        long countPendingUsers();

        // ── Direct Status Actions ─────────────────────────────────────

        // Apply a status change action to a user (LOCK, UNLOCK, DISABLE, TERMINATE,
        // OPEN/ENABLE)
        void applyStatusAction(long userId, String action, Integer lockMinutes);

        // ── Edit User Details ─────────────────────────────────────────

        // Update the user's profile fields (name, email, mobile, role)
        void updateUserDetails(long userId, String fullName, String email,
                        String mobile, Long roleId, String roleLabel);

        // Replace the stored password hash (used after admin reset or user self-change)
        void updatePasswordHash(long userId, String newPasswordHash);

        // ── Login Tracking ────────────────────────────────────────────

        // Lock a user for a given number of minutes (0 = indefinite lock)
        void lockUser(long userId, int lockMinutes);

        // Unlock a locked user and reset their failed attempt counter
        void unlockUser(long userId);

        // Add 1 to failed attempt count; locks user automatically if max attempts
        // reached
        void incrementFailedAttempts(User user);

        // Reset failed attempts to 0 and update last login timestamp on successful
        // login
        void recordSuccessfulLogin(User user);

        // Find all users whose timed lock has expired and unlock them; returns count
        // unlocked
        int autoUnlockExpiredLocks();

        // ── Audit ─────────────────────────────────────────────────────

        // Write one audit log entry to the DB
        void saveAuditLog(AuditLog auditLog);
}
