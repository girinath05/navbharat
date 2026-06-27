package com.cts.filter;

import com.cts.uam.model.User;
import com.cts.uam.service.UserServiceImpl;
import com.cts.util.SecurityUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;

// First filter in the chain — blocks unauthenticated access to ZUL pages
// Also re-checks the user's status from DB every 30 seconds to catch locked/inactive accounts quickly
public class SecurityFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    // How often to re-check the user's status from DB (30 seconds)
    private static final long RECHECK_INTERVAL_MS = 30_000L;

    // Pages anyone can open without logging in
    private static final Set<String> PUBLIC_PAGES = Set.of(
            "/", "/zul/index.zul", "/zul/login.zul"
    );

    // Logged-in users should not stay on these pages
    private static final Set<String> LOGIN_PAGES = Set.of("/zul/login.zul");

    private final UserServiceImpl userService = new UserServiceImpl();

    @Override
    public void init(FilterConfig cfg) {
        LOG.info("SecurityFilter initialized");
    }

    // Runs on every request — checks login status and redirects if needed
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // contextPath = /Navbharat from http://localhost:8080/Navbharat/zul/login.zul
        String contextPath  = request.getContextPath();
        // requestPath = /zul/login.zul (context path removed)
        String requestPath  = stripContextPath(request.getRequestURI(), contextPath);

        // Skip static files and ZK internal requests — only check .zul pages
        if (isStaticOrInternalRequest(requestPath) || !requestPath.endsWith(".zul")) {
            chain.doFilter(req, res);
            return;
        }

        // Get current user from session — false means don't create a new session per request
        HttpSession session     = request.getSession(false);
        User        currentUser = session == null ? null
                : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);

        if (currentUser != null) {
            // Re-fetch user from DB every 30 seconds to catch status changes (lock/deactivate)
            currentUser = revalidateUserIfNeeded(session, currentUser, contextPath, response);
            if (currentUser == null) return;

            // Block locked or inactive users even if they have a valid session
            if (!currentUser.isActive() || currentUser.isLocked()) {
                session.invalidate();
                LOG.info("SecurityFilter: session invalidated for user '{}' — status={}",
                        currentUser.getUsername(), currentUser.getStatus());
                response.sendRedirect(contextPath + "/zul/login.zul");
                return;
            }
        }

        boolean isLoggedIn = currentUser != null;

        // Already logged in and trying to open login page → redirect to home
        if (isLoggedIn && LOGIN_PAGES.contains(requestPath)) {
            LOG.debug("Logged-in user hit login page, redirecting to app home");
            response.sendRedirect(contextPath + SecurityUtil.getHomePage());
            return;
        }

        // Not logged in and trying to open a protected page → redirect to login
        if (!isLoggedIn && !PUBLIC_PAGES.contains(requestPath)) {
            LOG.debug("Unauthenticated request for {}, redirecting to login", requestPath);
            response.sendRedirect(contextPath + "/zul/login.zul");
            return;
        }

        chain.doFilter(req, res);
    }

    @Override
    public void destroy() {
    }

    // Re-fetches the user from DB if 30 seconds have passed since the last check
    // If the user is now locked or deleted, invalidates the session and redirects to login
    private User revalidateUserIfNeeded(HttpSession session, User user, String contextPath,
                                        HttpServletResponse response) throws IOException {
        Long lastCheckMillis = (Long) session.getAttribute("SEC_LAST_CHECK");
        long now             = System.currentTimeMillis();

        // Still within the 30-second window — use the cached user
        if (lastCheckMillis != null && (now - lastCheckMillis) <= RECHECK_INTERVAL_MS) {
            return user;
        }

        User freshUser = userService.findByUsername(user.getUsername()).orElse(null);
        if (freshUser == null || !freshUser.isActive() || freshUser.isLocked()) {
            session.invalidate();
            LOG.info("SecurityFilter: session invalidated for user '{}' — DB re-check failed",
                    user.getUsername());
            response.sendRedirect(contextPath + "/zul/login.zul");
            return null;
        }

        // Update session with fresh user data and reset the check timer
        session.setAttribute(SecurityUtil.SESSION_USER_KEY, freshUser);
        session.setAttribute("SEC_LAST_CHECK", now);
        return freshUser;
    }

    // Removes the context path prefix from the full URI to get just the page path
    private static String stripContextPath(String uri, String contextPath) {
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    // Returns true for static files and ZK internal paths that should skip all checks
    private static boolean isStaticOrInternalRequest(String path) {
        return path.startsWith("/zkau")
                || path.startsWith("/zkcomet")
                || path.endsWith(".dsp")
                || path.endsWith(".wpd")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".png")
                || path.endsWith(".gif")
                || path.endsWith(".jpg")
                || path.endsWith(".ico")
                || path.endsWith(".woff2")
                || path.endsWith(".woff")
                || path.endsWith(".ttf");
    }
}