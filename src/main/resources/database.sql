-- ================================================================
-- NavbharatCTS — Full Database Schema + Seed Data
-- Simplified Maker-Checker Workflow for User & Role Management
-- Target: PostgreSQL
-- UPDATED: Inward module realigned to sidebar v2
--   - Removed: INWARD_UPLOAD_SERVICE, INWARD_MICR_SERVICE,
--              INWARD_RETURN_QUEUE, INWARD_CHECKER_QUEUE,
--              INWARD_ESCALATION_QUEUE, INWARD_MAKER_REPORT,
--              INWARD_CHECKER_REPORT_1/2/3
--   - Added:   INWARD_MAKER_DASHBOARD_VIEW, INWARD_VERIFIER1_DASHBOARD_VIEW,
--              INWARD_VERIFIER2_DASHBOARD_VIEW, INWARD_UPLOAD_CHEQUES,
--              INWARD_RETURNED_CHEQUES, INWARD_RESUBMITTED_VI,
--              INWARD_RESUBMITTED_V2, INWARD_REFERRED_CHEQUES,
--              INWARD_REPORT_SUMMARY
--   - All role_permission rows referencing removed keys updated,
--     to avoid FK violation (same class of bug as earlier
--     OUTWARD_CXBF_REPORT fix).
-- ================================================================

-- ================================================================
-- 1. DROP (clean rebuild — comment out if you want to keep data)
-- ================================================================
DROP TABLE IF EXISTS cts_audit_log;
DROP TABLE IF EXISTS cts_role_permissions;
DROP TABLE IF EXISTS cts_permissions;
DROP TABLE IF EXISTS cts_users;
DROP TABLE IF EXISTS cts_roles;

-- ================================================================
-- 2. TABLES
-- ================================================================

-- ── Roles ─────────────────────────────────────────────────────
CREATE TABLE cts_roles (
    id               BIGSERIAL PRIMARY KEY,
    role_name        VARCHAR(50)  NOT NULL UNIQUE,
    description       VARCHAR(200),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING','ACTIVE','REJECTED','INACTIVE')),
    created_by       VARCHAR(50),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    rejected_reason  TEXT
);

