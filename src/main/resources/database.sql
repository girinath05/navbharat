-- ================================================================
-- NavbharatCTS — Full Database Schema + Seed Data
-- Target : PostgreSQL
--
-- Permission keys are the single source of truth for access control.
-- They divide into two distinct groups:
--
--   PAGE keys (24) — used by SecurityUtil.canAccessPage() / sidebar.zul
--                    Every sidebar perm="..." attribute has exactly one
--                    matching row here, and vice versa.
--
--   WORKSPACE keys (6) — used by composer hasPermission() calls for
--                    UI-element visibility (buttons, action menus).
--                    These have no ZUL page mapping in SecurityUtil.
--
-- Removed vs previous version:
--   - INWARD_DASHBOARD_VIEW    (sidebar accordion header carries no perm;
--                               sub-items carry their own perms directly)
--   - OUTWARD_BATCH_SCANNING   (orphan — never referenced in sidebar or
--                               SecurityUtil)
-- ================================================================


-- ================================================================
-- 1. CLEAN SLATE
-- ================================================================
DROP TABLE IF EXISTS cts_audit_log         CASCADE;
DROP TABLE IF EXISTS cts_role_permissions  CASCADE;
DROP TABLE IF EXISTS cts_permissions       CASCADE;
DROP TABLE IF EXISTS cts_users             CASCADE;
DROP TABLE IF EXISTS cts_roles             CASCADE;


-- ================================================================
-- 2. TABLES
-- ================================================================

CREATE TABLE cts_roles (
    id              BIGSERIAL    PRIMARY KEY,
    role_name       VARCHAR(50)  NOT NULL UNIQUE,
    description     VARCHAR(200),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','ACTIVE','REJECTED','INACTIVE')),
    created_by      VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    rejected_reason TEXT
);

CREATE TABLE cts_users (
    id              BIGSERIAL    PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(100) NOT NULL,
    role_label      VARCHAR(50),
    email           VARCHAR(100),
    mobile          VARCHAR(15),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','ACTIVE','REJECTED','INACTIVE',
                                          'LOCKED','DISABLED','TERMINATED')),
    role_id         BIGINT       REFERENCES cts_roles(id),
    rejected_reason TEXT,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP,
    last_login      TIMESTAMP,
    requested_by    VARCHAR(100),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE cts_permissions (
    id              BIGSERIAL    PRIMARY KEY,
    permission_key  VARCHAR(100) NOT NULL UNIQUE,
    display_name    VARCHAR(150) NOT NULL,
    module          VARCHAR(50)  NOT NULL
);

CREATE TABLE cts_role_permissions (
    role_id         BIGINT       NOT NULL REFERENCES cts_roles(id)                   ON DELETE CASCADE,
    permission_key  VARCHAR(100) NOT NULL REFERENCES cts_permissions(permission_key) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_key)
);

