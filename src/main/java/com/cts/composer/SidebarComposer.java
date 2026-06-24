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

public class SidebarComposer extends SelectorComposer<Component> {

    @Wire("#sidebarToggle")
    private Div sidebarToggle;

    @Wire("#outwardHeader")
    private Div outwardHeader;
    @Wire("#outwardMenu")
    private Div outwardMenu;
    @Wire("#outwardArrow")
    private Label outwardArrow;

    @Wire("#inwardHeader")
    private Div inwardHeader;
    @Wire("#inwardMenu")
    private Div inwardMenu;
    @Wire("#inwardArrow")
    private Label inwardArrow;

    @Wire("#inwardDashboardHeader")
    private Div inwardDashboardHeader;
    @Wire("#inwardDashboardMenu")
    private Div inwardDashboardMenu;
    @Wire("#inwardDashboardArrow")
    private Label inwardDashboardArrow;

    @Wire("#outwardReportsHeader")
    private Div outwardReportsHeader;
    @Wire("#outwardReportsMenu")
    private Div outwardReportsMenu;
    @Wire("#outwardReportsArrow")
    private Label outwardReportsArrow;

    @Wire("#inwardReportsHeader")
    private Div inwardReportsHeader;
    @Wire("#inwardReportsMenu")
    private Div inwardReportsMenu;
    @Wire("#inwardReportsArrow")
    private Label inwardReportsArrow;

    @Wire("#userHeader")
    private Div userHeader;
    @Wire("#userMenu")
    private Div userMenu;
    @Wire("#userArrow")
    private Label userArrow;

    private User currentUser;
    private boolean isCollapsed;

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

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