-- ── Users ─────────────────────────────────────────────────────
CREATE TABLE cts_users (
    id               BIGSERIAL PRIMARY KEY,
    username         VARCHAR(50)  NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    full_name        VARCHAR(100) NOT NULL,
    role_label       VARCHAR(50),
    email            VARCHAR(100),
    mobile           VARCHAR(15),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING','ACTIVE','REJECTED','INACTIVE','LOCKED','DISABLED','TERMINATED')),
    role_id          BIGINT REFERENCES cts_roles(id),
    rejected_reason  TEXT,
    failed_attempts  INT          NOT NULL DEFAULT 0,
    locked_until     TIMESTAMP,
    last_login       TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ── Permissions (master list) ────────────────────────────────
CREATE TABLE cts_permissions (
    id              BIGSERIAL PRIMARY KEY,
    permission_key  VARCHAR(100) NOT NULL UNIQUE,
    display_name    VARCHAR(150) NOT NULL,
    module          VARCHAR(50)  NOT NULL
);

-- ── Role ↔ Permission (live mapping) ──────────────────────────
CREATE TABLE cts_role_permissions (
    role_id         BIGINT       NOT NULL REFERENCES cts_roles(id) ON DELETE CASCADE,
    permission_key  VARCHAR(100) NOT NULL REFERENCES cts_permissions(permission_key),
    PRIMARY KEY (role_id, permission_key)
);

-- ── Audit log ──────────────────────────────────────────────────
CREATE TABLE cts_audit_log (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    event_type  VARCHAR(30)  NOT NULL,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(255),
    details     VARCHAR(500),
    event_time  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ================================================================
-- 3. INDEXES
-- ================================================================
CREATE INDEX idx_users_status   ON cts_users(status);
CREATE INDEX idx_users_username ON cts_users(username);
CREATE INDEX idx_roles_status   ON cts_roles(status);
CREATE INDEX idx_audit_username ON cts_audit_log(username);
CREATE INDEX idx_audit_time     ON cts_audit_log(event_time);

-- ================================================================
-- 4. SEED DATA — PERMISSIONS
-- These keys are the authoritative list — they must exactly match
-- com.cts.util.SecurityUtil#buildPagePermissions(), which maps
-- each .zul page to one permission key for access control.
-- ================================================================
INSERT INTO cts_permissions (permission_key, display_name, module) VALUES
-- Shell
('DASHBOARD_VIEW',           'View Dashboard',              'Dashboard'),
('OUTWARD_DASHBOARD_VIEW',   'View Outward Dashboard',      'Outward'),


-- Inward Dashboard accordion (role-specific dashboards)
('INWARD_MAKER_DASHBOARD_VIEW',     'Inward Maker Dashboard',       'Inward'),
('INWARD_VERIFIER1_DASHBOARD_VIEW', 'Inward Verifier-I Dashboard',  'Inward'),
('INWARD_VERIFIER2_DASHBOARD_VIEW', 'Inward Verifier-II Dashboard', 'Inward'),

-- Outward
('OUTWARD_BATCH_SCANNING',   'Batch Scanning',              'Outward'),
('OUTWARD_BATCH_MANAGEMENT', 'Batch Management',            'Outward'),
('OUTWARD_VERIFICATION_ONE', 'Verification I',              'Outward'),
('OUTWARD_VERIFICATION_TWO', 'Verification II',             'Outward'),
('OUTWARD_CBS_EXPORT',       'CXF / CXBF Export',           'Outward'),

-- Outward Reports
('OUTWARD_CXF_REPORT',       'CXF Report',                  'Outward'),
('OUTWARD_BATCH_SUMMARY',    'Batch Summary',                'Outward'),
('OUTWARD_CHEQUE_LEVEL',     'Cheque Level Report',          'Outward'),

-- Inward Clearing (new flow)
('INWARD_UPLOAD_CHEQUES',    'Upload Cheques',               'Inward'),
('INWARD_RETURNED_CHEQUES',  'Returned Cheques',             'Inward'),
('INWARD_RESUBMITTED_VI',    'Resubmitted Cheques by VI',    'Inward'),
('INWARD_RESUBMITTED_V2',    'Resubmitted Cheques by V2',    'Inward'),
('INWARD_REFERRED_CHEQUES',  'Referred Cheques',             'Inward'),

-- Inward Reports (consolidated)
('INWARD_REPORT_SUMMARY',    'Inward Report Summary',        'Inward'),

-- UAM
('UAM_ROLE_MGMT',            'Role Management',              'User Administration'),
('UAM_USER_MGMT',            'User Management',              'User Administration'),
('UAM_PENDING_APPROVAL',     'Pending User Approvals',       'User Administration'),
('UAM_PENDING_ROLE_APPROVAL','Pending Role Approvals',       'User Administration');

-- ================================================================
-- 5. SEED DATA — ROLES
-- ================================================================
INSERT INTO cts_roles (role_name, description, status, created_by, created_at) VALUES
('SUPER_ADMIN', 'Full system access across all modules',        'ACTIVE',  'system',  NOW()),
('UAM_ADMIN',   'Manages users and roles (maker-checker admin)', 'ACTIVE',  'system',  NOW()),
('INWARD_OPS',  'Handles inward cheque processing',              'ACTIVE',  'system',  NOW()),
('OUTWARD_OPS', 'Handles outward cheque processing',             'ACTIVE',  'system',  NOW()),
('VIEWER',      'Read-only access to dashboards and reports',    'ACTIVE',  'system',  NOW()),
('TEMP_AUDITOR','Temporary auditor role awaiting checker review','PENDING', 'rprasad', NOW());

-- Permissions for SUPER_ADMIN — everything
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'SUPER_ADMIN'), permission_key
FROM cts_permissions;

-- Permissions for UAM_ADMIN — full access to user/role admin screens
INSERT INTO cts_role_permissions (role_id, permission_key) VALUES
((SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), 'DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), 'UAM_USER_MGMT'),
((SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), 'UAM_ROLE_MGMT'),
((SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), 'UAM_PENDING_APPROVAL'),
((SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), 'UAM_PENDING_ROLE_APPROVAL');

-- Permissions for INWARD_OPS (realigned to sidebar v2)
INSERT INTO cts_role_permissions (role_id, permission_key) VALUES
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_MAKER_DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_VERIFIER1_DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_VERIFIER2_DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_UPLOAD_CHEQUES'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_RETURNED_CHEQUES'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_RESUBMITTED_VI'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_RESUBMITTED_V2'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_REFERRED_CHEQUES'),
((SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), 'INWARD_REPORT_SUMMARY');

-- Permissions for OUTWARD_OPS
INSERT INTO cts_role_permissions (role_id, permission_key) VALUES
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_BATCH_SCANNING'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_BATCH_MANAGEMENT'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_VERIFICATION_ONE'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_VERIFICATION_TWO'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_CBS_EXPORT'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_CXF_REPORT'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_BATCH_SUMMARY'),
((SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), 'OUTWARD_CHEQUE_LEVEL');

-- Permissions for VIEWER — dashboards + reports only (realigned)
INSERT INTO cts_role_permissions (role_id, permission_key) VALUES
((SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), 'DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), 'OUTWARD_DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), 'INWARD_REPORT_SUMMARY'),
((SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), 'OUTWARD_CXF_REPORT'),
((SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), 'OUTWARD_BATCH_SUMMARY'),
((SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), 'OUTWARD_CHEQUE_LEVEL');

-- Permissions submitted with the PENDING role (visible to checker on approval) — realigned
INSERT INTO cts_role_permissions (role_id, permission_key) VALUES
((SELECT id FROM cts_roles WHERE role_name = 'TEMP_AUDITOR'), 'DASHBOARD_VIEW'),
((SELECT id FROM cts_roles WHERE role_name = 'TEMP_AUDITOR'), 'INWARD_REPORT_SUMMARY'),
((SELECT id FROM cts_roles WHERE role_name = 'TEMP_AUDITOR'), 'OUTWARD_CXF_REPORT');

-- ================================================================
-- 6. SEED DATA — USERS
-- Password hash below = BCrypt hash of "Admin@123" for ALL seeded users.
-- Replace via change-password screen after first login.
-- ================================================================
INSERT INTO cts_users
    (username, password_hash, full_name, role_label, email, mobile, status, role_id, created_at, updated_at)
VALUES
('admin',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'System Administrator', 'SUPER_ADMIN', 'admin@navbharat.local', '9000000001',
 'ACTIVE', (SELECT id FROM cts_roles WHERE role_name = 'SUPER_ADMIN'), NOW(), NOW()),

('rprasad',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Ravi Prasad', 'UAM_ADMIN', 'rprasad@navbharat.local', '9000000002',
 'ACTIVE', (SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), NOW(), NOW()),

('skumar',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Suresh Kumar', 'INWARD_OPS', 'skumar@navbharat.local', '9000000003',
 'ACTIVE', (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), NOW(), NOW()),

('mjoseph',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Maria Joseph', 'OUTWARD_OPS', 'mjoseph@navbharat.local', '9000000004',
 'ACTIVE', (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), NOW(), NOW()),

('vrao',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Venkat Rao', 'VIEWER', 'vrao@navbharat.local', '9000000005',
 'ACTIVE', (SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), NOW(), NOW()),

-- A PENDING user — awaiting checker approval (Pending User Approvals screen)
('anair',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Anjali Nair', 'INWARD_OPS', 'anair@navbharat.local', '9000000006',
 'PENDING', (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), NOW(), NOW()),

-- A REJECTED user — kept in DB, hidden from main list, reason stored
('dverma',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Deepak Verma', 'OUTWARD_OPS', 'dverma@navbharat.local', '9000000007',
 'REJECTED', (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), NOW(), NOW()),

-- A LOCKED user — direct status action example
('plal',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Priya Lal', 'INWARD_OPS', 'plal@navbharat.local', '9000000008',
 'LOCKED', (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), NOW(), NOW()),

-- A DISABLED user
('ksingh',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Karan Singh', 'OUTWARD_OPS', 'ksingh@navbharat.local', '9000000009',
 'DISABLED', (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), NOW(), NOW()),

-- A TERMINATED user
('bshah',
 '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
 'Bhavna Shah', 'VIEWER', 'bshah@navbharat.local', '9000000010',
 'TERMINATED', (SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), NOW(), NOW());

-- Set rejected_reason for the rejected user
UPDATE cts_users
SET rejected_reason = 'Duplicate request — user already exists under a different ID.'
WHERE username = 'dverma';

-- Lock the LOCKED user for 30 minutes from now (example direct lock action)
UPDATE cts_users
SET locked_until = NOW() + INTERVAL '30 minutes'
WHERE username = 'plal';

-- Set rejected_reason for the rejected role
UPDATE cts_roles
SET status = 'PENDING'
WHERE role_name = 'TEMP_AUDITOR';
ALTER TABLE cts_users
ADD COLUMN requested_by VARCHAR(100);
-- ================================================================
-- 7. SEED DATA — AUDIT LOG (sample entries)
-- ================================================================
INSERT INTO cts_audit_log (username, event_type, ip_address, user_agent, details, event_time) VALUES
('admin',   'LOGIN_SUCCESS', '127.0.0.1', 'Mozilla/5.0', 'Initial admin login',                 NOW() - INTERVAL '2 days'),
('rprasad', 'LOGIN_SUCCESS', '127.0.0.1', 'Mozilla/5.0', 'UAM admin login',                      NOW() - INTERVAL '1 day'),
('dverma',  'LOGIN_FAILURE', '127.0.0.1', 'Mozilla/5.0', 'Login attempt blocked — status REJECTED', NOW() - INTERVAL '5 hours'),
('plal',    'ACCOUNT_LOCKED','127.0.0.1', 'Mozilla/5.0', 'Locked by admin via User Management',  NOW() - INTERVAL '1 hour');