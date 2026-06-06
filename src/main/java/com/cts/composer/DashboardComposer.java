package com.cts.composer;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Div;

public class DashboardComposer extends SelectorComposer<Component> {

   private static final long serialVersionUID = 1L;

   @Wire
   private Div contentArea;

   private static DashboardComposer instance;

   // Session key to store last loaded sub-page 
   public static final String SESS_LAST_SUB_PAGE = "lastSubPage";

   private static final String DEFAULT_PAGE = "/zul/outward/outwardDashboard.zul";

   @Override
   public void doAfterCompose(Component comp) throws Exception {
       super.doAfterCompose(comp);

       // Session guard 
       Session session = Sessions.getCurrent();
       Object loggedUser = session.getAttribute(LoginComposer.SESS_LOGGED_USER);
       if (loggedUser == null || loggedUser.toString().trim().isEmpty()) {
           Executions.sendRedirect("/zul/index.zul");
           return;
       }

       session.setAttribute(LoginComposer.SESS_CURRENT_PAGE, "zul/dashboard.zul");

       instance = this;

       // APPROACH 2: Read last sub-page from session 
       // If user refreshes, session still has the last sub-page they visited
       // So we load that instead of the default
       String lastSubPage = (String) session.getAttribute(SESS_LAST_SUB_PAGE);
       if (lastSubPage != null && !lastSubPage.trim().isEmpty()) {
           loadPage(lastSubPage);   // first time login, load default
       }
   }

   public static DashboardComposer getInstance() {
       return instance;
   }

   public void loadPage(String pagePath) {
       contentArea.getChildren().clear();
       Executions.createComponents(pagePath, contentArea, null);
   }
}