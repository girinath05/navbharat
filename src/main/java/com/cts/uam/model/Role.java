package com.cts.uam.model;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import com.cts.uam.enums.RoleStatus;

@Entity
@Table(name = "cts_roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_name", unique = true, nullable = false)
    private String roleName;

    @Column(name = "description")
    private String description;

    /**
     * New roles start as PENDING until approved (ACTIVE) or rejected (REJECTED).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RoleStatus status = RoleStatus.PENDING;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Populated on rejection — stored in DB for audit. */
    @Column(name = "rejected_reason", columnDefinition = "TEXT")
    private String rejectedReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Derived (not persisted) ───────────────────────────────────

    public boolean isActive() {
        return status == RoleStatus.ACTIVE;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public RoleStatus getStatus() { return status; }
    public void setStatus(RoleStatus status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }
}
