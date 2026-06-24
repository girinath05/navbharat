/*
 * ============================================================
 *  Project  : Navbharat CTS Outward
 *  File     : SidebarComposer.java
 *  Package  : com.cts.composer
 *  Purpose  : Composer for sidebar.zul — accordion menu, access
 *             control enforcement, collapse/expand toggle.
 *  Author   : [Name]
 *  Date     : June 2026
 * ============================================================
 */

package com.cts.composer;

import com.cts.uam.model.User;
import com.cts.util.SecurityUtil;

import java.util.ArrayList;
import java.util.List;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.HtmlBasedComponent;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Div;
import org.zkoss.zul.Include;
import org.zkoss.zul.Label;

/**
 * File    : SidebarComposer.java
 * Package : com.cts.composer
 * Purpose : Drives the sidebar navigation component (sidebar.zul).
 *
 * <p>On compose:
 * <ol>
 *   <li>Loads the current user from {@link SecurityUtil} — redirects to login if absent.</li>
 *   <li>Removes menu items the user cannot access ({@link #applyMenuAccessRules}).</li>
 *   <li>Hides section headers whose child items were all removed ({@link #updateSectionVisibility}).</li>
 *   <li>Wires accordion click handlers for each collapsible menu group.</li>
 * </ol>
 *
 * <p>Navigation: every menu item's {@code onClick} calls
 * {@link #navigateToPage(String, Component)}, which delegates to
 * {@link DashboardComposer#navigateTo(String)} after updating active state.
 */
