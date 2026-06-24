package com.cts.composer;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Timer;

import com.cts.uam.service.UserService;
import com.cts.util.SecurityUtil;

public class HeaderComposer extends SelectorComposer<Component> {

    private static final String SESS_CLOCK_VISIBLE = "header_clock_visible";

    // ── HEADER WIRES ───────────────────────────────────────────────
    @Wire private Timer  hdrTimer;
    @Wire private Label  lblHdrTime;
    @Wire private Label  lblHdrDate;
    @Wire private Label  lblHdrAvatar;
    @Wire private Div    hdrCentre;

    // ── AVATAR / POPUP WIRES ───────────────────────────────────────
    @Wire private Div    avatarBtn;
    @Wire private Div    profilePopup;
    @Wire private Div    ppOverlay;         // FROM V1 — click-outside catcher

    // ── POPUP CONTENT WIRES ────────────────────────────────────────
    @Wire private Label  lblPopupAvatar;
    @Wire private Label  lblPopupName;
    @Wire private Label  lblPopupEmail;
    @Wire private Label  lblPopupRole;
    @Wire private Label  lblSessionTime;

    // ── CLOCK TOGGLE WIRES ─────────────────────────────────────────
    @Wire private Div    clockTogglePill;
    @Wire private Label  lblClockToggle;

    // ── CHANGE PASSWORD MODAL WIRES ────────────────────────────────
    @Wire private Div     chpwModalOverlay;
    @Wire private Textbox txtCurrentPw;
    @Wire private Textbox txtNewPw;
    @Wire private Textbox txtConfirmPw;
    @Wire private Label   lblChpwError;

    // ── STATE ──────────────────────────────────────────────────────
    private long sessionStartMillis;
    private boolean popupOpen    = false;
    private boolean clockVisible = true;

    private final SimpleDateFormat timeFormat  = new SimpleDateFormat("hh:mm a");
    private final SimpleDateFormat dateFormat  = new SimpleDateFormat("dd MMM yyyy");
    private final UserService      userService = new UserService();

    // ══════════════════════════════════════════════════════════════
    // INIT
    // ══════════════════════════════════════════════════════════════

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // FIX 9a: persist session start time in HTTP session so the timer
        // survives header re-renders — does not reset on each compose.
        jakarta.servlet.http.HttpServletRequest httpReq =
            (jakarta.servlet.http.HttpServletRequest) Executions.getCurrent().getNativeRequest();
        jakarta.servlet.http.HttpSession httpSession = httpReq.getSession(false);
        if (httpSession != null) {
            Long stored = (Long) httpSession.getAttribute("SESSION_START_MILLIS");
            if (stored == null) {
                stored = System.currentTimeMillis();
                httpSession.setAttribute("SESSION_START_MILLIS", stored);
            }
            sessionStartMillis = stored;
        } else {
            sessionStartMillis = System.currentTimeMillis();
        }

        // Restore clock toggle state from ZK session
        Object saved = Sessions.getCurrent().getAttribute(SESS_CLOCK_VISIBLE);
        clockVisible = (saved == null) ? true : Boolean.TRUE.equals(saved);
        applyClockState();

        // FIX 6: SESSION_USER_KEY holds a typed User object — casting to
        // String caused ClassCastException / null and role showed username value.
        Object sessionAttr = Sessions.getCurrent().getAttribute(SecurityUtil.SESSION_USER_KEY);
        com.cts.uam.model.User sessionUser =
            (sessionAttr instanceof com.cts.uam.model.User u) ? u : null;

        String username = sessionUser != null ? sessionUser.getUsername() : null;
        String role     = sessionUser != null ? sessionUser.getRoleLabel()  : null;

        // FIX 9b: use real email from the User object; fall back to
        // synthetic address only when the stored email is blank.
        String email = (sessionUser != null
                && sessionUser.getEmail() != null
                && !sessionUser.getEmail().isBlank())
                    ? sessionUser.getEmail()
                    : (username != null
                        ? username.toLowerCase().replace(" ", ".") + "@navbharatbank.in"
                        : null);

        if (username == null || username.isEmpty()) username = "Administrator";
        if (email    == null || email.isEmpty())    email    = "admin@navbharatbank.in";
        if (role     == null || role.isEmpty())     role     = "OPERATOR";

        String avatarLetter = String.valueOf(username.charAt(0)).toUpperCase();

        lblHdrAvatar.setValue(avatarLetter);
        lblPopupAvatar.setValue(avatarLetter);
        lblPopupName.setValue(username);
        lblPopupEmail.setValue(email);
        lblPopupRole.setValue(role.toUpperCase());

