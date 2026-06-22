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

// BatchDAO.java
package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.entity.BatchEntity;

public interface BatchDAO {

    int loadMaxBatchSeq();

    void saveBatch(BatchEntity batch);

    void deleteBatchAndCheques(String batchId);

    /** Legacy — loads ALL batches. Use loadBatchesPage() for UI lists. */
    List<BatchEntity> loadAllBatches();

    /**
     * Paginated + filtered batch fetch — pushed to DB, not in-memory.
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
     * Total row count matching the same filters — needed for pagination controls.
     */
    long countBatches(
            String searchQuery,
            String statusFilter,
            String fromDate,
            String toDate);

    void updateBatchStatus(String batchId, String status);

    BatchEntity getBatchById(String batchId);

    void updateBatch(BatchEntity batch);

    BatchEntity findBatchById(String batchId);

    void updateBatchActualCounts(String batchId, int actualCheques, BigDecimal actualAmount, String status);

    /** Batches that have at least one high_value cheque */
    List<BatchEntity> loadBatchesWithHvCheques();
}