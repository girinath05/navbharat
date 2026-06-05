/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : HeaderComposer.java
 *  Package     : com.cts.composer
 *  Author      : Girinath M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for the top header bar.
 *                Drives a ZK Timer to update the live clock
 *                (lblHdrTime) and date (lblHdrDate) every second.
 *                Handles logout by invalidating the ZK session
 *                and redirecting to the login page.
 * ============================================================
 */

package com.cts.composer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Label;
import org.zkoss.zul.Timer;

public class HeaderComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;

	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm:ss a");

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	@Wire
	private Label lblHdrTime;

	@Wire
	private Label lblHdrDate;

	@Wire
	private Label lblHdrAvatar;

	@Wire
	private Label lblHdrRole;

	@Wire
	private Timer hdrTimer;

	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);

		updateClock();
	}

	private void updateClock() {

		if (lblHdrTime != null) {
			lblHdrTime.setValue(LocalTime.now().format(TIME_FMT));
		}

		if (lblHdrDate != null) {
			lblHdrDate.setValue(LocalDate.now().format(DATE_FMT).toUpperCase());
		}
	}

	@Listen("onTimer = #hdrTimer")
	public void onTimer() {
		updateClock();
	}

	private void loadUserDetails() {

		String userName = (String) Sessions.getCurrent().getAttribute("userName");

		String role = (String) Sessions.getCurrent().getAttribute("userRole");

		if (lblHdrAvatar != null) {

			if (userName != null && !userName.trim().isEmpty()) {
				lblHdrAvatar.setValue(String.valueOf(Character.toUpperCase(userName.charAt(0))));
			} else {
				lblHdrAvatar.setValue("U");
			}
		}

		if (lblHdrRole != null) {
			lblHdrRole.setValue(role != null ? role.toUpperCase() : "USER");
		}
	}

}