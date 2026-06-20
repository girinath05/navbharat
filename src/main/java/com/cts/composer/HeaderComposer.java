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
import org.zkoss.zul.Timer;

public class HeaderComposer extends SelectorComposer<Component> {

    private static final String SESS_CLOCK_VISIBLE = "header_clock_visible";

    @Wire private Timer hdrTimer;
    @Wire private Label lblHdrTime;
    @Wire private Label lblHdrDate;
    @Wire private Label lblHdrAvatar;
    @Wire private Div   hdrCentre;        // now a direct ZK child — @Wire works
    @Wire private Div   avatarBtn;
    @Wire private Div   profilePopup;
    @Wire private Div   ppOverlay;
    @Wire private Div   clockTogglePill;
    @Wire private Label lblClockToggle;
    @Wire private Label lblPopupAvatar;
    @Wire private Label lblPopupName;
    @Wire private Label lblPopupEmail;
    @Wire private Label lblPopupRole;
    @Wire private Label lblSessionTime;

    private long sessionStartMillis;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a");
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy");
    private boolean popupOpen    = false;
    private boolean clockVisible = true;

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        sessionStartMillis = System.currentTimeMillis();

        // Restore clock toggle state from session
        Object saved = Sessions.getCurrent().getAttribute(SESS_CLOCK_VISIBLE);
        clockVisible = (saved == null) ? true : Boolean.TRUE.equals(saved);
        applyClockState();

        // User info
        String username = (String) Sessions.getCurrent().getAttribute(LoginComposer.SESS_USER_NAME);
        String role     = (String) Sessions.getCurrent().getAttribute(LoginComposer.SESS_USER_ROLE);
        String email    = username != null
                ? username.toLowerCase().replace(" ", ".") + "@navbharatbank.in"
                : null;

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

    // ── TIMER ──────────────────────────────────────────────────────

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

    // ── AVATAR / POPUP ─────────────────────────────────────────────

    @Listen("onClick = #avatarBtn")
    public void onAvatarClick() {
        if (popupOpen) closePopup();
        else           openPopup();
    }

    private void openPopup()  {
        profilePopup.setSclass("profile-popup pp-visible");
        ppOverlay.setVisible(true);
        popupOpen = true;
    }

    private void closePopup() {
        profilePopup.setSclass("profile-popup");
        ppOverlay.setVisible(false);
        popupOpen = false;
    }

    /** Fires when the user clicks anywhere outside the popup (header,
     *  sidebar, or dashboard center — the overlay sits above all of them). */
    @Listen("onClick = #ppOverlay")
    public void onOverlayClick() {
        closePopup();
    }

    // ── CLOCK TOGGLE ───────────────────────────────────────────────

    @Listen("onClick = #btnToggleClock")
    public void onToggleClock() {
        clockVisible = !clockVisible;
        Sessions.getCurrent().setAttribute(SESS_CLOCK_VISIBLE, clockVisible);
        applyClockState();
    }

    /**
     * ON  (green)  → hdrCentre visible,   pill green,  label "ON"
     * OFF (grey)   → hdrCentre hidden,    pill grey,   label "OFF"
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

    // ── MENU ACTIONS ───────────────────────────────────────────────

    @Listen("onClick = #btnViewProfile")
    public void onViewProfile(Event event) {
        closePopup();
        Executions.sendRedirect("/zul/viewProfile.zul");
    }

    @Listen("onClick = #btnChangePassword")
    public void onChangePassword(Event event) {
        closePopup();
        Executions.sendRedirect("/zul/changePassword.zul");
    }

    @Listen("onClick = #btnEditAccount")
    public void onEditAccount(Event event) {
        closePopup();
        Executions.sendRedirect("/zul/editAccount.zul");
    }

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