        updateClock();
    }

    // ══════════════════════════════════════════════════════════════
    // TIMER
    // ══════════════════════════════════════════════════════════════

    @Listen("onTimer = #hdrTimer")
    public void onTick(Event event) {
        updateClock();
        updateSessionTime();
    }

    private void updateClock() {
        Date now = new Date();
        lblHdrTime.setValue(timeFormat.format(now).toUpperCase());
        lblHdrDate.setValue(dateFormat.format(now).toUpperCase());
    }

    private void updateSessionTime() {
        long elapsed = System.currentTimeMillis() - sessionStartMillis;
        long seconds = (elapsed / 1000) % 60;
        long minutes = (elapsed / (1000 * 60)) % 60;
        long hours   = (elapsed / (1000 * 60 * 60)) % 24;
        lblSessionTime.setValue(
            String.format("Active session · %02d:%02d:%02d", hours, minutes, seconds));
    }

    // ══════════════════════════════════════════════════════════════
    // AVATAR / POPUP
    // ══════════════════════════════════════════════════════════════

    @Listen("onClick = #avatarBtn")
    public void onAvatarClick() {
        if (popupOpen) closePopup();
        else           openPopup();
    }

    private void openPopup() {
        profilePopup.setSclass("profile-popup pp-visible");
        ppOverlay.setVisible(true);     // FROM V1 — show click-outside catcher
        popupOpen = true;
    }

    private void closePopup() {
        profilePopup.setSclass("profile-popup");
        ppOverlay.setVisible(false);    // FROM V1 — hide click-outside catcher
        popupOpen = false;
    }

    /** FROM V1 — fires when user clicks anywhere outside the popup.
     *  The transparent overlay sits above all page content (z-index 99998)
     *  but below the popup (z-index 99999), so any outside click lands here. */
    @Listen("onClick = #ppOverlay")
    public void onOverlayClick() {
        closePopup();
    }

    // ══════════════════════════════════════════════════════════════
    // CLOCK TOGGLE
    // ══════════════════════════════════════════════════════════════

    @Listen("onClick = #btnToggleClock")
    public void onToggleClock() {
        clockVisible = !clockVisible;
        Sessions.getCurrent().setAttribute(SESS_CLOCK_VISIBLE, clockVisible);
        applyClockState();
    }

    /**
     * ON  (green) → hdrCentre visible,  pill green, label "ON"
     * OFF (grey)  → hdrCentre hidden,   pill grey,  label "OFF"
     */
    private void applyClockState() {
        hdrCentre.setVisible(clockVisible);
        if (clockVisible) {
            clockTogglePill.setSclass("pp-toggle pp-toggle-on");
            lblClockToggle.setValue("ON");
        } else {
            clockTogglePill.setSclass("pp-toggle pp-toggle-off");
            lblClockToggle.setValue("OFF");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CHANGE PASSWORD MODAL
    // ══════════════════════════════════════════════════════════════

    /** Opens the inline change-password modal. */
    @Listen("onClick = #btnChangePassword")
    public void onChangePassword() {
        closePopup();
        clearChpwForm();
        chpwModalOverlay.setVisible(true);
    }

    /** Closes the modal via the X button or Cancel button. */
    @Listen("onClick = #btnChpwClose; onClick = #btnChpwCancel")
    public void onChpwClose() {
        chpwModalOverlay.setVisible(false);
        clearChpwForm();
    }

    /** Validates inputs and calls the backend to change the password. */
    @Listen("onClick = #btnSavePassword")
    public void onSavePassword() {
        lblChpwError.setValue("");

        String currentPw  = txtCurrentPw.getValue().trim();
        String newPw      = txtNewPw.getValue().trim();
        String confirmPw  = txtConfirmPw.getValue().trim();

        if (currentPw.isEmpty() || newPw.isEmpty() || confirmPw.isEmpty()) {
            lblChpwError.setValue("All fields are required.");
            return;
        }
        if (!newPw.equals(confirmPw)) {
            lblChpwError.setValue("New password and confirm password do not match.");
            return;
        }
        if (newPw.equals(currentPw)) {
            lblChpwError.setValue("New password must be different from current password.");
            return;
        }

        try {
            String username = SecurityUtil.getCurrentUserId();
            userService.changeOwnPassword(username, currentPw, newPw);

            chpwModalOverlay.setVisible(false);
            clearChpwForm();

            Messagebox.show(
                "Password changed successfully.",
                "Done", Messagebox.OK, Messagebox.INFORMATION);

        } catch (IllegalArgumentException ex) {
            lblChpwError.setValue(ex.getMessage());
        } catch (Exception ex) {
            lblChpwError.setValue("Failed to change password. Please try again.");
        }
    }

    private void clearChpwForm() {
        txtCurrentPw.setValue("");
        txtNewPw.setValue("");
        txtConfirmPw.setValue("");
        lblChpwError.setValue("");
    }

    // ══════════════════════════════════════════════════════════════
    // LOGOUT
    // ══════════════════════════════════════════════════════════════

    @Listen("onClick = #btnLogout")
    public void onLogout(Event event) {
        closePopup();
        Messagebox.show(
            "Are you sure you want to logout?\nYour current session will be ended.",
            "Confirm Logout",
            new Messagebox.Button[] { Messagebox.Button.YES, Messagebox.Button.NO },
            new String[]            { "Yes, Logout", "Cancel" },
            Messagebox.QUESTION,
            Messagebox.Button.NO,
            clickEvent -> {
                if (Messagebox.Button.YES.equals(clickEvent.getButton())) {
                    doLogout();
                }
            }
        );
    }

    private void doLogout() {
        Sessions.getCurrent().invalidate();
        Executions.sendRedirect("/zul/index.zul");
    }
}