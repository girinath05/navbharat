/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchDAO.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : DAO interface for batch-level persistence.
 *                Declares CRUD and status-update contracts for
 *                BatchEntity. Implemented by BatchDAOImpl using
 *                Hibernate sessions from HibernateUtil.
 * ============================================================
 */
package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.cts.outward.entity.BatchEntity;

public interface BatchDAO {

	// ══════════════════════════════════════════════════════════
	// SEQUENCE
	// ══════════════════════════════════════════════════════════

	/**
	 * Returns the next available batch sequence number.
	 * Queries the highest numeric suffix from batch_id values matching
	 * 'BATCH[0-9]+' and adds 1. Returns 100 if table is empty or on error.
	 *
	 * Called by: ChequeScanComposer when creating a new batch
	 */
	int loadMaxBatchSeq();

	// ══════════════════════════════════════════════════════════
	// WRITE OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Persists a new batch or merges an existing one (upsert via Hibernate merge).
	 * Opens an explicit transaction; rolls back and rethrows on failure.
	 *
	 * @param batch BatchEntity to save — must not be null
	 */
	void saveBatch(BatchEntity batch);

	/**
	 * Deletes a batch and all its cheques atomically.
	 * Cheques are deleted first (FK constraint), then the batch row.
	 * Rolls back both deletes if either fails.
	 *
	 * @param batchId batch PK — must not be null or blank
	 */
	void deleteBatchAndCheques(String batchId);

	/**
	 * Updates only the status column and updated_at timestamp for a batch.
	 * Silent on null batchId/status (logs warning, no exception).
	 *
	 * Called by: BatchServiceImpl, ChequeScanComposer
	 */
	void updateBatchStatus(String batchId, String status);

	/**
	 * Full field update for an existing batch — replaces branch_code,
	 * total_cheques, total_amount, control_amount, batch_type, status.
	 * Defaults status to 'Pending' if null is passed.
	 * Rethrows on failure.
	 *
	 * @param batch must have a non-null batchId
	 */
	void updateBatch(BatchEntity batch);

	/**
	 * Updates total_cheques, total_amount, and status after the ZIP import
	 * has been parsed and actual counts are known.
	 * Rethrows on failure.
	 *
	 * Called by: ZipImportServiceImpl after batch scan completes
	 */
	void updateBatchActualCounts(String batchId, int actualCheques, BigDecimal actualAmount, String status);

	// ══════════════════════════════════════════════════════════
	// READ OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Legacy — loads ALL batches ordered by created_at DESC.
	 * Use loadBatchesPage() for UI list screens to avoid full-table scans.
	 */
	List<BatchEntity> loadAllBatches();

	/**
	 * Paginated + filtered batch fetch — LIMIT/OFFSET pushed to DB, not in-memory.
	 *
	 * @param searchQuery   case-insensitive substring match on batch_id / branch_code
	 *                      (pass null or "" to skip)
	 * @param statusFilter  exact match on status column
	 *                      (pass null or "" to skip)
	 * @param fromDate      inclusive lower bound on created_at (SQL date string "YYYY-MM-DD",
	 *                      pass null to skip)
	 * @param toDate        inclusive upper bound on created_at (pass null to skip)
	 * @param pageSize      rows per page (must be > 0)
	 * @param pageNumber    1-based page index
	 * @return              list of BatchEntity for the requested slice (may be empty, never null)
	 */
	List<BatchEntity> loadBatchesPage(
			String searchQuery,
			String statusFilter,
			String fromDate,
			String toDate,
			int pageSize,
			int pageNumber);

	/**
	 * Total row count matching the same filters as loadBatchesPage() —
	 * needed for pagination controls to calculate total pages.
	 */
	long countBatches(
			String searchQuery,
			String statusFilter,
			String fromDate,
			String toDate);

	/**
	 * Fetches a single batch by primary key.
	 * Delegates to findBatchById() — kept for legacy callers.
	 *
	 * @return BatchEntity or null if not found / on error
	 */
	BatchEntity getBatchById(String batchId);

	/**
	 * Fetches a single batch by primary key.
	 * Returns null on not-found or DB error (never throws).
	 */
	BatchEntity findBatchById(String batchId);

	/**
	 * Returns batches that contain at least one high_value = true cheque.
	 * Uses EXISTS subquery to avoid loading cheque rows.
	 *
	 * Called by: HV dashboard / VerificationTwoComposer batch list
	 */
	List<BatchEntity> loadBatchesWithHvCheques();

	// ══════════════════════════════════════════════════════════
	// VERIFICATION I (V1) — Author: Anusha
	// ══════════════════════════════════════════════════════════

	/**
	 * Fetches exactly the batch rows whose batch_id is in the given set.
	 * Called by VerificationOneService after identifying which batch IDs
	 * have V1 cheques — no status filter applied here.
	 * Returns empty list on null/empty input or DB error.
	 *
	 * @param batchIds set of batch IDs to load
	 */
	List<BatchEntity> loadBatchesByIds(Set<String> batchIds);
}