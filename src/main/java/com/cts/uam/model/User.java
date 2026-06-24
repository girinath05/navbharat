package com.cts.uam.model;

import com.cts.uam.enums.UserStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cts_users", indexes = {
        @Index(name = "idx_username", columnList = "username", unique = true)
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "role_label", length = 50)
    private String roleLabel;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "mobile", length = 15)
    private String mobile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.PENDING;

    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "requested_by", length = 50)
    private String requestedBy;

    /** Populated on rejection — stored in DB for audit. */
    @Column(name = "rejected_reason", columnDefinition = "TEXT")
    private String rejectedReason;

    // ── Security ──────────────────────────────────────────────────

    @Column(name = "failed_attempts")
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    // ── Audit ─────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Constructors ──────────────────────────────────────────────
    public User() {
    }

    // ── Derived methods (not persisted) ───────────────────────────

    /** True only if status is ACTIVE. */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isLocked() {
        if (status == UserStatus.LOCKED)
            return true;
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
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

    public void setUsername(String v) {
        this.username = v;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String v) {
        this.passwordHash = v;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String v) {
        this.fullName = v;
    }

    public String getRoleLabel() {
        return roleLabel;
    }

    public void setRoleLabel(String v) {
        this.roleLabel = v;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        this.email = v;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String v) {
        this.mobile = v;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus v) {
        this.status = v;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long v) {
        this.roleId = v;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String v) {
        this.requestedBy = v;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }

    public void setRejectedReason(String v) {
        this.rejectedReason = v;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int v) {
        this.failedAttempts = v;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime v) {
        this.lockedUntil = v;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime v) {
        this.lastLogin = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

}
