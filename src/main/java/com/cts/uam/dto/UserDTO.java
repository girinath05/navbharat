package com.cts.uam.dto;

import com.cts.uam.model.User;
import java.time.LocalDateTime;

/**
 * UserDTO — safe data transfer object for the User entity.
 * passwordHash never leaves this class.
 */
public class UserDTO {

    private Long id;
    private String username;
    private String fullName;
    private String email;
    private String mobile;
    private Long roleId;
    private String roleLabel;
    private String status;
    private boolean active;
    private LocalDateTime lockedUntil;
    private int failedAttempts;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String requestedBy;
    private String rejectedReason;

    public UserDTO() {
    }

    public static UserDTO fromEntity(User user) {
        if (user == null)
            return null;

        UserDTO dto = new UserDTO();
        dto.id = user.getId();
        dto.username = user.getUsername();
        dto.fullName = user.getFullName();
        dto.email = user.getEmail();
        dto.mobile = user.getMobile();
        dto.roleId = user.getRoleId();
        dto.roleLabel = user.getRoleLabel();
        dto.status = user.getStatus().name();
        dto.active = user.isActive();
        dto.lockedUntil = user.getLockedUntil();
        dto.failedAttempts = user.getFailedAttempts();
        dto.lastLogin = user.getLastLogin();
        dto.createdAt = user.getCreatedAt();
        dto.updatedAt = user.getUpdatedAt();
        dto.requestedBy = user.getRequestedBy();
        dto.rejectedReason = user.getRejectedReason();
        return dto;
    }

    public boolean isLocked() {
        if ("LOCKED".equals(status))
            return true;
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
    }

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public String getRoleLabel() {
        return roleLabel;
    }

    public void setRoleLabel(String roleLabel) {
        this.roleLabel = roleLabel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }

    public void setRejectedReason(String rejectedReason) {
        this.rejectedReason = rejectedReason;
    }
}
