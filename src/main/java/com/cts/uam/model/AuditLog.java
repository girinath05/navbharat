package com.cts.uam.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records every login attempt (success & failure) for audit trail.
 */
@Entity
@Table(name = "cts_audit_log", indexes = {
        @Index(name = "idx_audit_username", columnList = "username"),
        @Index(name = "idx_audit_time", columnList = "event_time")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType; // LOGIN_SUCCESS | LOGIN_FAILURE | ACCOUNT_LOCKED | LOGOUT

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "details", length = 500)
    private String details;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @PrePersist
    protected void onCreate() {
        eventTime = LocalDateTime.now();
    }

    // ── Constructors ──────────────────────────────────────────────────────
    public AuditLog() {
    }

    public AuditLog(String username, String eventType, String ipAddress,
            String userAgent, String details) {
        this.username = username;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.details = details;
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEventType() {
        return eventType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDetails() {
        return details;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }
}