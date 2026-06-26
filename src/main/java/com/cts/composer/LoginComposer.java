package com.cts.composer;

import com.cts.uam.model.AuditLog;
import com.cts.uam.model.AuthResult;
import com.cts.uam.model.User;
import com.cts.uam.service.UserService;
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

    private final UserService userService = new UserService();

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        if (isUserAlreadyLoggedIn()) {
            Executions.sendRedirect(SecurityUtil.getHomePage());
            return;
        }

        if(usernameField!=null)usernameField.setFocus(true);
        showAccessDeniedMessageIfPresent();
    }

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

        // Prevent double submit while authentication is running
        loginButton.setDisabled(true);
        loginButton.setLabel("Authenticating...");
        try {
            authenticateUser(username, password);
        } finally {
            loginButton.setDisabled(false);
            loginButton.setLabel("LOGIN");
        }
    }

    private boolean isUserAlreadyLoggedIn() {
        Session zkSession = Executions.getCurrent().getSession();
        return zkSession != null && zkSession.getAttribute(SecurityUtil.SESSION_USER_KEY) instanceof User;
    }


    private void showAccessDeniedMessageIfPresent() {
        String errorCode = Executions.getCurrent().getParameter("error");
        if ("access_denied".equals(errorCode)) {
            showErrorMessage("Access denied. You do not have permission to view that page.");
        }
    }

    private void authenticateUser(String username, String password) {

        AuthResult authResult = userService.authenticate(username, password);

        switch (authResult.reason) {
            case USER_NOT_FOUND -> {
                showErrorMessage("Invalid User ID or password.");
            }
            case ACCOUNT_LOCKED -> {
                showErrorMessage("Your account is temporarily locked due to too many failed attempts. "
                        + "Please try again after 30 minutes or contact your administrator.");
            }
            case ACCOUNT_INACTIVE -> {
                showErrorMessage("Your account has been deactivated. Please contact your administrator.");
            }
            case WRONG_PASSWORD -> {
                User failedUser = authResult.user;

                int remainingAttempts = failedUser != null
                        ? Math.max(0, MAX_FAILED_ATTEMPTS - failedUser.getFailedAttempts())
                        : 0;

                if (remainingAttempts == 0) {
                    showErrorMessage("Too many failed attempts. Account locked for 30 minutes.");
                } else {
                    showErrorMessage("Invalid User ID or password. " + remainingAttempts + " attempt(s) remaining.");
                }
            }
            case SUCCESS -> {
                User loggedInUser = authResult.user;
                storeLoggedInUser(loggedInUser);
                LOG.info("User '{}' logged in from {}", username);
                Executions.sendRedirect(SecurityUtil.getHomePage());
            }
        }
    }

    private void storeLoggedInUser(User user) {
        Session zkSession = Executions.getCurrent().getSession();
        zkSession.setAttribute(SecurityUtil.SESSION_USER_KEY, user);

        HttpServletRequest request = (HttpServletRequest) Executions.getCurrent().getNativeRequest();
        HttpSession httpSession = request.getSession(true);
        httpSession.setAttribute(SecurityUtil.SESSION_USER_KEY, user);
    }


    private void showErrorMessage(String message) {
        errorLabel.setValue(message);
        errorBox.setVisible(true);

        // Clear password on any error so it never stays on screen
        passwordField.setValue("");
        passwordField.setFocus(true);
    }

    private void clearErrorMessage() {
        errorLabel.setValue("");
        errorBox.setVisible(false);
    }

}