CREATE TABLE cts_audit_log (
    id          BIGSERIAL    PRIMARY KEY,
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
-- 4. PERMISSIONS
--
-- Section order mirrors sidebar.zul exactly.
-- PAGE keys map 1-to-1 with SecurityUtil.buildPagePermissions().
-- WORKSPACE keys are checked via SecurityUtil.hasPermission() only.
-- ================================================================
INSERT INTO cts_permissions (permission_key, display_name, module) VALUES

    -- ── MAIN MENU ────────────────────────────────────────────────
    ('GENERAL_DASHBOARD',                  'General Dashboard',                   'Dashboard'),
    ('OUTWARD_DASHBOARD',          'Outward Dashboard',           'Outward'),

    -- Inward Dashboard (sub-items; accordion header has no perm)
    ('INWARD_MAKER_DASHBOARD_VIEW',     'Inward Maker Dashboard',           'Inward'),
    ('INWARD_VERIFIER1_DASHBOARD_VIEW', 'Inward Verifier-I Dashboard',      'Inward'),
    ('INWARD_VERIFIER2_DASHBOARD_VIEW', 'Inward Verifier-II Dashboard',     'Inward'),

    -- ── CLEARING — Outward ───────────────────────────────────────
    ('OUTWARD_DRAFT_BATCHES',           'Draft Batches',                    'Outward'),
    ('OUTWARD_BATCH_DETAIL', 'Batch details', 'Outward'),
    ('OUTWARD_PENDING_BATCHES',        'Pending Batches',        'Outward'),
    ('OUTWARD_SUBMITTED_BATCHES',       'Submitted Batches',                'Outward'),
    ('OUTWARD_VERIFICATION_ONE',        'Verification I',                   'Outward'),
    ('OUTWARD_VERIFICATION_TWO',        'Verification II',                  'Outward'),
    ('OUTWARD_CXF_CIBF_GENERATION',              'CXF-CIBF Generation',                'Outward'),

    -- ── CLEARING — Inward ────────────────────────────────────────
    ('INWARD_UPLOAD_CHEQUES',           'Upload Cheques',                   'Inward'),
    ('INWARD_RETURNED_CHEQUES',         'Returned Cheques',                 'Inward'),
    ('INWARD_RESUBMITTED_VI',           'Resubmitted Cheques at V-I',        'Inward'),
    ('INWARD_RESUBMITTED_V2',           'Resubmitted Cheques at V-II',        'Inward'),
    ('INWARD_REFERRED_CHEQUES',         'Referred Cheques',                 'Inward'),

    -- ── REPORTS — Outward ────────────────────────────────────────
    ('OUTWARD_CXF_REPORT',              'CXF-CIBF Report',                       'Outward'),
    ('OUTWARD_BATCH_SUMMARY',           'Batch Summary',             'Outward'),
    ('OUTWARD_CHEQUE_LEVEL',            'Cheque Level Report',              'Outward'),

    -- ── REPORTS — Inward ─────────────────────────────────────────
    ('INWARD_REPORT_SUMMARY',           'Report Summary',            'Inward'),

    -- ── SETTINGS — UAM ───────────────────────────────────────────
    ('UAM_ROLE_MGMT',                   'Role Management',                  'User Administration'),
    ('UAM_USER_MGMT',                   'User Management',                  'User Administration'),
    ('UAM_PENDING_APPROVAL',            'Pending User Approvals',           'User Administration'),
    ('UAM_PENDING_ROLE_APPROVAL',       'Pending Role Approvals',           'User Administration');



-- ================================================================
-- 5. ROLES
-- ================================================================
INSERT INTO cts_roles (role_name, description, status, created_by, created_at) VALUES
    ('SUPER_ADMIN',  'Full system access across all modules',              'ACTIVE',  'system',  NOW()),
    ('UAM_ADMIN',    'Manages users and roles (maker-checker admin)',       'ACTIVE',  'system',  NOW()),
    ('INWARD_OPS',   'Handles inward cheque processing',                   'ACTIVE',  'system',  NOW()),
    ('OUTWARD_OPS',  'Handles outward cheque processing',                  'ACTIVE',  'system',  NOW()),
    ('VIEWER',       'Read-only access to dashboards and reports',         'ACTIVE',  'system',  NOW()),
    ('TEMP_AUDITOR', 'Temporary auditor role awaiting checker review',     'PENDING', 'rprasad', NOW());


-- ================================================================
-- 6. ROLE → PERMISSION GRANTS
-- ================================================================

-- ── SUPER_ADMIN : every permission ──────────────────────────────
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'SUPER_ADMIN'), permission_key
FROM   cts_permissions;

-- ── UAM_ADMIN ────────────────────────────────────────────────────
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'), unnest(ARRAY[
    'GENERAL_DASHBOARD',
    'UAM_ROLE_MGMT',
    'UAM_USER_MGMT',
    'UAM_PENDING_APPROVAL',
    'UAM_PENDING_ROLE_APPROVAL'
]);

-- ── INWARD_OPS ───────────────────────────────────────────────────
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'), unnest(ARRAY[
    'GENERAL_DASHBOARD',
    'INWARD_MAKER_DASHBOARD_VIEW',
    'INWARD_VERIFIER1_DASHBOARD_VIEW',
    'INWARD_VERIFIER2_DASHBOARD_VIEW',
    'INWARD_UPLOAD_CHEQUES',
    'INWARD_RETURNED_CHEQUES',
    'INWARD_RESUBMITTED_VI',
    'INWARD_RESUBMITTED_V2',
    'INWARD_REFERRED_CHEQUES',
    'INWARD_REPORT_SUMMARY'
]);

-- ── OUTWARD_OPS ──────────────────────────────────────────────────
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), unnest(ARRAY[
    'GENERAL_DASHBOARD',
    'OUTWARD_DASHBOARD',
    'OUTWARD_DRAFT_BATCHES',
    'OUTWARD_BATCH_DETAIL',
    'OUTWARD_PENDING_BATCHES',
    'OUTWARD_SUBMITTED_BATCHES',
    'OUTWARD_VERIFICATION_ONE',
    'OUTWARD_VERIFICATION_TWO',
    'OUTWARD_CXF_CIBF_GENERATION',
    'OUTWARD_CXF_REPORT',
    'OUTWARD_BATCH_SUMMARY',
    'OUTWARD_CHEQUE_LEVEL'
]);

-- ── VIEWER : dashboards + reports only ───────────────────────────
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'VIEWER'), unnest(ARRAY[
    'GENERAL_DASHBOARD',
    'INWARD_MAKER_DASHBOARD_VIEW',
    'INWARD_VERIFIER1_DASHBOARD_VIEW',
    'INWARD_VERIFIER2_DASHBOARD_VIEW',
    'INWARD_REPORT_SUMMARY',
    'OUTWARD_CXF_REPORT',
    'OUTWARD_BATCH_SUMMARY',
    'OUTWARD_CHEQUE_LEVEL'
]);

