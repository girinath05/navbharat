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
 */
public class AuSecurityFilter implements Filter {

    @Override
    public void init(FilterConfig cfg) {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // ZK bootstrap/init requests do not yet have a desktop id.it is not created yet 
        String desktopId = request.getParameter("dtid");
        if (desktopId == null || desktopId.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        // Login and index pages may fire AU calls while the user is still signing in  it is url of pages in string
        String referer = request.getHeader("Referer");
        if (isLoginFlowReferer(referer)) {
            chain.doFilter(req, res);
            return;
        }

        HttpSession session = request.getSession(false);
        User currentUser = session == null ? null
                : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);
        
        //this checking user if null or its status is changed then it will send to login page
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

    private boolean isLoginFlowReferer(String referer) {
        if (referer == null) {
            return false;
        }

        return referer.contains("/zul/login.zul")
                || referer.contains("/zul/index.zul")
                || referer.endsWith("/Navbharat/")
                || referer.endsWith("/Navbharat");
    }
}