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
import com.cts.uam.service.UserServiceImpl;
import com.cts.util.SecurityUtil;

public class HeaderComposer extends SelectorComposer<Component> {

    // Session key used to persist the clock ON/OFF preference across header re-renders
    private static final String SESS_CLOCK_VISIBLE = "header_clock_visible";

    // ── HEADER WIRES ───────────────────────────────────────────────
    // ZK injects these components from header.zul using their matching id attributes
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
    // Tracks when this session started (in ms) to calculate elapsed session time
    private long sessionStartMillis;
    // Tracks whether the profile popup is currently open or closed
    private boolean popupOpen    = false;
    // Tracks current clock visibility state; true = clock is shown in header
    private boolean clockVisible = true;

    // Formatters for displaying time and date in the header labels
    private final SimpleDateFormat timeFormat  = new SimpleDateFormat("hh:mm a");
    private final SimpleDateFormat dateFormat  = new SimpleDateFormat("dd MMM yyyy");
    // Service used to call changeOwnPassword() during password change flow
    private final UserService      userService = new UserServiceImpl();

    // ══════════════════════════════════════════════════════════════
    // INIT
    // ══════════════════════════════════════════════════════════════

    /**
     * Called by ZK after all @Wire fields are injected.
     * Initializes session start time, clock state, and user profile labels.
     */
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
            // Store the start time only on first load; reuse it on subsequent header re-renders
            if (stored == null) {
                stored = System.currentTimeMillis();
                httpSession.setAttribute("SESSION_START_MILLIS", stored);
            }
            sessionStartMillis = stored;
        } else {
            // Fallback: if no HTTP session exists, treat current time as start
            sessionStartMillis = System.currentTimeMillis();
        }

        // Restore clock toggle state from ZK session; default to visible if not set yet
        Object saved = Sessions.getCurrent().getAttribute(SESS_CLOCK_VISIBLE);
        clockVisible = (saved == null) ? true : Boolean.TRUE.equals(saved);
        applyClockState();

        // FIX 6: SESSION_USER_KEY holds a typed User object — casting to
        // String caused ClassCastException / null and role showed username value.
        Object sessionAttr = Sessions.getCurrent().getAttribute(SecurityUtil.SESSION_USER_KEY);
        // Safe pattern-matching cast; sessionUser will be null if session has no User
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

        // Provide safe fallback values so header never shows blank/null text
        if (username == null || username.isEmpty()) username = "Administrator";
        if (email    == null || email.isEmpty())    email    = "admin@navbharatbank.in";
        if (role     == null || role.isEmpty())     role     = "OPERATOR";

        // First character of username (uppercased) is used as the avatar letter in header and popup
        String avatarLetter = String.valueOf(username.charAt(0)).toUpperCase();

        // Populate all profile labels with resolved user data
        lblHdrAvatar.setValue(avatarLetter);
        lblPopupAvatar.setValue(avatarLetter);
        lblPopupName.setValue(username);
        lblPopupEmail.setValue(email);
        lblPopupRole.setValue(role.toUpperCase());

        // Set the initial clock/date values immediately so header doesn't show defaults until first tick
        updateClock();
    }

    // ══════════════════════════════════════════════════════════════
    // TIMER
    // ══════════════════════════════════════════════════════════════

    /**
     * Fires every 1 second via hdrTimer; updates the live clock and the session duration counter.
     */
    @Listen("onTimer = #hdrTimer")
    public void onTick(Event event) {
        updateClock();
        updateSessionTime();
    }

    /**
     * Formats the current system time and date and pushes them to the header labels.
     */
    private void updateClock() {
        Date now = new Date();
        lblHdrTime.setValue(timeFormat.format(now).toUpperCase());
        lblHdrDate.setValue(dateFormat.format(now).toUpperCase());
    }

    /**
     * Calculates elapsed time since sessionStartMillis and updates the session label in the popup footer.
     */
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

    /**
     * Toggles the profile popup open or closed when the avatar button is clicked.
     */
    @Listen("onClick = #avatarBtn")
    public void onAvatarClick() {
        if (popupOpen) closePopup();
        else           openPopup();
    }

    /**
     * Makes the profile popup visible and shows the overlay to catch outside clicks.
     */
    private void openPopup() {
        profilePopup.setSclass("profile-popup pp-visible");
        ppOverlay.setVisible(true);     // FROM V1 — show click-outside catcher
        popupOpen = true;
    }

    /**
     * Hides the profile popup and removes the overlay so normal page interaction resumes.
     */
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

    /**
     * Flips the clock visibility flag, saves the new state in ZK session, and updates the UI.
     */
    @Listen("onClick = #btnToggleClock")
    public void onToggleClock() {
        clockVisible = !clockVisible;
        // Persist preference in ZK session so it survives page navigation within the same session
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

    /**
     * Closes the profile popup, clears any previous form data, and shows the change-password modal.
     */
    @Listen("onClick = #btnChangePassword")
    public void onChangePassword() {
        closePopup();
        clearChpwForm();
        chpwModalOverlay.setVisible(true);
    }

    /**
     * Hides the modal and clears all input fields when X or Cancel is clicked.
     */
    @Listen("onClick = #btnChpwClose; onClick = #btnChpwCancel")
    public void onChpwClose() {
        chpwModalOverlay.setVisible(false);
        clearChpwForm();
    }

    /**
     * Validates all three password fields then delegates to userService to update the password in DB.
     */
    @Listen("onClick = #btnSavePassword")
    public void onSavePassword() {
        // Clear any previous error message before re-validating
        lblChpwError.setValue("");

        String currentPw  = txtCurrentPw.getValue().trim();
        String newPw      = txtNewPw.getValue().trim();
        String confirmPw  = txtConfirmPw.getValue().trim();

        // Guard: all three fields must be filled before proceeding
        if (currentPw.isEmpty() || newPw.isEmpty() || confirmPw.isEmpty()) {
            lblChpwError.setValue("All fields are required.");
            return;
        }
        // Guard: new password and confirm must match exactly
        if (!newPw.equals(confirmPw)) {
            lblChpwError.setValue("New password and confirm password do not match.");
            return;
        }
        // Guard: new password must actually differ from the current one
        if (newPw.equals(currentPw)) {
            lblChpwError.setValue("New password must be different from current password.");
            return;
        }

        try {
            // Fetch the currently logged-in username from session to pass to the service
            String username = SecurityUtil.getCurrentUserId();
            // Service verifies current password against DB and saves the hashed new password
            userService.changeOwnPassword(username, currentPw, newPw);

            chpwModalOverlay.setVisible(false);
            clearChpwForm();

            // Notify user of success with a standard ZK info dialog
            Messagebox.show(
                "Password changed successfully.",
                "Done", Messagebox.OK, Messagebox.INFORMATION);

        } catch (IllegalArgumentException ex) {
            // Service throws this for wrong current password or policy violations
            lblChpwError.setValue(ex.getMessage());
        } catch (Exception ex) {
            // Catch-all for unexpected DB or service failures
            lblChpwError.setValue("Failed to change password. Please try again.");
        }
    }

    /**
     * Resets all three textboxes and the error label to blank; called on open and close of the modal.
     */
    private void clearChpwForm() {
        txtCurrentPw.setValue("");
        txtNewPw.setValue("");
        txtConfirmPw.setValue("");
        lblChpwError.setValue("");
    }

    // ══════════════════════════════════════════════════════════════
    // LOGOUT
    // ══════════════════════════════════════════════════════════════

    /**
     * Closes the popup and shows a YES/NO confirmation dialog before logging the user out.
     */
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
            // Lambda handles the dialog result; only proceeds with logout on YES
            clickEvent -> {
                if (Messagebox.Button.YES.equals(clickEvent.getButton())) {
                    doLogout();
                }
            }
        );
    }

    /**
     * Invalidates the ZK session and redirects the browser back to the login page.
     */
    private void doLogout() {
        Sessions.getCurrent().invalidate();
        Executions.sendRedirect("/zul/login.zul");
    }
}