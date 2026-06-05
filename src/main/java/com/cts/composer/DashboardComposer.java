/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : DashboardComposer.java
 *  Package     : com.cts.composer
 *  Author      : Girinath M
 *  Created     : June 2026
 *  Description : ZK SelectorComposer wired to the main shell layout.
 *                Loads the outward dashboard ZUL fragment into the
 *                contentArea div on compose. Exposes a static
 *                getInstance() for cross-composer page navigation.
 * ============================================================
 */

package com.cts.composer;

import java.util.HashMap;
import java.util.Map;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Div;

public class DashboardComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;

	@Wire
	private Div contentArea;

	private static DashboardComposer instance;

	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);

		instance = this;

		loadPage("/zul/outward/outwardDashboard.zul");
	}

	public static DashboardComposer getInstance() {
		return instance;
	}

	public void loadPage(String pagePath) {

		contentArea.getChildren().clear();

		Executions.createComponents(pagePath, contentArea, null);
	}
}