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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class AccessControlFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(AccessControlFilter.class);

    // Runs once when the filter is registered
    @Override
    public void init(FilterConfig filterConfig) {
        LOG.info("AccessControlFilter initialized");
    }

    // Runs on every request — checks if the user is allowed to access the requested page
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // Strip context path so we get just /zul/login.zul instead of /Navbharat/zul/login.zul
        String requestPath = stripContextPath(httpRequest.getRequestURI(), httpRequest.getContextPath());

        // Skip access checks for CSS, JS, images, and ZK internal requests
        if (isStaticOrInternalRequest(requestPath)) {
            chain.doFilter(request, response);
            return;
        }

        // Get current user from session — passing false so we don't create a new session per request
        HttpSession session  = httpRequest.getSession(false);
        User currentUser = session == null ? null
                : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);

        // Allow the request if the user has permission for this page
        if (SecurityUtil.canAccessPage(requestPath, currentUser)) {
            chain.doFilter(request, response);
            return;
        }

        boolean isLoggedIn = currentUser != null;

        // Not logged in → send to login page
        if (!isLoggedIn) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/zul/login.zul");
            return;
        }

        // Logged in but trying to access the app shell directly → 403 Forbidden
        if ("/zul/app.zul".equals(requestPath)) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // Logged in but no permission for the page → redirect to login with access denied message
        httpResponse.sendRedirect(httpRequest.getContextPath() + "/zul/login.zul?error=access_denied");
    }

    @Override
    public void destroy() {
        // no-op
    }

    // Returns true for static files and ZK internal requests that should skip access checks
    private static boolean isStaticOrInternalRequest(String path) {
        return path == null
                || path.startsWith("/zkau")
                || path.startsWith("/zkcomet")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".png")
                || path.endsWith(".gif")
                || path.endsWith(".jpg")
                || path.endsWith(".ico")
                || path.endsWith(".woff2")
                || path.endsWith(".woff")
                || path.endsWith(".ttf")
                || path.endsWith(".dsp")
                || path.endsWith(".wpd");
    }

    // Removes the app context path prefix from the full URI to get just the page path
    private static String stripContextPath(String requestUri, String contextPath) {
        if (requestUri == null) return "";

        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }

        return requestUri;
    }
}