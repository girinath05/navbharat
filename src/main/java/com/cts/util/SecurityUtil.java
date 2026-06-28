package com.cts.util;

import com.cts.uam.model.User;
import com.cts.uam.service.RoleService;
import com.cts.uam.service.RoleServiceImpl;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Session;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class is a helper that answers three questions at runtime:
 * 1. Who is logged in right now?
 * 2. What actions is that person allowed to do?
 * 3. Is that person allowed to open a particular page?
 *
 * Every page in the sidebar (sidebar.zul) has a permission key attached to it.
 * This class keeps a list of those page → permission pairs so that when a user
 * tries to open a page, we can quickly check whether they have the right
 * permission.
 *
 * NOTE: Some permission keys (like BATCH_DETAIL, CHEQUE_EDITOR) are used inside
 * page composers to show or hide buttons — they do NOT have a page entry here
 * because they are not full pages, just small UI controls inside a page.
 */
public final class SecurityUtil {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityUtil.class);

    // This talks to the database to fetch what permissions a role has.
    private static final RoleService ROLE_SERVICE = new RoleServiceImpl();

    // The key we use to store and read the logged-in user object from the session.
    public static final String SESSION_USER_KEY = "CURRENT_USER";

    // The key we use to store the permission list in the session so we don't
    // keep hitting the database on every click.
    private static final String PERMISSION_CACHE_KEY = "_PERM_CACHE";

    // The main app shell page. Any logged-in user can open this.
    private static final String HOME_PAGE = "/zul/app.zul";

    // These pages are open to everyone — no login needed.
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/", "/zul/index.zul", "/zul/login.zul");

    // The full list of pages and which permission each one needs.
    // Built once when the class loads and never changes.
    private static final Map<String, String> PAGE_PERMISSIONS = buildPagePermissions();

    // Prevent anyone from creating an instance — all methods here are static.
    private SecurityUtil() {
    }

    // ── Who is logged in? ────────────────────────────────────────────────────

    /**
     * Gets the current ZK session.
     * Returns null if there is no active request right now.
     */
    public static Session getCurrentSession() {
        return Executions.getCurrent() != null
                ? Executions.getCurrent().getSession()
                : null;
    }

    /**
     * Finds the User object for whoever is logged in right now.
     *
     * We check the ZK session first. If the user is not there, we fall back
     * to the regular HTTP session. This makes sure filters and page composers
     * always agree on who is logged in.
     *
     * Returns null if nobody is logged in.
     */
    public static User getCurrentUser() {
        // Try ZK session first
        Session zkSession = getCurrentSession();
        Object sessionUser = zkSession != null ? zkSession.getAttribute(SESSION_USER_KEY) : null;
        if (sessionUser instanceof User user) {
            return user;
        }

        // No active ZK request means we cannot check the HTTP session either
        if (Executions.getCurrent() == null) {
            return null;
        }

        // Fall back to the plain HTTP session (used by servlet filters)
        Object requestObject = Executions.getCurrent().getNativeRequest();
        if (!(requestObject instanceof HttpServletRequest request)) {
            return null;
        }

        HttpSession httpSession = request.getSession(false);
        Object httpUser = httpSession != null ? httpSession.getAttribute(SESSION_USER_KEY) : null;
        return httpUser instanceof User u ? u : null;
    }

    /**
     * Returns the username of the person who is logged in.
     * Returns "unknown" if nobody is logged in, so log lines always have a value.
     */
    public static String getCurrentUserId() {
        User user = getCurrentUser();
        return user != null ? user.getUsername() : "unknown";
    }

    /**
     * Returns true if someone is currently logged in, false otherwise.
     */
    public static boolean isLoggedIn() {
        return getCurrentUser() != null;
    }

    // ── What is this person allowed to do? ──────────────────────────────────

    /**
     * Checks whether the currently logged-in user has a specific permission.
     *
     * This is used inside page composers to decide whether to show or hide
     * a button, tab, or action — not to guard whole pages (that is done by
     * canAccessPage below).
     *
     * Returns false if the permission name is empty, or if nobody is logged in.
     */
    public static boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            LOG.warn("hasPermission() was called with an empty permission name — returning false");
            return false;
        }

        User user = getCurrentUser();
        if (user == null || user.getRoleId() == null) {
            return false;
        }

        return resolvePermissions(user).contains(permission.trim().toUpperCase());
    }

    /**
     * Returns the full set of permission keys that belong to this user's role.
     *
     * How it works:
     * - On the first call after login, we ask the database for the list and
     * save it in the session.
     * - On every call after that, we read it straight from the session so
     * we never hit the database again during the same login session.
     * - When the user logs out, the session is cleared, which also removes
     * this cached list.
     *
     * Returns an empty set if the user has no role or if the database call fails.
     */
    public static Set<String> resolvePermissions(User user) {
        if (user == null || user.getRoleId() == null) {
            if (user != null) {
                LOG.warn("User '{}' has no role assigned — denying all permissions",
                        user.getUsername());
            }
            return Set.of();
        }

        Session zkSession = getCurrentSession();
        Map<Long, Set<String>> cache = getPermissionCache(zkSession);

        // If we already loaded permissions for this role during this session, use them
        if (cache.containsKey(user.getRoleId())) {
            return cache.get(user.getRoleId());
        }

        // First time — go to the database, then save the result in the session
        try {
            Set<String> permissions = ROLE_SERVICE.getAssignedPermissionKeys(user.getRoleId());

            if (permissions.isEmpty()) {
                LOG.warn("Role {} assigned to user '{}' has no permissions in the database",
                        user.getRoleId(), user.getUsername());
            }

            cache.put(user.getRoleId(), permissions);
            storePermissionCache(zkSession, cache);
            return permissions;

        } catch (Exception ex) {
            LOG.error("Could not load permissions for user '{}' with roleId={} — reason: {}",
                    user.getUsername(), user.getRoleId(), ex.getMessage());
            return Set.of();
        }
    }

    /**
     * Removes the saved permission list from the session.
     * Call this when the user logs out so stale data does not carry over.
     */
    public static void invalidatePermissionCache() {
        Session zkSession = getCurrentSession();
        if (zkSession != null) {
            zkSession.removeAttribute(PERMISSION_CACHE_KEY);
        }
    }

    // ── Can this person open a page? ────────────────────────────────────────

    /**
     * Returns the path of the main app shell page.
     * Used by the login flow to redirect the user after a successful login.
     */
    public static String getHomePage() {
        return HOME_PAGE;
    }

    /**
     * Checks whether the currently logged-in user is allowed to open the given
     * page.
     * Shortcut that reads the current user automatically.
     */
    public static boolean canAccessPage(String page) {
        return canAccessPage(page, getCurrentUser());
    }

    /**
     * Checks whether a specific user is allowed to open the given page.
     *
     * The rules in order:
     * 1. If the path is empty, deny.
     * 2. If the page is in the public list (login page etc.), allow anyone.
     * 3. If the page is the main app shell, allow any logged-in user.
     * 4. If nobody is logged in, deny.
     * 5. Look up which permission the page needs in our list.
     * - If no entry exists for this page path, deny it (unknown pages are
     * blocked by default to be safe).
     * - If an entry exists, check whether the user has that permission.
     */
    public static boolean canAccessPage(String page, User user) {
        String normalizedPage = normalizePath(page);

        if (normalizedPage.isEmpty()) {
            return false;
        }

        // Rule 2 — public pages need no login
        if (PUBLIC_PATHS.contains(normalizedPage)) {
            return true;
        }

        // Rule 3 — the app shell is fine for any logged-in user
        if (HOME_PAGE.equals(normalizedPage)) {
            return user != null;
        }

        // Rule 4 — everything else requires a logged-in user with a role
        if (user == null || user.getRoleId() == null) {
            return false;
        }

        // Rule 5 — look up the required permission and check it
        String requiredPermission = PAGE_PERMISSIONS.get(normalizedPage);
        if (requiredPermission == null) {
            // Page is not in our list — block it by default
            return !(normalizedPage.startsWith("/zul/") && normalizedPage.endsWith(".zul"));
        }

        return resolvePermissions(user).contains(requiredPermission.toUpperCase());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Makes sure the page path always starts with a slash.
     * For example "zul/dashboard.zul" becomes "/zul/dashboard.zul".
     * Returns an empty string if the input is null or blank.
     */
    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty())
            return "";
        return value.startsWith("/") ? value : "/" + value;
    }

    /**
     * Reads the permission cache from the session.
     *
     * The session can store any object, so Java cannot know the type at compile
     * time — that is why the @SuppressWarnings annotation is here. We check the
     * type ourselves with "instanceof Map" before casting, so it is safe.
     *
     * Returns an empty map if the session is null or the cache does not exist yet.
     */
    @SuppressWarnings("unchecked")
    private static Map<Long, Set<String>> getPermissionCache(Session zkSession) {
        if (zkSession == null)
            return new HashMap<>();
        Object cache = zkSession.getAttribute(PERMISSION_CACHE_KEY);
        return cache instanceof Map ? (Map<Long, Set<String>>) cache : new HashMap<>();
    }

    /**
     * Saves the permission cache back into the session.
     * Does nothing if the session is null.
     */
    private static void storePermissionCache(Session zkSession,
            Map<Long, Set<String>> cache) {
        if (zkSession != null) {
            zkSession.setAttribute(PERMISSION_CACHE_KEY, cache);
        }
    }

    /**
     * Builds the list that maps every sidebar page to the permission it needs.
     *
     * This list must always match sidebar.zul.
     * Every perm="..." / page="..." pair in the sidebar has exactly one line here.
     *
     * If you add a new page to the sidebar, add a line here too.
     * If you remove a page from the sidebar, remove the line here too.
     *
     * The six workspace keys (BATCH_DETAIL, CHEQUE_EDITOR, REPAIRE_WORKSPACE,
     * TV1_REVIEW_WORKSPACE, TV2_REVIEW_WORKSPACE, CHECKER_CHEQUE_DETAIL) are
     * NOT in this list. They are checked with hasPermission() inside composers
     * to show or hide buttons — they are not full pages, so they do not belong
     * here.
     */
    private static Map<String, String> buildPagePermissions() {
        Map<String, String> p = new LinkedHashMap<>();

        // ── MAIN MENU ────────────────────────────────────────────────────────
        p.put("/zul/dashboard.zul", "GENERAL_DASHBOARD");
        p.put("/zul/outward/outwardDashboard.zul", "OUTWARD_DASHBOARD");

        // The "Inward Dashboard" accordion header in the sidebar has no permission —
        // it is just a visual toggle. The three items inside it each have their own
        // permission, so we list those sub-pages here instead.
        p.put("/zul/inward/dashboard/makerDashboard.zul", "INWARD_MAKER_DASHBOARD_VIEW");
        p.put("/zul/inward/dashboard/verifierIDashboard.zul", "INWARD_VERIFIER1_DASHBOARD_VIEW");
        p.put("/zul/inward/dashboard/verifierIIDashboard.zul", "INWARD_VERIFIER2_DASHBOARD_VIEW");

        // ── CLEARING — Outward ───────────────────────────────────────────────
        p.put("/zul/outward/draft-batches.zul", "OUTWARD_DRAFT_BATCHES");
        p.put("/zul/outward/pending-batches.zul", "OUTWARD_PENDING_BATCHES");
        p.put("/zul/outward/submitted-batches.zul", "OUTWARD_SUBMITTED_BATCHES");
        p.put("/zul/outward/verification-I.zul", "OUTWARD_VERIFICATION_ONE");
        p.put("/zul/outward/verification-II.zul", "OUTWARD_VERIFICATION_TWO");
        p.put("/zul/outward/cxf-cxbf.zul", "OUTWARD_CXF_CIBF_GENERATION");

        // ── CLEARING — Inward ────────────────────────────────────────────────
        p.put("/zul/inward/clearing/uploadCheques.zul", "INWARD_UPLOAD_CHEQUES");
        p.put("/zul/inward/clearing/returnedCheques.zul", "INWARD_RETURNED_CHEQUES");
        p.put("/zul/inward/clearing/resubmittedByVI.zul", "INWARD_RESUBMITTED_VI");
        p.put("/zul/inward/clearing/resubmittedByV2.zul", "INWARD_RESUBMITTED_V2");
        p.put("/zul/inward/clearing/referredCheques.zul", "INWARD_REFERRED_CHEQUES");

        // ── REPORTS — Outward ────────────────────────────────────────────────
        p.put("/zul/outward/reports/cxfReport.zul", "OUTWARD_CXF_REPORT");
        p.put("/zul/outward/reports/batchSummaryReport.zul", "OUTWARD_BATCH_SUMMARY");
        p.put("/zul/outward/reports/chequeLevelReport.zul", "OUTWARD_CHEQUE_LEVEL");

        // ── REPORTS — Inward ─────────────────────────────────────────────────
        p.put("/zul/inward/reports/reportSummary.zul", "INWARD_REPORT_SUMMARY");

        // ── SETTINGS — UAM ───────────────────────────────────────────────────
        p.put("/zul/uam/role-mgmt.zul", "UAM_ROLE_MGMT");
        p.put("/zul/uam/user-mgmt.zul", "UAM_USER_MGMT");
        p.put("/zul/uam/pending-approval.zul", "UAM_PENDING_APPROVAL");
        p.put("/zul/uam/pending-role-approval.zul", "UAM_PENDING_ROLE_APPROVAL");

        return Map.copyOf(p);
    }
}