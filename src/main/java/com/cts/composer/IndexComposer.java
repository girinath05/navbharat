package com.cts.composer;

import com.cts.uam.model.User;
import com.cts.util.SecurityUtil;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.select.SelectorComposer;

public class IndexComposer extends SelectorComposer<Component> {

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        User user = SecurityUtil.getCurrentUser();
        if (user != null) {
            Executions.sendRedirect(SecurityUtil.getHomePage());
        } else {
            Executions.sendRedirect("/zul/login.zul");
        }
    }
}