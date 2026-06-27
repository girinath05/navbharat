/*
 * ============================================================
 *  Project  : Navbharat CTS Outward
 *  File     : DashboardComposer.java
 *  Package  : com.cts.composer
 *  Purpose  : Root composer for dashboard.zul. Wires the content
 *             area Include, validates session via SecurityUtil,
 *             and loads the initial sub-page (last visited or
 *             role-based default). Provides navigateTo() as a
 *             static helper for all sub-page composers.
 *  Author   : [Name]
 *  Date     : June 2026
 * ============================================================
 */

package com.cts.composer;

import java.util.logging.Logger;

import com.cts.util.SecurityUtil;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Include;

/**
 * File : DashboardComposer.java
 * Package : com.cts.composer
 * Purpose : Manages the main dashboard shell (dashboard.zul).
 *
 * <p>
 * On compose:
 * <ol>
 * <li>Validates session via {@link SecurityUtil} — redirects to login if not
 * authenticated.</li>
 * <li>Restores the last sub-page from session (survives F5 refresh).</li>
 * <li>Falls back to a role-based default page when the last page is stale or
 * inaccessible.</li>
 * </ol>
 *
 * <p>
 * Navigation architecture: the shell contains a single ZK {@code <include>}
 * component
 * (id="mainContent"). Sub-pages are swapped by changing its {@code src}
 * attribute.
 * All composers call {@link #navigateTo(String)} — no singleton instance
 * needed.
 */
public class DashboardComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(DashboardComposer.class.getName());

    // Key used to save and restore the last visited page across F5 refresh
    public static final String LAST_VISITED_PAGE_KEY = "CTS_LAST_PAGE";

    // The <include id="mainContent"> in app.zul — all sub-pages load here
    @Wire("#mainContent")
    private Include contentInclude;

    @Override
    public void doAfterCompose(Component component) throws Exception {
        super.doAfterCompose(component);

        // Not logged in → back to login page
        if (SecurityUtil.getCurrentUser() == null) {
            Executions.sendRedirect("/zul/login.zul");
            return;
        }

        contentInclude.setSrc(resolveInitialPage());
    }

    // Returns the last visited page if still accessible, otherwise picks a default
    private String resolveInitialPage() {
        Object lastPageAttribute = Sessions.getCurrent().getAttribute(LAST_VISITED_PAGE_KEY);
        String lastVisitedPage = (lastPageAttribute instanceof String) ? (String) lastPageAttribute : null;

        if (lastVisitedPage != null
                && !lastVisitedPage.isBlank()
                && SecurityUtil.canAccessPage(lastVisitedPage)) {
            return lastVisitedPage;
        }

        return resolveDefaultPage();
    }

    // Picks the first page the user's role is allowed to see
    private String resolveDefaultPage() {
        if (SecurityUtil.canAccessPage("/zul/dashboard.zul")) {
            return "/zul/dashboard.zul";
        }
        if (SecurityUtil.canAccessPage("/zul/outward/outwardDashboard.zul")) {
            return "/zul/outward/outwardDashboard.zul";
        }
        if (SecurityUtil.canAccessPage("/zul/inward/dashboard/makerDashboard.zul")) {
            return "/zul/inward/dashboard/makerDashboard.zul";
        }
        if (SecurityUtil.canAccessPage("/zul/inward/dashboard/verifierIDashboard.zul")) {
            return "/zul/inward/dashboard/verifierIDashboard.zul";
        }
        if (SecurityUtil.canAccessPage("/zul/inward/dashboard/verifierIIDashboard.zul")) {
            return "/zul/inward/dashboard/verifierIIDashboard.zul";
        }
        return "/zul/no-dashboard-access.zul";
    }

    // Called by any composer to navigate to a different page inside #mainContent
    public static void navigateTo(String pagePath) {
        if (pagePath == null || pagePath.isBlank()) {
            LOGGER.warning("DashboardComposer.navigateTo: null/blank pagePath — ignoring");
            return;
        }

        if (!SecurityUtil.canAccessPage(pagePath)) {
            LOGGER.warning("DashboardComposer.navigateTo: access denied for [" + pagePath + "]");
            return;
        }

        // Save so F5 refresh reopens the same page
        Sessions.getCurrent().setAttribute(LAST_VISITED_PAGE_KEY, pagePath);

        // Find #mainContent across all desktop pages and swap the src
        for (Page page : Executions.getCurrent().getDesktop().getPages()) {
            Component mainContent = page.getFellowIfAny("mainContent");
            if (mainContent instanceof Include include) {
                include.setSrc(pagePath);
                return;
            }
        }

        LOGGER.warning("DashboardComposer.navigateTo: #mainContent not found for [" + pagePath + "]");
    }
}