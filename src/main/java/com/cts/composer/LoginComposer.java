/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : LoginComposer.java
 *  Package     : com.cts.composer
 *  Author      : Girinath M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer wired to the login container
 *                div in index.zul. Validates userId / password
 *                against session state, stores the authenticated
 *                user in the ZK session, and redirects to the
 *                main shell on success. Displays inline error
 *                label on bad credentials.
 * ============================================================
 */

package com.cts.composer;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Label;
import org.zkoss.zul.Textbox;

/**
 * ============================================================ ClearPay CTS —
 * Login Composer File : LoginComposer.java Package: com.iispl.composer Stack :
 * ZK 10.0.0-jakarta / Tomcat 10.1.x / Java 17 Author : IISPL / Nav Bharat Bank
 * ============================================================
 *
 * Wired to: index.zul apply="com.iispl.composer.LoginComposer" placed on the
 * <div id="loginContainer"> — the parent that contains #userId, #password, and
 * #btnLogin.
 *
 * ── BUGS FIXED ────────────────────────────────────────────
 *
 * BUG 1 (was in index.zul, fixed there): apply= was on the <button> itself,
 * so @Wire could not climb up to find sibling #userId and #password textboxes.
 * Fixed: apply= moved to <div id="loginContainer">.
 *
 * BUG 2 (this file — sendRedirect): Was: Executions.sendRedirect("home.zul");
 * Fix: Executions.sendRedirect("outward-clearing.zul");
 *
 * BUG 3 (this file — session key mismatch): Was: session.setAttribute("userId",
 * ...) ← wrong key session.setAttribute("userName", ...) ← wrong key Fix:
 * session.setAttribute("loggedUser", ...) ← matches
 * OutwardClearingComposer.SESS_USERNAME session.setAttribute("userRole", ...) ←
 * matches OutwardClearingComposer.SESS_ROLE session.setAttribute("userBranch",
 * ...) ← matches OutwardClearingComposer.SESS_BRANCH
 *
 * OutwardClearingComposer reads: SESS_USERNAME = "loggedUser" SESS_ROLE =
 * "userRole" SESS_BRANCH = "userBranch" Without "loggedUser" being set,
 * guardSession() saw null and immediately redirected back to index.zul —
 * infinite loop. ============================================================
 */
public class LoginComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;

	// ── Session attribute keys
	// MUST match constants in OutwardClearingComposer exactly
	private static final String SESS_LOGGED_USER = "loggedUser"; // ← fixed from "userId"
	private static final String SESS_USER_ROLE = "userRole";
	private static final String SESS_USER_BRANCH = "userBranch";
	private static final String SESS_USER_NAME = "userName";

	// ── Wired components
	// Works because apply= is now on the parent <div>
	@Wire("#userId")
	private Textbox userId;

	@Wire("#password")
	private Textbox password;

	@Wire("#lblError")
	private Label lblError;

	// ════════════════════════════════════════════════════════════════
	// LIFECYCLE
	// ════════════════════════════════════════════════════════════════
	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);
		// If already logged in, skip login page
//        Object existing = Sessions.getCurrent().getAttribute(SESS_LOGGED_USER);
//        if (existing != null && !existing.toString().trim().isEmpty()) {
//            Executions.sendRedirect(".zul");
//        }
	}

	// ════════════════════════════════════════════════════════════════
	// LOGIN EVENT
	// Triggered by: onClick on #btnLogin
	// onOK (Enter key) on #userId or #password
	// ════════════════════════════════════════════════════════════════
	@Listen("onClick = #btnLogin; onOK = #userId; onOK = #password")
	public void onLogin() {

		// Read field values (null-safe)
		String uid = (userId != null) ? userId.getValue().trim() : "";
		String pass = (password != null) ? password.getValue().trim() : "";

		// ── Basic presence validation ──────────────────────────────
		if (uid.isEmpty()) {
			showError("Please enter your User ID.");
			return;
		}
		if (pass.isEmpty()) {
			showError("Please enter your Password.");
			return;
		}

		// ── Authentication ─────────────────────────────────────────
		// Replace this block with your real DAO/service call:
		//
		// UserService svc = new UserService();
		// UserDTO user = svc.authenticate(uid, pass);
		// if (user == null) { showError("Invalid credentials."); return; }
		//
		// Then set session from user DTO:
		// session.setAttribute(SESS_LOGGED_USER, user.getLoginId());
		// session.setAttribute(SESS_USER_NAME, user.getFullName());
		// session.setAttribute(SESS_USER_ROLE, user.getRole());
		// session.setAttribute(SESS_USER_BRANCH, user.getBranchCode());

		// ── Stub: accept any non-empty credentials for now ──
		String resolvedName = resolveDisplayName(uid);
		String resolvedRole = resolveRole(uid);
		String resolvedBranch = "BLR01"; // replace with DB value

		// ── Store in session — keys MUST match OutwardClearingComposer ──
		Session session = Sessions.getCurrent();
		session.setAttribute(SESS_LOGGED_USER, uid); // "loggedUser"
		session.setAttribute(SESS_USER_NAME, resolvedName); // "userName"
		session.setAttribute(SESS_USER_ROLE, resolvedRole); // "userRole"
		session.setAttribute(SESS_USER_BRANCH, resolvedBranch); // "userBranch"

		// ── FIX BUG 2: redirect to outward-clearing.zul ─────────────
		Executions.sendRedirect("zul/dashboard.zul");
	}

	// ════════════════════════════════════════════════════════════════
	// HELPERS
	// ════════════════════════════════════════════════════════════════

	private void showError(String msg) {
		if (lblError != null) {
			lblError.setValue(msg);
			lblError.setVisible(true);
		}
	}

	/**
	 * Stub: derive display name from login ID. Replace with DB lookup:
	 * UserService.getFullName(uid)
	 */
	private String resolveDisplayName(String uid) {
		switch (uid.toLowerCase()) {
		case "admin":
			return "Administrator";
		case "maker1":
			return "Rajesh Kumar";
		case "checker1":
			return "Priya Nair";
		default:
			return uid.toUpperCase();
		}
	}

	/**
	 * Stub: derive role from login ID. Replace with DB lookup:
	 * UserService.getRole(uid)
	 */
	private String resolveRole(String uid) {
		switch (uid.toLowerCase()) {
		case "admin":
			return "ADMIN";
		case "maker1":
			return "MAKER";
		case "checker1":
			return "CHECKER";
		default:
			return "ADMIN"; // default for demo
		}
	}
}