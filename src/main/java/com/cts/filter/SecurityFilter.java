package com.cts.filter;

import com.cts.uam.model.User;
import com.cts.uam.service.UserService;
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

/**
 * Blocks unauthenticated access before ZK creates a desktop.
 * Also re-checks user status periodically so locked/inactive accounts are stopped quickly.
 */
public class SecurityFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    private static final long RECHECK_INTERVAL_MS = 30_000L;

    // Pages that can be reached without logging in
    private static final Set<String> PUBLIC_PAGES = Set.of(
            "/", "/zul/index.zul", "/zul/login.zul"
    );

    // Logged-in users should not stay on the login page
    private static final Set<String> LOGIN_PAGES = Set.of("/zul/login.zul");

    private final UserService userService = new UserService();

    @Override
    public void init(FilterConfig cfg) {
        LOG.info("SecurityFilter initialized");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        //context path means path from like /Navbharat from this http://localhost:8080/Navbharat/zul/login.zul
        String contextPath = request.getContextPath();
        //it takes out context path from this Navbharat/zul/login.zul  
        String requestPath = stripContextPath(request.getRequestURI(), contextPath);

        // Skip ZK internal assets and non-ZUL requests like css js
        if (isStaticOrInternalRequest(requestPath) || !requestPath.endsWith(".zul")) {
            chain.doFilter(req, res);
            return;
        }
        
        //session is not created then it will give null and if we write true then it will create one new session in every requese
        HttpSession session = request.getSession(false);
        User currentUser = session == null ? null : (User) session.getAttribute(SecurityUtil.SESSION_USER_KEY);

        if (currentUser != null) {
            //here it is validatting if current user is locked or inaction by admin so it is check in every 30 sec or request comes in 30 sec
            currentUser = revalidateUserIfNeeded(session, currentUser, contextPath, response);
            if (currentUser == null) {
                return;
            }
            //fresh or cached both types of users checked this
            if (!currentUser.isActive() || currentUser.isLocked()) {
                session.invalidate();
                LOG.info("SecurityFilter: session invalidated for user '{}' — status={}",
                        currentUser.getUsername(), currentUser.getStatus());
                response.sendRedirect(contextPath + "/zul/login.zul");
                return;
            }
        }

        boolean isLoggedIn = currentUser != null;

        if (isLoggedIn && LOGIN_PAGES.contains(requestPath)) {
            LOG.debug("Logged-in user hit login page, redirecting to app home");
            response.sendRedirect(contextPath + SecurityUtil.getHomePage());
            return;
        }

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

    private User revalidateUserIfNeeded(HttpSession session, User user, String contextPath,
                                        HttpServletResponse response) throws IOException {
        Long lastCheckMillis = (Long) session.getAttribute("SEC_LAST_CHECK");
        long now = System.currentTimeMillis();

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

        session.setAttribute(SecurityUtil.SESSION_USER_KEY, freshUser);
        session.setAttribute("SEC_LAST_CHECK", now);
        return freshUser;
    }

    private static String stripContextPath(String uri, String contextPath) {
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

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