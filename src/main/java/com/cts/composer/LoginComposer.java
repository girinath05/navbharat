package com.cts.composer;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Label;
import org.zkoss.zul.Textbox;

public class LoginComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;

    public static final String SESS_LOGGED_USER  = "loggedUser";
    public static final String SESS_USER_NAME    = "userName";
    public static final String SESS_USER_ROLE    = "userRole";
    public static final String SESS_USER_BRANCH  = "userBranch";
    public static final String SESS_CURRENT_PAGE = "currentPage";

    @Wire("#userId")
    private Textbox userId;

    @Wire("#password")
    private Textbox password;

    @Wire("#lblError")
    private Label lblError;

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        Session session = Sessions.getCurrent();
        Object existing = session.getAttribute(SESS_LOGGED_USER);

        if (existing != null && !existing.toString().trim().isEmpty()) {
            // Already logged in — go to dashboard
            Executions.sendRedirect("/zul/dashboard.zul");
        }
    }

    @Listen("onClick = #btnLogin; onOK = #userId; onOK = #password")
    public void onLogin() {

        String uid  = (userId   != null) ? userId.getValue().trim()   : "";
        String pass = (password != null) ? password.getValue().trim() : "";

        if (uid.isEmpty()) {
            showError("Please enter your User ID.");
            return;
        }
        if (pass.isEmpty()) {
            showError("Please enter your Password.");
            return;
        }

        String resolvedName   = resolveDisplayName(uid);
        String resolvedRole   = resolveRole(uid);

        Session session = Sessions.getCurrent();
        session.setAttribute(SESS_LOGGED_USER,  uid);
        session.setAttribute(SESS_USER_NAME,    resolvedName);
        session.setAttribute(SESS_USER_ROLE,    resolvedRole);
        session.setAttribute(SESS_USER_BRANCH,  "BLR01");

        Executions.sendRedirect("/zul/dashboard.zul");
    }

    private void showError(String msg) {
        if (lblError != null) {
            lblError.setValue(msg);
            lblError.setVisible(true);
        }
    }

    private String resolveDisplayName(String uid) {
        switch (uid.toLowerCase()) {
            case "admin":    return "Administrator";
            case "maker1":   return "Rajesh Kumar";
            case "checker1": return "Priya Nair";
            default:         return uid.toUpperCase();
        }
    }

    private String resolveRole(String uid) {
        switch (uid.toLowerCase()) {
            case "admin":    return "ADMIN";
            case "maker1":   return "MAKER";
            case "checker1": return "CHECKER";
            default:         return "ADMIN";
        }
    }
}