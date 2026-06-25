package com.cts.filter;

import com.cts.uam.model.User;
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

import java.io.IOException;

/**
 * Protects the ZK AJAX endpoint (/zkau/*).
 * Bootstrap calls and login-page callbacks are allowed through.
 * Real user actions from expired or locked sessions are blocked.
 *
 * FIX (June 2026): isLoginFlowReferer() now lowercases the Referer
 * header before comparing, so context paths like /navbharat (lowercase)
 * match correctly — previously /Navbharat (capital N) caused all ZK AU
 * calls from the login page to return 401 before the user could log in.
 */
public class AuSecurityFilter implements Filter {

    @Override
    public void init(FilterConfig cfg) {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // ZK bootstrap/init requests do not yet have a desktop id — let them through
        String desktopId = request.getParameter("dtid");
        if (desktopId == null || desktopId.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        // Login and index pages may fire AU calls while the user is still signing in
        String referer = request.getHeader("Referer");
        if (isLoginFlowReferer(referer, request.getContextPath())) {
            chain.doFilter(req, res);
            return;
        }

        HttpSession session     = request.getSession(false);
        User        currentUser = session == null ? null
                : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);

        // If user is null or account has been locked/deactivated — block with 401
        if (currentUser == null || !currentUser.isActive() || currentUser.isLocked()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Session expired or account locked.");
            return;
        }

        chain.doFilter(req, res);
    }

    @Override
    public void destroy() {
        // no-op
    }

    /**
     * Returns true when the AU call originates from the login or index page.
     *
     * Comparison is case-insensitive to handle context-path capitalisation
     * differences between deployments (e.g. /navbharat vs /Navbharat).
     *
     * Also accepts the actual runtime context path so this filter works
     * regardless of how the WAR is named or deployed.
     *
     * @param referer     value of the HTTP Referer header (may be null)
     * @param contextPath servlet context path (e.g. /navbharat)
     * @return true if the request originates from a public login-flow page
     */
    private boolean isLoginFlowReferer(String referer, String contextPath) {
        if (referer == null) return false;

        // Lowercase both sides — fixes /Navbharat vs /navbharat mismatch
        String lowerReferer      = referer.toLowerCase();
        String lowerContextPath  = (contextPath != null ? contextPath : "").toLowerCase();

        // Matches login and index ZUL pages
        if (lowerReferer.contains("/zul/login.zul") || lowerReferer.contains("/zul/index.zul")) {
            return true;
        }

        // Matches the app root (e.g. http://localhost:8078/navbharat/ or /navbharat)
        if (!lowerContextPath.isEmpty()) {
            if (lowerReferer.endsWith(lowerContextPath + "/")
                    || lowerReferer.endsWith(lowerContextPath)) {
                return true;
            }
        }

        // Fallback: bare root URL (context path is "" or "/")
        return lowerReferer.matches("https?://[^/]+(:\\d+)?/?");
    }
}