public class SidebarComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;

    // ── Accordion menu group wires ────────────────────────────────────
    @Wire("#outwardHeader")       private Div   outwardHeader;
    @Wire("#outwardMenu")         private Div   outwardMenu;
    @Wire("#outwardArrow")        private Label outwardArrow;

    @Wire("#inwardHeader")        private Div   inwardHeader;
    @Wire("#inwardMenu")          private Div   inwardMenu;
    @Wire("#inwardArrow")         private Label inwardArrow;

    @Wire("#outwardReportsHeader") private Div  outwardReportsHeader;
    @Wire("#outwardReportsMenu")   private Div  outwardReportsMenu;
    @Wire("#outwardReportsArrow")  private Label outwardReportsArrow;

    @Wire("#inwardReportsHeader") private Div   inwardReportsHeader;
    @Wire("#inwardReportsMenu")   private Div   inwardReportsMenu;
    @Wire("#inwardReportsArrow")  private Label inwardReportsArrow;

    @Wire("#userHeader")          private Div   userHeader;
    @Wire("#userMenu")            private Div   userMenu;
    @Wire("#userArrow")           private Label userArrow;

    @Wire("#sidebarToggle")       private Div   sidebarToggle;

    /** The currently authenticated user; loaded in {@link #doAfterCompose}. */
    private User    currentUser;
    /** Tracks sidebar collapsed/expanded state for toggle behaviour. */
    private boolean isCollapsed;

    /**
     * ZK lifecycle: compose → load user → apply access rules → wire accordions.
     *
     * @param comp root component of sidebar.zul
     * @throws Exception if ZK wiring or session redirect fails
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // ── Session guard ──────────────────────────────────────────────
        currentUser = SecurityUtil.getCurrentUser();
        if (currentUser == null) {
            Executions.sendRedirect("/zul/login.zul");
            return;
        }

        isCollapsed = getSclass(comp).contains("collapsed");

        // Remove menu items the current user cannot access
        applyMenuAccessRules(comp);

        // Hide section headers that no longer have visible items underneath
        updateSectionVisibility(comp);

        // Wire accordion toggle on each menu group header
        wireAccordion(outwardHeader,       outwardMenu,       outwardArrow);
        wireAccordion(inwardHeader,        inwardMenu,        inwardArrow);
        wireAccordion(outwardReportsHeader, outwardReportsMenu, outwardReportsArrow);
        wireAccordion(inwardReportsHeader,  inwardReportsMenu,  inwardReportsArrow);
        wireAccordion(userHeader,           userMenu,           userArrow);
    }

    // ── Access control ───────────────────────────────────────────────

    /**
     * Recursively walks the sidebar component tree and removes (detaches) any
     * menu-item node whose {@code page} attribute points to a path the current
     * user cannot access.
     *
     * @param root sidebar root or any parent component to scan
     */
    private void applyMenuAccessRules(Component root) {
        List<Component> children = new ArrayList<>(root.getChildren());
        for (Component child : children) {
            configureMenuNode(child);
        }
    }

    /**
     * Reads {@code page} and {@code perm} from this node only.
     * If the node has a {@code page} attribute:
     * <ul>
     *   <li>Checks permission + page access via {@link SecurityUtil}.</li>
     *   <li>Detaches the node if access is denied.</li>
     *   <li>Wires {@code onClick} to {@link #navigateToPage} if access is granted.</li>
     * </ul>
     * Nodes without a {@code page} attribute are recursed into (structural containers).
     *
     * @param node the component to configure
     */
    private void configureMenuNode(Component node) {
        String pagePath           = getOwnAttributeValue(node, "page");
        String requiredPermission = getOwnAttributeValue(node, "perm");

        if (pagePath != null) {
            boolean accessAllowed = SecurityUtil.hasPermission(requiredPermission)
                    && SecurityUtil.canAccessPage(pagePath);

            if (!accessAllowed) {
                node.detach();
                return;
            }

            // Wire navigation on click — delegate to DashboardComposer.navigateTo()
            node.addEventListener("onClick", event -> navigateToPage(pagePath, node));
            return;
        }

        // Structural container — recurse into children
        List<Component> children = new ArrayList<>(node.getChildren());
        for (Component child : children) {
            configureMenuNode(child);
        }
    }

    /**
     * Reads a component attribute by name, falling back to the {@code data-*} variant.
     * Reads only from this component (scope 0 — no parent inheritance).
     *
     * @param node          the component to read from
     * @param attributeName attribute name (without {@code data-} prefix)
     * @return attribute value as String, or {@code null} if absent
     */
    private String getOwnAttributeValue(Component node, String attributeName) {
        Object value = node.getAttribute(attributeName, 0);
        if (value != null) return value.toString();

        Object dataValue = node.getAttribute("data-" + attributeName, 0);
        return dataValue != null ? dataValue.toString() : null;
    }

    // ── Accordion wiring ─────────────────────────────────────────────

    /**
     * Wires an onClick accordion toggle on {@code header} that shows/hides {@code menu}
     * and rotates {@code arrow} between ▶ (closed) and ▼ (open).
     *
     * <p>If the sidebar is collapsed when the header is clicked, it expands first.
     * Re-applies access rules before opening to handle permission changes mid-session.
     *
     * @param header  the clickable header div
     * @param menu    the submenu div to show/hide
     * @param arrow   the arrow Label whose value tracks open/closed state
     */
    private void wireAccordion(Div header, Div menu, Label arrow) {
        if (header == null || menu == null || arrow == null) return;

        header.addEventListener("onClick", event -> {
            if (isCollapsed) expandSidebar();

            boolean menuCurrentlyOpen = menu.isVisible();
            if (!menuCurrentlyOpen) {
                // Re-check access before opening (permissions may have changed)
                applyMenuAccessRules(menu);
                if (!hasVisibleMenuItems(menu)) return; // nothing accessible — stay closed
            }

            menu.setVisible(!menuCurrentlyOpen);
            arrow.setValue(menuCurrentlyOpen ? "▶" : "▼");
        });
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Navigates to the given page: saves to session, updates active CSS state,
     * then delegates to {@link DashboardComposer#navigateTo(String)}.
     *
     * @param pagePath    absolute ZUL path to navigate to
     * @param clickedItem the menu item component that was clicked (for active highlight)
     */
    private void navigateToPage(String pagePath, Component clickedItem) {
        if (!SecurityUtil.canAccessPage(pagePath)) return;

        // Persist so DashboardComposer can restore after F5 refresh
        Sessions.getCurrent().setAttribute(DashboardComposer.LAST_VISITED_PAGE_KEY, pagePath);

        // Update active highlight: clear all, set this item
        clearActiveMenuState(getSelf());
        String currentSclass = getSclass(clickedItem);
        if (!currentSclass.contains("active")) {
            setSclass(clickedItem, (currentSclass + " active").trim());
        }

        // Delegate to DashboardComposer static helper — finds #mainContent Include
        DashboardComposer.navigateTo(pagePath);
    }

    // ── Section visibility ───────────────────────────────────────────

    /**
     * Hides section-label divs and accordion headers whose child menu items
     * were all removed by {@link #applyMenuAccessRules}.
     *
     * @param root the sidebar root component to scan
     */
    private void updateSectionVisibility(Component root) {
        List<Component> children = new ArrayList<>(root.getChildren());

        for (int i = 0; i < children.size(); i++) {
            Component child  = children.get(i);
            String    sclass = getSclass(child);

            // Section label (e.g. "CLEARING", "REPORTS") — hide if nothing below it
            if (sclass.contains("sidebar-section") || sclass.contains("nav-section")) {
                child.setVisible(hasVisibleMenuItems(child));
            }

            // Accordion header — hide if its submenu has no accessible items
            if (sclass.contains("cts-menu-header")) {
                Component submenu   = (i + 1 < children.size()) ? children.get(i + 1) : null;
                boolean   hasAccess = submenu != null && hasVisibleMenuItems(submenu);

                child.setVisible(hasAccess);

                // Keep submenu closed initially regardless of access
                if (submenu != null && getSclass(submenu).contains("cts-submenu")) {
                    submenu.setVisible(false);
                }
            }
        }
    }

    /**
     * Returns {@code true} if the component tree rooted at {@code component}
     * contains at least one visible {@code cts-menu-item} node.
     *
     * @param component root to search
     * @return true if any visible menu item exists in the subtree
     */
    private boolean hasVisibleMenuItems(Component component) {
        for (Component child : component.getChildren()) {
            if (getSclass(child).contains("cts-menu-item") && child.isVisible()) return true;
            if (hasVisibleMenuItems(child)) return true;
        }
        return false;
    }

    /**
     * Removes the {@code active} CSS class from all {@code cts-menu-item}
     * components in the sidebar tree, then recurses into children.
     *
     * @param component root to clear from
     */
    private void clearActiveMenuState(Component component) {
        String sclass = getSclass(component);
        if (sclass.contains("cts-menu-item")) {
            setSclass(component, sclass.replace("active", "").trim());
        }
        for (Component child : component.getChildren()) {
            clearActiveMenuState(child);
        }
    }

    // ── Sidebar collapse / expand ─────────────────────────────────────

    /**
     * Toggles the sidebar between expanded (240 px) and collapsed (70 px) states.
     * Closes all open submenus before collapsing to keep the UI clean.
     */
    @Listen("onClick = #sidebarToggle")
    public void toggleSidebar() {
        if (isCollapsed) {
            expandSidebar();
            return;
        }
        closeAllSubmenus(getSelf());
        setSclass(getSelf(), (getSclass(getSelf()) + " collapsed").trim());
        updateSidebarWidth("70px");
        isCollapsed = true;
    }

    /**
     * Expands the sidebar to full width and clears the collapsed CSS class.
     */
    private void expandSidebar() {
        setSclass(getSelf(), getSclass(getSelf()).replace(" collapsed", "").trim());
        updateSidebarWidth("240px");
        isCollapsed = false;
    }

    /**
     * Recursively closes all submenu divs and resets their arrow labels to ▶.
     *
     * @param root root component to start from
     */
    private void closeAllSubmenus(Component root) {
        for (Component child : root.getChildren()) {
            String sclass = getSclass(child);
            if (sclass.contains("cts-submenu"))  child.setVisible(false);
            if (child instanceof Label arrow && sclass.contains("cts-arrow")) arrow.setValue("▶");
            closeAllSubmenus(child);
        }
    }

    /**
     * Pushes a CSS width change to the {@code .app-sidebar} DOM element via JS.
     * Required because the sidebar width is controlled by CSS/JS, not ZK layout.
     *
     * @param width CSS width value (e.g. {@code "240px"} or {@code "70px"})
     */
    private void updateSidebarWidth(String width) {
        Clients.evalJavaScript(
            "var sidebar = document.querySelector('.app-sidebar');" +
            "if (sidebar) { sidebar.style.width='" + width + "'; }");
    }

    // ── CSS helpers ───────────────────────────────────────────────────

    /**
     * Returns the {@code sclass} of a component safely (never null).
     *
     * @param component ZK component to read sclass from
     * @return sclass string or empty string if component is not HtmlBasedComponent
     */
    private String getSclass(Component component) {
        return component instanceof HtmlBasedComponent html && html.getSclass() != null
                ? html.getSclass() : "";
    }

    /**
     * Sets the {@code sclass} on a component if it is an HtmlBasedComponent.
     *
     * @param component ZK component to update
     * @param sclass    CSS class string to apply
     */
    private void setSclass(Component component, String sclass) {
        if (component instanceof HtmlBasedComponent html) html.setSclass(sclass);
    }
}