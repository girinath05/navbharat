/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CxfFileResult.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Value object returned by the CXF generation
 *                service. Carries the generated file path,
 *                instrument count, total amount, and generation
 *                timestamp used by the composer to display the
 *                post-generation summary dialog.
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CxfFileResult ───────────── Immutable result of a single CXF file generation
 * attempt. Uses Java 21 record for conciseness.
 *
 * Fields: fileName — e.g. CXF_01-CTS_20240515_0001.xml filePath — absolute path
 * on disk where file was written recordCount — number of cheques included in
 * this file totalAmount — sum of amounts in this file generatedAt — timestamp
 * of generation success — true if file written without error errorMsg — set
 * when success = false
 */
public record CxfFileResult(String fileName, String filePath, int recordCount, BigDecimal totalAmount,
		LocalDateTime generatedAt, boolean success, String errorMsg) {
	/** Convenience factory for a successful result */
	public static CxfFileResult ok(String fileName, String filePath, int count, BigDecimal total) {
		return new CxfFileResult(fileName, filePath, count, total, LocalDateTime.now(), true, null);
	}

	/** Convenience factory for a failed result */
	public static CxfFileResult fail(String fileName, String errorMsg) {
		return new CxfFileResult(fileName, null, 0, BigDecimal.ZERO, LocalDateTime.now(), false, errorMsg);
	}

	public boolean isSuccess() {
		return success;
	}

	public String getFileName() {
		return fileName;
	}

	public String getFilePath() {
		return filePath;
	}

	public int getRecordCount() {
		return recordCount;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public LocalDateTime getGeneratedAt() {
		return generatedAt;
	}

	public String getErrorMsg() {
		return errorMsg;
	}
}
