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
 * File    : DashboardComposer.java
 * Package : com.cts.composer
 * Purpose : Manages the main dashboard shell (dashboard.zul).
 *
 * <p>On compose:
 * <ol>
 *   <li>Validates session via {@link SecurityUtil} — redirects to login if not authenticated.</li>
 *   <li>Restores the last sub-page from session (survives F5 refresh).</li>
 *   <li>Falls back to a role-based default page when the last page is stale or inaccessible.</li>
 * </ol>
 *
 * <p>Navigation architecture: the shell contains a single ZK {@code <include>} component
 * (id="mainContent"). Sub-pages are swapped by changing its {@code src} attribute.
 * All composers call {@link #navigateTo(String)} — no singleton instance needed.
 */
public class DashboardComposer extends SelectorComposer<Component> {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(DashboardComposer.class.getName());

    /**
     * Session attribute key: stores the last sub-page path the user visited.
     * Written by {@link SidebarComposer} on every navigation; read here on refresh.
     */
    public static final String LAST_VISITED_PAGE_KEY = "CTS_LAST_PAGE";

    /** Wires the shared content Include component (id="mainContent" in dashboard.zul). */
    @Wire("#mainContent")
    private Include contentInclude;

    /**
     * Called by ZK after dashboard.zul component tree is composed.
     * Validates session, then loads the appropriate sub-page into {@code #mainContent}.
     *
     * @param component root component of dashboard.zul
     * @throws Exception if session redirect or page load fails unrecoverably
     */
    @Override
    public void doAfterCompose(Component component) throws Exception {
        super.doAfterCompose(component);

        // ── Session guard — unauthenticated users go back to login ──────────
        if (SecurityUtil.getCurrentUser() == null) {
            Executions.sendRedirect("/zul/login.zul");
            return;
        }

        contentInclude.setSrc(resolveInitialPage());
    }

    /**
     * Resolves which sub-page to load on initial render.
     *
     * <p>Priority order:
     * <ol>
     *   <li>Last visited page from session — if it still exists and user can access it.</li>
     *   <li>Role-based default page via {@link #resolveDefaultPage()}.</li>
     * </ol>
     *
     * @return absolute ZUL path to load into the content Include
     */
    private String resolveInitialPage() {
        Object lastPageAttribute = Sessions.getCurrent().getAttribute(LAST_VISITED_PAGE_KEY);
        String lastVisitedPage   = (lastPageAttribute instanceof String) ? (String) lastPageAttribute : null;

        if (lastVisitedPage != null
                && !lastVisitedPage.isBlank()
                && SecurityUtil.canAccessPage(lastVisitedPage)) {
            return lastVisitedPage;
        }

        return resolveDefaultPage();
    }

    /**
     * Returns the role-appropriate default landing page.
     * Checked in priority order: general dashboard → outward → inward → no-access page.
     *
     * @return absolute ZUL path the current user is allowed to see as their home page
     */
    private String resolveDefaultPage() {
        if (SecurityUtil.canAccessPage("/zul/dashboard.zul")) {
            return "/zul/dashboard.zul";
        }
        if (SecurityUtil.canAccessPage("/zul/outward/outwardDashboard.zul")) {
            return "/zul/outward/outwardDashboard.zul";
        }
        if (SecurityUtil.canAccessPage("/zul/inward/inwardDashboard.zul")) {
            return "/zul/inward/inwardDashboard.zul";
        }
        return "/zul/no-dashboard-access.zul";
    }

    /**
     * Static navigation helper used by all sub-page composers (e.g. "View Batches",
     * "Back" buttons, row-click handlers).
     *
     * <p>Replaces the legacy {@code DashboardComposer.getInstance().loadPage(...)} pattern.
     * Uses {@link SecurityUtil#canAccessPage(String)} so no unauthorised navigation
     * is possible even via direct method call.
     *
     * <p>Saves the destination to session ({@link #LAST_VISITED_PAGE_KEY}) so that
     * F5 refresh restores the same page.
     *
     * <p>Finds {@code #mainContent} by walking all pages in the current desktop —
     * works regardless of where the caller lives in the component tree.
     *
     * @param pagePath absolute ZUL path to load (e.g. {@code /zul/outward/my-batches.zul})
     */
    public static void navigateTo(String pagePath) {
        if (pagePath == null || pagePath.isBlank()) {
            LOGGER.warning("DashboardComposer.navigateTo: null/blank pagePath — ignoring");
            return;
        }

        if (!SecurityUtil.canAccessPage(pagePath)) {
            LOGGER.warning("DashboardComposer.navigateTo: access denied for [" + pagePath + "]");
            return;
        }

        // Persist destination so refresh restores it
        Sessions.getCurrent().setAttribute(LAST_VISITED_PAGE_KEY, pagePath);

        // Walk all desktop pages to find the shared #mainContent Include
        for (Page page : Executions.getCurrent().getDesktop().getPages()) {
            Component mainContent = page.getFellowIfAny("mainContent");
            if (mainContent instanceof Include include) {
                include.setSrc(pagePath);
                return;
            }
        }

        LOGGER.warning("DashboardComposer.navigateTo: #mainContent Include not found for [" + pagePath + "]");
    }
}