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

// Protects the ZK AJAX endpoint (/zkau/*).
// Blocks requests from expired or locked sessions, but lets bootstrap calls and login-page requests through.
public class AuSecurityFilter implements Filter {

    @Override
    public void init(FilterConfig cfg) {
        // no-op
    }

    // Runs on every /zkau request — decides whether to allow or block it
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // ZK bootstrap requests have no desktop id yet — let them through
        String desktopId = request.getParameter("dtid");
        if (desktopId == null || desktopId.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        // AU calls from the login or index page are allowed — user is still signing in
        String referer = request.getHeader("Referer");
        if (isLoginFlowReferer(referer, request.getContextPath())) {
            chain.doFilter(req, res);
            return;
        }

        // Get the current user from session
        HttpSession session     = request.getSession(false);
        User        currentUser = session == null ? null
                : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);

        // Block if session is expired or account is locked/inactive
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

    // Returns true if the AU request came from the login or index page
    // Uses lowercase comparison to handle context path capitalisation differences (e.g. /Navbharat vs /navbharat)
    private boolean isLoginFlowReferer(String referer, String contextPath) {
        if (referer == null) return false;

        String lowerReferer     = referer.toLowerCase();
        String lowerContextPath = (contextPath != null ? contextPath : "").toLowerCase();

        // Login and index pages
        if (lowerReferer.contains("/zul/login.zul") || lowerReferer.contains("/zul/index.zul")) {
            return true;
        }

        // App root URL (e.g. http://localhost:8078/navbharat/ or /navbharat)
        if (!lowerContextPath.isEmpty()) {
            if (lowerReferer.endsWith(lowerContextPath + "/")
                    || lowerReferer.endsWith(lowerContextPath)) {
                return true;
            }
        }

        // Bare root URL with no context path (e.g. http://localhost:8078/)
        return lowerReferer.matches("https?://[^/]+(:\\d+)?/?");
    }
}