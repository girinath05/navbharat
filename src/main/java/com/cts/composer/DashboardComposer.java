package com.cts.composer;

import com.cts.util.SecurityUtil;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Include;

public class DashboardComposer extends SelectorComposer<Component> {

    /**
     * Stores the last page the user visited so we can restore it after refresh.
     */
    public static final String LAST_VISITED_PAGE_KEY = "CTS_LAST_PAGE";

    @Wire("#mainContent")
    private Include contentInclude;

    @Override
    public void doAfterCompose(Component component) throws Exception {
        super.doAfterCompose(component);
        contentInclude.setSrc(getInitialPage());
    }

    /**
     * Prefer the last visited page if it is still allowed.
     * Otherwise load the default dashboard for the user's role.
     */
    private String getInitialPage() {
        String lastVisitedPage = getLastVisitedPage();

        if (isAccessible(lastVisitedPage)) {
            return lastVisitedPage;
        }

        return getDefaultDashboardPage();
    }

    private String getLastVisitedPage() {
        Object value = Sessions.getCurrent().getAttribute(LAST_VISITED_PAGE_KEY);
        return value instanceof String ? (String) value : null;
    }

    private boolean isAccessible(String pagePath) {
        return pagePath != null
                && !pagePath.isBlank()
                && SecurityUtil.canAccessPage(pagePath);
    }

    private String getDefaultDashboardPage() {
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
     * Programmatic navigation helper for composers that are not wired into
     * SidebarComposer's click handling (e.g. "Back" / "Next" buttons inside
     * outward sub-pages). Mirrors the lookup SidebarComposer uses to find the
     * shared #mainContent Include and swap its src. Replaces the legacy
     * {@code DashboardComposer.getInstance().loadPage(...)} API from before
     * the Include-based shell was introduced.
     */
    public static void navigateTo(String pagePath) {
        if (!SecurityUtil.canAccessPage(pagePath)) {
            return;
        }

        Sessions.getCurrent().setAttribute(LAST_VISITED_PAGE_KEY, pagePath);

        for (Page page : Executions.getCurrent().getDesktop().getPages()) {
            Component mainContent = page.getFellowIfAny("mainContent");
            if (mainContent instanceof Include include) {
                include.setSrc(pagePath);
                return;
            }
        }
    }
}