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

    // ─────────────────────────────────────────────────────────────
    // SEQUENCE
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns next available batch sequence number for batchId generation.
     * Reads current MAX(seq) from DB — survives restarts and discards.
     * Format: "BATCH" + String.format("%04d", loadMaxBatchSeq()) → "BATCH0042"
     *
     * Called by: BatchServiceImpl.createBatch()
     */
    int loadMaxBatchSeq();

    // ─────────────────────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────────────────────

    /**
     * Inserts or merges a BatchEntity into cts_batches.
     * Opens explicit transaction; rolls back and rethrows on failure.
     *
     * Called by: BatchServiceImpl.createBatch()
     */
    void saveBatch(BatchEntity batch);

    /**
     * Permanently deletes a batch AND all its child cheques from DB.
     * DELETE cts_cheques first, then DELETE cts_batches (FK order).
     * Runs in single transaction — both deletes succeed or both roll back.
     *
     * Called by: BatchServiceImpl.discardBatch()
     */
    void deleteBatchAndCheques(String batchId);

    // ─────────────────────────────────────────────────────────────
    // READ — LEGACY
    // ─────────────────────────────────────────────────────────────

    /**
     * Legacy — loads ALL batches into memory ordered by created_at DESC.
     * Use loadBatchesPage() for any UI list screen.
     *
     * Called by: BatchServiceImpl.getAllBatches() → BatchChequeEntryComposer
     */
    List<BatchEntity> loadAllBatches();

    // ─────────────────────────────────────────────────────────────
    // READ — PAGINATED
    // ─────────────────────────────────────────────────────────────

    /**
     * Paginated + filtered batch fetch — LIMIT/OFFSET pushed to Postgres.
     * All filter params bound as named params; never string-interpolated.
     *
     * Filter semantics:
     *   searchQuery  — ILIKE %term% on batch_id OR branch_code (null/blank = skip)
     *   statusFilter — exact match on status column (null/blank/"All Status" = skip)
     *   fromDate     — created_at::date >= fromDate "YYYY-MM-DD" (null = skip)
     *   toDate       — created_at::date <= toDate   "YYYY-MM-DD" (null = skip)
     *
     * @param searchQuery   case-insensitive substring match on batch_id / branch_code
     * @param statusFilter  exact match on status column
     * @param fromDate      inclusive lower bound on created_at (SQL date "YYYY-MM-DD")
     * @param toDate        inclusive upper bound on created_at
     * @param pageSize      rows per page (must be > 0)
     * @param pageNumber    1-based page index
     * @return              list of BatchEntity for requested slice (empty, never null)
     *
     * Called by: BatchServiceImpl.getBatchesPage() → MyBatchesComposer.loadPage()
     */
    List<BatchEntity> loadBatchesPage(
            String searchQuery,
            String statusFilter,
            String fromDate,
            String toDate,
            int pageSize,
            int pageNumber);

    /**
     * Total row count matching same filters as loadBatchesPage().
     * Must be called with identical filter args for accurate "Page X of Y" labels.
     *
     * Called by: BatchServiceImpl.countBatches() → MyBatchesComposer.loadPage()
     */
    long countBatches(
            String searchQuery,
            String statusFilter,
            String fromDate,
            String toDate);

    // ─────────────────────────────────────────────────────────────
    // STATUS UPDATE
    // ─────────────────────────────────────────────────────────────

    /**
     * Updates status column + updated_at for a single batch row.
     * Use BatchStatus enum values for newStatus to avoid magic strings.
     *
     * Called by: BatchServiceImpl (submitBatch, checkAndFinalizeBatch, updateBatchStatus)
     */
    void updateBatchStatus(String batchId, String status);

    /**
     * Full batch UPDATE — overwrites branch_code, totals, amounts, batch_type, status.
     * Use when multiple fields change together (e.g. after ZIP import reconciliation).
     *
     * Called by: BatchServiceImpl.updateBatch() (if delegated) or directly by composers
     */
    void updateBatch(BatchEntity batch);

    /**
     * Targeted UPDATE for post-ZIP-import reconciliation:
     * sets total_cheques, total_amount, status, updated_at in one query.
     * Avoids full updateBatch() overhead when only counts/amounts change.
     *
     * Called by: ZipImportServiceImpl after parsing ZIP scan bundle
     */
    void updateBatchActualCounts(String batchId, int actualCheques, BigDecimal actualAmount, String status);

    // ─────────────────────────────────────────────────────────────
    // LOOKUP
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetches single BatchEntity by primary key batch_id.
     * Returns null if not found or batchId is null/blank.
     *
     * Called by: BatchServiceImpl.getBatchById() → BatchDetailComposer
     */
    BatchEntity getBatchById(String batchId);

    /**
     * Alias for getBatchById — same SELECT, different name for call-site clarity.
     * Both delegate to the same native query.
     *
     * Called by: BatchServiceImpl.getBatchById() (delegates here)
     */
    BatchEntity findBatchById(String batchId);

    /**
     * Returns all batches that contain at least one high_value cheque.
     * Uses EXISTS subquery on cts_cheques.high_value = true.
     *
     * Called by: reporting / HV-specific batch list screens
     */
    List<BatchEntity> loadBatchesWithHvCheques();
    
    //v1
	List<BatchEntity> loadBatchesByIds(Set<String> batchIds);
}