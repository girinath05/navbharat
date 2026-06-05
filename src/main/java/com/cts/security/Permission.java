/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : Permission.java
 *  Package     : com.cts.security
 *  Author      : Girinath M.
 *  Created     : June 2026
 *  Description : Enum of fine-grained permission constants used
 *                by SecurityUtil. Each constant maps to a single
 *                UI action (e.g. DASHBOARD_VIEW, BATCH_SUBMIT,
 *                HV_VERIFY). Role-to-permission mapping is
 *                resolved at login and stored in the ZK session.
 * ============================================================
 */

package com.cts.security;

public enum Permission {

	DASHBOARD_VIEW,

	OUTWARD_ENTRY, OUTWARD_VERIFY,

	INWARD_ENTRY, INWARD_VERIFY,

	USER_CREATE, USER_VIEW, USER_DELETE,

	REPORT_VIEW
}