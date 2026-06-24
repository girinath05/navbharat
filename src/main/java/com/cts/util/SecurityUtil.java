package com.cts.util;

import com.cts.uam.model.User;
import com.cts.uam.service.RoleService;
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
 * Central helper for session, role, and page access checks.
 * Permissions are loaded through RoleService and cached per ZK session.
 */
public final class SecurityUtil {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityUtil.class);
    private static final RoleService ROLE_SERVICE = new RoleService();

    public static final String SESSION_USER_KEY = "CURRENT_USER";

    private static final String PERMISSION_CACHE_KEY = "_PERM_CACHE";
    private static final String HOME_PAGE = "/zul/app.zul";

    // Public pages that do not require login
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/", "/zul/index.zul", "/zul/login.zul");

    // Maps each page to the permission required to open it
    private static final Map<String, String> PAGE_PERMISSIONS = buildPagePermissions();

    private SecurityUtil() {
    }

    //this method is used to take out current session from zk session
    public static Session getCurrentSession() {
        return Executions.getCurrent() != null ? Executions.getCurrent().getSession() : null;
    }

    public static User getCurrentUser() {
        Session zkSession = getCurrentSession();
        Object sessionUser = zkSession != null ? zkSession.getAttribute(SESSION_USER_KEY) : null;
        if (sessionUser instanceof User user) {
            return user;
        }

        // Fallback to HTTP session so filters and composers stay aligned
        if (Executions.getCurrent() == null) {
            return null;
        }

        Object requestObject = Executions.getCurrent().getNativeRequest();
        if (!(requestObject instanceof HttpServletRequest request)) {
            return null;
        }

        HttpSession httpSession = request.getSession(false);
        Object httpUser = httpSession != null ? httpSession.getAttribute(SESSION_USER_KEY) : null;
        return httpUser instanceof User user ? user : null;
    }

    public static String getCurrentUserId() {
        User user = getCurrentUser();
        return user != null ? user.getUsername() : "unknown";
    }

    public static boolean isLoggedIn() {
        return getCurrentUser() != null;
    }
    
    
    public static boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            LOG.warn("Permission check called with blank value");
            return false;
        }

        User user = getCurrentUser();
        if (user == null || user.getRoleId() == null) {
            return false;
        }

        return resolvePermissions(user).contains(permission.trim().toUpperCase());
    }

    //this method is used to find out all permission keys assign to user and store that into session 
    // at starting and not need to db call again and again
    public static Set<String> resolvePermissions(User user) {
        if (user == null || user.getRoleId() == null) {
            if (user != null) {
                LOG.warn("User '{}' has no roleId; denying permissions", user.getUsername());
            }
            return Set.of();
        }

        Session zkSession = getCurrentSession();
        Map<Long, Set<String>> permissionCache = getPermissionCache(zkSession);
        if (permissionCache.containsKey(user.getRoleId())) {
            return permissionCache.get(user.getRoleId());
        }

        try {
            Set<String> permissions = ROLE_SERVICE.getAssignedPermissionKeys(user.getRoleId());
            if (permissions.isEmpty()) {
                LOG.warn("Role {} for user '{}' has no permissions",
                        user.getRoleId(), user.getUsername());
            }

            permissionCache.put(user.getRoleId(), permissions);
            storePermissionCache(zkSession, permissionCache);

            return permissions;
        } catch (Exception ex) {
            LOG.error("Failed to load permissions for user '{}' roleId={}: {}",
                    user.getUsername(), user.getRoleId(), ex.getMessage());
            return Set.of();
        }
    }
    //this is used clean the session at the time when user is logging out
    public static void invalidatePermissionCache() {
        Session zkSession = getCurrentSession();
        if (zkSession != null) {
            zkSession.removeAttribute(PERMISSION_CACHE_KEY);
        }
    }

    public static String getHomePage() {
        return HOME_PAGE;
    }
    //this method is used to check is any particular zul page is assign to currently login user
    public static boolean canAccessPage(String page) {
        return canAccessPage(page, getCurrentUser());
    }

    public static boolean canAccessPage(String page, User user) {
        String normalizedPage = normalizePath(page);
        if (normalizedPage.isEmpty()) {
            return false;
        }

        // Allow public pages even without login
        if (PUBLIC_PATHS.contains(normalizedPage)) {
            return true;
        }

        // The app shell is allowed for any logged-in user
        if (HOME_PAGE.equals(normalizedPage)) {
            return user != null;
        }

        if (user == null || user.getRoleId() == null) {
            return false;
        }
        //it is used to find out the key for each zul which is assign
        String requiredPermission = PAGE_PERMISSIONS.get(normalizedPage);
        if (requiredPermission == null) {
            // Deny unknown protected pages by default
            return !(normalizedPage.startsWith("/zul/") && normalizedPage.endsWith(".zul"));
        }

        return resolvePermissions(user).contains(requiredPermission.toUpperCase());
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }
     
    //this is used because when we try to get object from session then there is no conformation what types
    //of object can get so that compiler showing warning to supress that warning we use this
    @SuppressWarnings("unchecked")
    private static Map<Long, Set<String>> getPermissionCache(Session zkSession) {
        if (zkSession == null) {
            return new HashMap<>();
        }

        Object cache = zkSession.getAttribute(PERMISSION_CACHE_KEY);
        return cache instanceof Map ? (Map<Long, Set<String>>) cache : new HashMap<>();
    }

    //this method is used to assign all permission assign to currently logined user
    private static void storePermissionCache(Session zkSession, Map<Long, Set<String>> permissionCache) {
        if (zkSession != null) {
            zkSession.setAttribute(PERMISSION_CACHE_KEY, permissionCache);
        }
    }
    
    //this is used to find out key of any zul page
    private static Map<String, String> buildPagePermissions() {
        Map<String, String> permissions = new LinkedHashMap<>();

        permissions.put("/zul/dashboard.zul", "DASHBOARD_VIEW");
        permissions.put("/zul/outward/outwardDashboard.zul", "OUTWARD_DASHBOARD_VIEW");
        permissions.put("/zul/inward/inwardDashboard.zul", "INWARD_DASHBOARD_VIEW");

        permissions.put("/zul/outward/cheque-scan.zul", "OUTWARD_BATCH_SCANNING");
        permissions.put("/zul/outward/my-batches.zul", "OUTWARD_BATCH_MANAGEMENT");
        permissions.put("/zul/outward/batch-detail.zul", "OUTWARD_BATCH_MANAGEMENT");
        permissions.put("/zul/outward/verification-I.zul", "OUTWARD_VERIFICATION_ONE");
        permissions.put("/zul/outward/verification-II.zul", "OUTWARD_VERIFICATION_TWO");
        permissions.put("/zul/outward/cxf-cxbf.zul", "OUTWARD_CBS_EXPORT");

        permissions.put("/zul/outward/reports/cxfReport.zul", "OUTWARD_CXF_REPORT");
        permissions.put("/zul/outward/reports/batchSummaryReport.zul", "OUTWARD_BATCH_SUMMARY");
        permissions.put("/zul/outward/reports/chequeLevelReport.zul", "OUTWARD_CHEQUE_LEVEL");

        permissions.put("/zul/inward/dashboard/makerDashboard.zul", "INWARD_MAKER_DASHBOARD_VIEW");
        permissions.put("/zul/inward/dashboard/verifierIDashboard.zul", "INWARD_VERIFIER1_DASHBOARD_VIEW");
        permissions.put("/zul/inward/dashboard/verifierIIDashboard.zul", "INWARD_VERIFIER2_DASHBOARD_VIEW");

        // Inward Clearing (new flow)
        permissions.put("/zul/inward/clearing/uploadCheques.zul", "INWARD_UPLOAD_CHEQUES");
        permissions.put("/zul/inward/clearing/returnedCheques.zul", "INWARD_RETURNED_CHEQUES");
        permissions.put("/zul/inward/clearing/resubmittedByVI.zul", "INWARD_RESUBMITTED_VI");
        permissions.put("/zul/inward/clearing/resubmittedByV2.zul", "INWARD_RESUBMITTED_V2");
        permissions.put("/zul/inward/clearing/referredCheques.zul", "INWARD_REFERRED_CHEQUES");

        // Inward Reports (consolidated)
        permissions.put("/zul/inward/reports/reportSummary.zul", "INWARD_REPORT_SUMMARY");

        permissions.put("/zul/uam/role-mgmt.zul", "UAM_ROLE_MGMT");
        permissions.put("/zul/uam/user-mgmt.zul", "UAM_USER_MGMT");
        permissions.put("/zul/uam/pending-approval.zul", "UAM_PENDING_APPROVAL");
        permissions.put("/zul/uam/pending-role-approval.zul", "UAM_PENDING_ROLE_APPROVAL");

        return Map.copyOf(permissions);
    }
}