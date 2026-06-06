package com.cts.composer;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;

import java.util.Arrays;
import java.util.List;

public class SidebarComposer extends SelectorComposer<Component> {

    // ── Session keys for dropdown open/close state ─────────────────
    private static final String SESS_OUTWARD_OPEN         = "sidebar_outward_open";
    private static final String SESS_INWARD_OPEN          = "sidebar_inward_open";
    private static final String SESS_OUTWARD_REPORTS_OPEN = "sidebar_outwardReports_open";
    private static final String SESS_INWARD_REPORTS_OPEN  = "sidebar_inwardReports_open";
    private static final String SESS_USER_OPEN            = "sidebar_user_open";

    // ── Session key for the currently active menu item id ──────────
    private static final String SESS_ACTIVE_ITEM = "sidebar_active_item";

    // Default active item id (shown on first load / fresh session)
    private static final String DEFAULT_ACTIVE_ID = "menuDashboard";

    // ── Dropdown menus ─────────────────────────────────────────────
    @Wire private Div   outwardMenu;
    @Wire private Div   inwardMenu;
    @Wire private Div   outwardReportsMenu;
    @Wire private Div   inwardReportsMenu;
    @Wire private Div   userMenu;

    @Wire private Label outwardArrow;
    @Wire private Label inwardArrow;
    @Wire private Label outwardReportsArrow;
    @Wire private Label inwardReportsArrow;
    @Wire private Label userArrow;

    // ── All top-level and sub menu-item divs (must have id in ZUL) ─
    @Wire private Div menuDashboard;
    @Wire private Div menuScanModule;
    @Wire private Div menuBatchManagement;
    @Wire private Div menuVerification1;
    @Wire private Div menuVerification2;
    @Wire private Div menuCxfCibf;
    @Wire private Div menuUploadService;
    @Wire private Div menuMicrService;
    @Wire private Div menuReturnCheques;
    @Wire private Div menuCheckerQueue;
    @Wire private Div menuEscalationQueue;
    @Wire private Div menuCxfReport;
    @Wire private Div menuCxbfReport;
    @Wire private Div menuMakerReport;
    @Wire private Div menuCheckerReport1;
    @Wire private Div menuCheckerReport2;
    @Wire private Div menuCheckerReport3;
    @Wire private Div menuRoleManagement;
    @Wire private Div menuUserManagement;
    @Wire private Div menuPendingUserApproval;
    @Wire private Div menuPendingRoleApproval;

    /** Flat list of every clickable menu-item div — used for clearing active. */
    private List<Div> allMenuItems;

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        allMenuItems = Arrays.asList(
            menuDashboard,
            menuScanModule, menuBatchManagement, menuVerification1,
            menuVerification2, menuCxfCibf,
            menuUploadService, menuMicrService, menuReturnCheques,
            menuCheckerQueue, menuEscalationQueue,
            menuCxfReport, menuCxbfReport,
            menuMakerReport, menuCheckerReport1, menuCheckerReport2, menuCheckerReport3,
            menuRoleManagement, menuUserManagement,
            menuPendingUserApproval, menuPendingRoleApproval
        );

        // Restore dropdown open/close states
        applyDropdownState(outwardMenu,        outwardArrow,        SESS_OUTWARD_OPEN);
        applyDropdownState(inwardMenu,         inwardArrow,         SESS_INWARD_OPEN);
        applyDropdownState(outwardReportsMenu, outwardReportsArrow, SESS_OUTWARD_REPORTS_OPEN);
        applyDropdownState(inwardReportsMenu,  inwardReportsArrow,  SESS_INWARD_REPORTS_OPEN);
        applyDropdownState(userMenu,           userArrow,           SESS_USER_OPEN);

        // Restore active menu item highlight
        String activeId = (String) Sessions.getCurrent().getAttribute(SESS_ACTIVE_ITEM);
        if (activeId == null) activeId = DEFAULT_ACTIVE_ID;
        applyActiveById(activeId);
    }

    // ── DROPDOWN HELPERS ───────────────────────────────────────────

    private void applyDropdownState(Div menu, Label arrow, String sessionKey) {
        boolean open = Boolean.TRUE.equals(Sessions.getCurrent().getAttribute(sessionKey));
        menu.setVisible(open);
        arrow.setValue(open ? "▼" : "▶");
    }

    private void toggle(Div menu, Label arrow, String sessionKey) {
        boolean open = !menu.isVisible();
        menu.setVisible(open);
        arrow.setValue(open ? "▼" : "▶");
        Sessions.getCurrent().setAttribute(sessionKey, open);
    }

    // ── ACTIVE STATE HELPERS ───────────────────────────────────────

    /** Remove 'active' sclass from every menu item, then set it on the target. */
    private void setActive(Div target) {
        for (Div item : allMenuItems) {
            if (item == null) continue;
            String sc = item.getSclass();
            if (sc == null) sc = "";
            // Remove 'active' token
            sc = sc.replaceAll("\\bactive\\b", "").trim();
            item.setSclass(sc);
        }
        if (target != null) {
            String sc = target.getSclass();
            if (sc == null) sc = "";
            if (!sc.contains("active")) {
                sc = (sc + " active").trim();
            }
            target.setSclass(sc);
            // Persist so refresh restores it
            Sessions.getCurrent().setAttribute(SESS_ACTIVE_ITEM, target.getId());
        }
    }

    /** Find the wired Div by its ZUL id string and activate it. */
    private void applyActiveById(String id) {
        if (id == null) return;
        for (Div item : allMenuItems) {
            if (item != null && id.equals(item.getId())) {
                setActive(item);
                return;
            }
        }
    }

    // ── TOGGLE LISTENERS ──────────────────────────────────────────

    @Listen("onClick=#outwardHeader")
    public void toggleOutwardMenu() {
        toggle(outwardMenu, outwardArrow, SESS_OUTWARD_OPEN);
    }

    @Listen("onClick=#inwardHeader")
    public void toggleInwardMenu() {
        toggle(inwardMenu, inwardArrow, SESS_INWARD_OPEN);
    }

    @Listen("onClick=#outwardReportsHeader")
    public void toggleOutwardReportsMenu() {
        toggle(outwardReportsMenu, outwardReportsArrow, SESS_OUTWARD_REPORTS_OPEN);
    }

    @Listen("onClick=#inwardReportsHeader")
    public void toggleInwardReportsMenu() {
        toggle(inwardReportsMenu, inwardReportsArrow, SESS_INWARD_REPORTS_OPEN);
    }

    @Listen("onClick=#userHeader")
    public void toggleUserMenu() {
        toggle(userMenu, userArrow, SESS_USER_OPEN);
    }

    // ── PAGE NAVIGATION ───────────────────────────────────────────

    @Listen("onClick=.cts-menu-item")
    public void navigate(Event event) {
        // Walk up from clicked element to the div that has pagePath
        Component target = event.getTarget();
        while (target != null && target.getAttribute("pagePath") == null) {
            target = target.getParent();
        }
        if (target == null) return;

        String pagePath = target.getAttribute("pagePath").toString();

        // Update active highlight — target is the .cts-menu-item Div
        if (target instanceof Div) {
            setActive((Div) target);
        }

        // Save last sub-page so refresh restores the content area
        Sessions.getCurrent().setAttribute(DashboardComposer.SESS_LAST_SUB_PAGE, pagePath);

        DashboardComposer.getInstance().loadPage(pagePath);
    }
}