-- ── TEMP_AUDITOR (PENDING — visible to checker on approval) ──────
INSERT INTO cts_role_permissions (role_id, permission_key)
SELECT (SELECT id FROM cts_roles WHERE role_name = 'TEMP_AUDITOR'), unnest(ARRAY[
    'GENERAL_DASHBOARD',
    'INWARD_REPORT_SUMMARY',
    'OUTWARD_CXF_REPORT'
]);


-- ================================================================
-- 7. USERS
-- All passwords = BCrypt hash of "Admin@123". Change after first login.
-- ================================================================
INSERT INTO cts_users
    (username, password_hash, full_name, role_label, email, mobile,
     status, role_id, created_at, updated_at)
VALUES
    ('admin',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'System Administrator', 'SUPER_ADMIN',
     'admin@navbharat.local',   '9000000001', 'ACTIVE',
     (SELECT id FROM cts_roles WHERE role_name = 'SUPER_ADMIN'), NOW(), NOW()),

    ('rprasad',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Ravi Prasad',             'UAM_ADMIN',
     'rprasad@navbharat.local', '9000000002', 'ACTIVE',
     (SELECT id FROM cts_roles WHERE role_name = 'UAM_ADMIN'),   NOW(), NOW()),

    ('skumar',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Suresh Kumar',            'INWARD_OPS',
     'skumar@navbharat.local',  '9000000003', 'ACTIVE',
     (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'),  NOW(), NOW()),

    ('mjoseph',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Maria Joseph',            'OUTWARD_OPS',
     'mjoseph@navbharat.local', '9000000004', 'ACTIVE',
     (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), NOW(), NOW()),

    ('vrao',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Venkat Rao',              'VIEWER',
     'vrao@navbharat.local',    '9000000005', 'ACTIVE',
     (SELECT id FROM cts_roles WHERE role_name = 'VIEWER'),      NOW(), NOW()),

    -- Pending — awaiting checker approval
    ('anair',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Anjali Nair',             'INWARD_OPS',
     'anair@navbharat.local',   '9000000006', 'PENDING',
     (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'),  NOW(), NOW()),

    -- Rejected — reason stored below
    ('dverma',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Deepak Verma',            'OUTWARD_OPS',
     'dverma@navbharat.local',  '9000000007', 'REJECTED',
     (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), NOW(), NOW()),

    -- Locked — locked_until set below
    ('plal',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Priya Lal',               'INWARD_OPS',
     'plal@navbharat.local',    '9000000008', 'LOCKED',
     (SELECT id FROM cts_roles WHERE role_name = 'INWARD_OPS'),  NOW(), NOW()),

    -- Disabled
    ('ksingh',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Karan Singh',             'OUTWARD_OPS',
     'ksingh@navbharat.local',  '9000000009', 'DISABLED',
     (SELECT id FROM cts_roles WHERE role_name = 'OUTWARD_OPS'), NOW(), NOW()),

    -- Terminated
    ('bshah',
     '$2a$12$fFVlzaZlTHYadjqwBHVu6OdkAKOTEu38R.41iAwtfLAG6KEWS7BOC',
     'Bhavna Shah',             'VIEWER',
     'bshah@navbharat.local',   '9000000010', 'TERMINATED',
     (SELECT id FROM cts_roles WHERE role_name = 'VIEWER'),      NOW(), NOW());

-- Post-insert fixups
UPDATE cts_users
SET    rejected_reason = 'Duplicate request — user already exists under a different ID.'
WHERE  username = 'dverma';

UPDATE cts_users
SET    locked_until = NOW() + INTERVAL '30 minutes'
WHERE  username = 'plal';


-- ================================================================
-- 8. AUDIT LOG — sample entries
-- ================================================================
INSERT INTO cts_audit_log (username, event_type, ip_address, user_agent, details, event_time) VALUES
    ('admin',   'LOGIN_SUCCESS',  '127.0.0.1', 'Mozilla/5.0', 'Initial admin login',                      NOW() - INTERVAL '2 days'),
    ('rprasad', 'LOGIN_SUCCESS',  '127.0.0.1', 'Mozilla/5.0', 'UAM admin login',                          NOW() - INTERVAL '1 day'),
    ('dverma',  'LOGIN_FAILURE',  '127.0.0.1', 'Mozilla/5.0', 'Login attempt blocked — status REJECTED',  NOW() - INTERVAL '5 hours'),
    ('plal',    'ACCOUNT_LOCKED', '127.0.0.1', 'Mozilla/5.0', 'Locked by admin via User Management',      NOW() - INTERVAL '1 hour');