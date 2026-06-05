/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : SecurityUtil.java
 *  Package     : com.cts.security
 *  Author      : Girinath M.
 *  Created     : June 2026
 *  Description : Stateless security helper. Resolves a role name
 *                to its Set<Permission>, checks whether the
 *                current session user holds a given permission,
 *                and provides hasPermission() guards called by
 *                composers before rendering sensitive UI actions.
 * ============================================================
 */

package com.cts.security;

import java.util.Collections;
import java.util.Set;

import org.zkoss.zk.ui.Sessions;

public final class SecurityUtil {

	private SecurityUtil() {
	}

	@SuppressWarnings("unchecked")
	public static Set<Permission> getPermissions() {

		Object obj = Sessions.getCurrent().getAttribute("permissions");

		if (obj == null) {

			return Collections.emptySet();
		}

		return (Set<Permission>) obj;
	}

	public static boolean hasPermission(String permissionName) {

		try {

			Permission permission = Permission.valueOf(permissionName);

			return getPermissions().contains(permission);

		} catch (Exception e) {

			return false;
		}
	}
}