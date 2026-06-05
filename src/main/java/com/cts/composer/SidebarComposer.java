/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : SidebarComposer.java
 *  Package     : com.cts.composer
 *  Author      : Girinath M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for the collapsible sidebar
 *                navigation. Manages expand/collapse toggle of
 *                outward, inward, reports, and user sub-menus
 *                via arrow label rotation. Delegates content
 *                loading to DashboardComposer.loadPage().
 * ============================================================
 */

package com.cts.composer;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;

import com.cts.composer.DashboardComposer;

public class SidebarComposer extends SelectorComposer<Component> {

	// All menus are now Div (plain <div> in ZUL)
	@Wire
	private Div outwardMenu;
	@Wire
	private Div inwardMenu;
	@Wire
	private Div outwardReportsMenu;
	@Wire
	private Div inwardReportsMenu;
	@Wire
	private Div userMenu;

	// Arrow labels
	@Wire
	private Label outwardArrow;
	@Wire
	private Label inwardArrow;
	@Wire
	private Label outwardReportsArrow;
	@Wire
	private Label inwardReportsArrow;
	@Wire
	private Label userArrow;

	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);
		// All arrows start collapsed
		outwardArrow.setValue("▶");
		inwardArrow.setValue("▶");
		outwardReportsArrow.setValue("▶");
		inwardReportsArrow.setValue("▶");
		userArrow.setValue("▶");
	}

	// ── OUTWARD CLEARING ──
	@Listen("onClick=#outwardHeader")
	public void toggleOutwardMenu() {
		boolean open = !outwardMenu.isVisible();
		outwardMenu.setVisible(open);
		outwardArrow.setValue(open ? "▼" : "▶");
	}

	// ── INWARD CLEARING ──
	@Listen("onClick=#inwardHeader")
	public void toggleInwardMenu() {
		boolean open = !inwardMenu.isVisible();
		inwardMenu.setVisible(open);
		inwardArrow.setValue(open ? "▼" : "▶");
	}

	// ── OUTWARD REPORTS ──
	@Listen("onClick=#outwardReportsHeader")
	public void toggleOutwardReportsMenu() {
		boolean open = !outwardReportsMenu.isVisible();
		outwardReportsMenu.setVisible(open);
		outwardReportsArrow.setValue(open ? "▼" : "▶");
	}

	// ── INWARD REPORTS ──
	@Listen("onClick=#inwardReportsHeader")
	public void toggleInwardReportsMenu() {
		boolean open = !inwardReportsMenu.isVisible();
		inwardReportsMenu.setVisible(open);
		inwardReportsArrow.setValue(open ? "▼" : "▶");
	}

	// ── USER MANAGEMENT ──
	@Listen("onClick=#userHeader")
	public void toggleUserMenu() {
		boolean open = !userMenu.isVisible();
		userMenu.setVisible(open);
		userArrow.setValue(open ? "▼" : "▶");
	}

	// ── PAGE NAVIGATION ──
	@Listen("onClick=.cts-menu-item")
	public void navigate(Event event) {
		Component target = event.getTarget();
		// Walk up the tree to find the component with pagePath
		while (target != null && target.getAttribute("pagePath") == null) {
			target = target.getParent();
		}
		if (target == null) {
			return;
		}
		String pagePath = target.getAttribute("pagePath").toString();
		// Executions.sendRedirect(pagePath);
		DashboardComposer.getInstance().loadPage(pagePath);
	}
}