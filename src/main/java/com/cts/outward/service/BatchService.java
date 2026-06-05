/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Service interface for batch business logic.
 *                Sits between composer and DAO; handles batch
 *                ID generation, count-mismatch resolution,
 *                status transitions, and discard operations.
 *                Throws BatchSubmitException on validation
 *                failures.
 * ============================================================
 */

// BatchService.java

package com.cts.outward.service;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.exception.BatchSubmitException;

public interface BatchService {
	BatchEntity createBatch(String branchCode, int expectedCheques, BigDecimal expectedAmount, String createdBy);

	void discardBatch(String batchId);

	void submitBatch(String batchId) throws BatchSubmitException;

	// BatchService.java — add this method
	void submitBatchForVerification(String batchId) throws BatchSubmitException;

	List<BatchEntity> getAllBatches();

	BatchEntity getBatchById(String batchId);

	int nextBatchSeq();
}