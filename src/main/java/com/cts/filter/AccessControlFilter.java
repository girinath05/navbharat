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

    @Override
    public void init(FilterConfig filterConfig) {
        LOG.info("AccessControlFilter initialized");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        //it takes out context path from this Navbharat/zul/login.zul  
        String requestPath = stripContextPath(httpRequest.getRequestURI(), httpRequest.getContextPath());

        // to skip internal request like css js,zkau etc
        if (isStaticOrInternalRequest(requestPath)) {
            chain.doFilter(request, response);
            return;
        }
        //session is not created then it will give null and if we write true then it will create one new session in every requese
        HttpSession session = httpRequest.getSession(false);
        User currentUser = session == null ? null
                : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);

        // Allow the request only when the current user has access to the page
        if (SecurityUtil.canAccessPage(requestPath, currentUser)) {
            chain.doFilter(request, response);
            return;
        }

        //if user is not logged then it sets false
        boolean isLoggedIn = currentUser != null;
        
        //if user is not logged then it redirect to login page
        if (!isLoggedIn) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/zul/login.zul");
            return;
        }

        // Logged-in users should get a clear forbidden response for the app shell
        if ("/zul/app.zul".equals(requestPath)) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        httpResponse.sendRedirect(httpRequest.getContextPath() + "/zul/login.zul?error=access_denied");
    }

    @Override
    public void destroy() {
        // no-op
    }

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

    private static String stripContextPath(String requestUri, String contextPath) {
        if (requestUri == null) {
            return "";
        }

        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }

        return requestUri;
    }
}