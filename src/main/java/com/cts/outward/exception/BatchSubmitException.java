/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchSubmitException.java
 *  Package     : com.cts.outward.exception
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Checked exception thrown by the service layer
 *                when batch submission fails validation or
 *                persistence. Carries a user-facing message
 *                surfaced by the composer as a ZK alert dialog.
 * ============================================================
 */

package com.cts.outward.exception;

public class BatchSubmitException extends Exception {
	public BatchSubmitException(String message) {
		super(message);
	}
}