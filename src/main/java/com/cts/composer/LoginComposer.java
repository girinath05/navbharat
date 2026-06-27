package com.cts.composer;

import com.cts.uam.model.AuditLog;
import com.cts.uam.model.AuthResult;
import com.cts.uam.model.User;
import com.cts.uam.service.UserServiceImpl;
import com.cts.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;
import org.zkoss.zul.Textbox;

public class LoginComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(LoginComposer.class);

    // Keep this in sync with the lockout rule in UserService
    private static final int MAX_FAILED_ATTEMPTS = 5;

    @Wire("#txtUsername")
    private Textbox usernameField;
    @Wire("#txtPassword")
    private Textbox passwordField;
    @Wire("#btnLogin")
    private Button loginButton;
    @Wire("#lblError")
    private Label errorLabel;
    @Wire("#divError")
    private Div errorBox;
    @Wire("#lblCapsLock")
    private Label capsLockLabel;

    private final UserServiceImpl userService = new UserServiceImpl();

    // ── INIT ──────────────────────────────────────────────────────

    // Runs when login page loads — redirects to home if already logged in,
    // otherwise focuses the username field and shows any access-denied message
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        if (isUserAlreadyLoggedIn()) {
            Executions.sendRedirect(SecurityUtil.getHomePage());
            return;
        }

        if (usernameField != null)
            usernameField.setFocus(true);
        showAccessDeniedMessageIfPresent();
    }

    // ── LOGIN ─────────────────────────────────────────────────────

    // Triggered on Login button click or pressing Enter in username/password field
    // Validates inputs, then calls authenticateUser()
    @Listen("onClick = #btnLogin; onOK = #txtUsername; onOK = #txtPassword")
    public void onLogin() {
        clearErrorMessage();

        String username = usernameField.getValue().trim();
        String password = passwordField.getValue();

        if (username.isEmpty()) {
            showErrorMessage("Please enter your User ID.");
            usernameField.setFocus(true);
            return;
        }

        if (password.isEmpty()) {
            showErrorMessage("Please enter your password.");
            passwordField.setFocus(true);
            return;
        }

        // Disable the button while authentication runs to prevent double submit
        loginButton.setDisabled(true);
        loginButton.setLabel("Authenticating...");
        try {
            authenticateUser(username, password);
        } finally {
            loginButton.setDisabled(false);
            loginButton.setLabel("LOGIN");
        }
    }

    // ── AUTH ──────────────────────────────────────────────────────

    // Checks if a user is already logged in by looking for a User object in ZK
    // session
    private boolean isUserAlreadyLoggedIn() {
        Session zkSession = Executions.getCurrent().getSession();
        return zkSession != null && zkSession.getAttribute(SecurityUtil.SESSION_USER_KEY) instanceof User;
    }

    // Shows "access denied" error if the user was redirected here after trying a
    // restricted page
    private void showAccessDeniedMessageIfPresent() {
        String errorCode = Executions.getCurrent().getParameter("error");
        if ("access_denied".equals(errorCode)) {
            showErrorMessage("Access denied. You do not have permission to view that page.");
        }
    }

    // Calls the auth service and handles each possible result — success, wrong
    // password, locked, etc.
    private void authenticateUser(String username, String password) {
        AuthResult authResult = userService.authenticate(username, password);
        String ip = getClientIp();

        switch (authResult.reason) {
            case USER_NOT_FOUND -> {
                saveAudit(username, "LOGIN_FAILURE", ip, "Login failed: user not found");
                showErrorMessage("Invalid User ID or password.");
            }
            case ACCOUNT_LOCKED -> {
                saveAudit(username, "ACCOUNT_LOCKED", ip, "Login blocked: account is locked");
                showErrorMessage("Your account is temporarily locked due to too many failed attempts. "
                        + "Please try again after 30 minutes or contact your administrator.");
            }
            case ACCOUNT_INACTIVE -> {
                saveAudit(username, "LOGIN_FAILURE", ip, "Login blocked: account inactive/disabled");
                showErrorMessage("Your account has been deactivated. Please contact your administrator.");
            }
            case WRONG_PASSWORD -> {
                User failedUser = authResult.user;
                int remainingAttempts = failedUser != null
                        ? Math.max(0, MAX_FAILED_ATTEMPTS - failedUser.getFailedAttempts())
                        : 0;
                saveAudit(username, "LOGIN_FAILURE", ip,
                        "Wrong password — " + remainingAttempts + " attempt(s) remaining");
                if (remainingAttempts == 0) {
                    showErrorMessage("Too many failed attempts. Account locked for 30 minutes.");
                } else {
                    showErrorMessage("Invalid User ID or password. " + remainingAttempts + " attempt(s) remaining.");
                }
            }
            case SUCCESS -> {
                storeLoggedInUser(authResult.user);
                LOG.info("User '{}' logged in from {}", username, ip);
                saveAudit(username, "LOGIN_SUCCESS", ip, "Login successful");
                Executions.sendRedirect(SecurityUtil.getHomePage());
            }
        }
    }

    // ── AUDIT / IP ────────────────────────────────────────────────

    // Saves a login audit entry — never throws so it never blocks the login flow
    private void saveAudit(String username, String eventType, String ip, String details) {
        try {
            userService.saveAuditLog(new AuditLog(username, eventType, ip, getUserAgent(), details));
        } catch (Exception ex) {
            LOG.warn("Could not save login audit log for '{}': {}", username, ex.getMessage());
        }
    }

    // Returns the real client IP — checks X-Forwarded-For first in case of a
    // reverse proxy
    private String getClientIp() {
        HttpServletRequest request = (HttpServletRequest) Executions.getCurrent().getNativeRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty())
            return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    // Returns the browser/device info from the request header
    private String getUserAgent() {
        HttpServletRequest request = (HttpServletRequest) Executions.getCurrent().getNativeRequest();
        return request.getHeader("User-Agent");
    }

    // ── SESSION ───────────────────────────────────────────────────

    // Saves the logged-in User object in both ZK session and HTTP session
    private void storeLoggedInUser(User user) {
        Session zkSession = Executions.getCurrent().getSession();
        zkSession.setAttribute(SecurityUtil.SESSION_USER_KEY, user);

        HttpServletRequest request = (HttpServletRequest) Executions.getCurrent().getNativeRequest();
        HttpSession httpSession = request.getSession(true);
        httpSession.setAttribute(SecurityUtil.SESSION_USER_KEY, user);
    }

    // ── ERROR ─────────────────────────────────────────────────────

    // Shows an error message and clears the password field
    private void showErrorMessage(String message) {
        errorLabel.setValue(message);
        errorBox.setVisible(true);
        passwordField.setValue("");
        passwordField.setFocus(true);
    }

    // Hides the error box and clears the error text
    private void clearErrorMessage() {
        errorLabel.setValue("");
        errorBox.setVisible(false);
    }
}