        wireAccordion(outwardHeader, outwardMenu, outwardArrow);
        wireAccordion(inwardDashboardHeader, inwardDashboardMenu, inwardDashboardArrow);
        wireAccordion(inwardHeader, inwardMenu, inwardArrow);
        wireAccordion(outwardReportsHeader, outwardReportsMenu, outwardReportsArrow);
        wireAccordion(inwardReportsHeader, inwardReportsMenu, inwardReportsArrow);
        wireAccordion(userHeader, userMenu, userArrow);
    }

    private void applyMenuAccessRules(Component root) {
        List<Component> children = new ArrayList<Component>(root.getChildren());
        for (Component child : children) {
            configureMenuNode(child);
        }
    }

    /**
     * Reads page/perm only from this node.
     * Child layout nodes are left untouched so submenu structure remains intact.
     */
    private void configureMenuNode(Component node) {
        String pagePath = getOwnAttributeValue(node, "page");
        String requiredPermission = getOwnAttributeValue(node, "perm");

        if (pagePath != null) {
            boolean allowed = SecurityUtil.hasPermission(requiredPermission)
                    && SecurityUtil.canAccessPage(pagePath);

            if (!allowed) {
                node.detach();
                return;
            }

            node.addEventListener("onClick", event -> navigateToPage(pagePath, node));
            return;
        }

        List<Component> children = new ArrayList<Component>(node.getChildren());
        for (Component child : children) {
            configureMenuNode(child);
        }
    }

    /**
     * Reads an attribute from the component itself only.
     * Supports both plain attributes and data-* attributes.
     */
    private String getOwnAttributeValue(Component node, String attributeName) {
        Object value = node.getAttribute(attributeName, 0);
        if (value != null) {
            return value.toString();
        }

        Object dataValue = node.getAttribute("data-" + attributeName, 0);
        return dataValue != null ? dataValue.toString() : null;
    }

    private void wireAccordion(Div header, Div menu, Label arrow) {
        if (header == null || menu == null || arrow == null) {
            return;
        }

        header.addEventListener("onClick", event -> {
            if (isCollapsed) {
                expandSidebar();
            }

            boolean menuIsOpen = menu.isVisible();
            if (!menuIsOpen) {
                // Re-check access before showing the submenu
                applyMenuAccessRules(menu);

                // If everything inside was removed, keep the submenu closed
                if (!hasVisibleMenuItems(menu)) {
                    return;
                }
            }

            menu.setVisible(!menuIsOpen);
            arrow.setValue(menuIsOpen ? "▶" : "▼");
        });
    }

    private void navigateToPage(String pagePath, Component clickedItem) {
        if (!SecurityUtil.canAccessPage(pagePath)) {
            return;
        }

        // Remember the page so DashboardComposer can restore it after refresh
        Sessions.getCurrent().setAttribute(DashboardComposer.LAST_VISITED_PAGE_KEY, pagePath);

        clearActiveMenuState(getSelf());

        String currentClass = getSclass(clickedItem);
        if (!currentClass.contains("active")) {
            setSclass(clickedItem, (currentClass + " active").trim());
        }

        // Fallback: update the shared content include from any loaded page
        for (Page page : Executions.getCurrent().getDesktop().getPages()) {
            Component mainContent = page.getFellowIfAny("mainContent");
            if (mainContent instanceof Include include) {
                include.setSrc(pagePath);
                return;
            }
        }
    }

    private void updateSectionVisibility(Component root) {
        List<Component> children = new ArrayList<>(root.getChildren());

        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            String sclass = getSclass(child);

            // Hide empty section headers so users only see usable sections
            if (sclass.contains("sidebar-section") || sclass.contains("nav-section")) {
                child.setVisible(hasVisibleMenuItems(child));
            }

            // Hide the accordion header when the user cannot access anything inside it
            if (sclass.contains("cts-menu-header")) {
                Component submenu = (i + 1 < children.size()) ? children.get(i + 1) : null;
                boolean hasAccess = submenu != null && hasVisibleMenuItems(submenu);

                child.setVisible(hasAccess);

                if (submenu != null && getSclass(submenu).contains("cts-submenu")) {
                    submenu.setVisible(false);
                }
            }
        }
    }

    private boolean hasVisibleMenuItems(Component component) {
        for (Component child : component.getChildren()) {
            String sclass = getSclass(child);

            if (sclass.contains("cts-menu-item") && child.isVisible()) {
                return true;
            }

            if (hasVisibleMenuItems(child)) {
                return true;
            }
        }
        return false;
    }

    private void clearActiveMenuState(Component component) {
        String sclass = getSclass(component);
        if (sclass.contains("cts-menu-item")) {
            setSclass(component, sclass.replace("active", "").trim());
        }

        for (Component child : component.getChildren()) {
            clearActiveMenuState(child);
        }
    }

    @Listen("onClick = #sidebarToggle")
    public void toggleSidebar() {
        Component root = getSelf();

        if (isCollapsed) {
            expandSidebar();
            return;
        }

        // Close all submenus before collapsing so the sidebar stays visually clean
        closeAllSubmenus(root);
        setSclass(root, (getSclass(root) + " collapsed").trim());
        updateSidebarWidth("70px");
        isCollapsed = true;
    }

    private void expandSidebar() {
        Component root = getSelf();
        setSclass(root, getSclass(root).replace(" collapsed", "").trim());
        updateSidebarWidth("240px");
        isCollapsed = false;
    }

    private void closeAllSubmenus(Component root) {
        for (Component child : root.getChildren()) {
            String sclass = getSclass(child);

            if (sclass.contains("cts-submenu")) {
                child.setVisible(false);
            }

            if (child instanceof Label arrow && sclass.contains("cts-arrow")) {
                arrow.setValue("▶");
            }

            closeAllSubmenus(child);
        }
    }

    private void updateSidebarWidth(String width) {
        Clients.evalJavaScript(
                "var sidebar = document.querySelector('.app-sidebar');" +
                        "if (sidebar) { sidebar.style.width='" + width + "'; }");
    }

    private String getSclass(Component component) {
        return component instanceof HtmlBasedComponent html && html.getSclass() != null
                ? html.getSclass()
                : "";
    }

    private void setSclass(Component component, String sclass) {
        if (component instanceof HtmlBasedComponent html) {
            html.setSclass(sclass);
        }
    }
}