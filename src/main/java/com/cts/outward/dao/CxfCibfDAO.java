package com.cts.outward.dao;

import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.dto.CxfChequeDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAO interface for the CXF-CIBF Generation module.
 * Follows the existing BatchDAO / ChequeDAO interface+impl pattern in this project.
 */
public interface CxfCibfDAO {

    /**
     * Counts the total number of batches that have generated files.
     * 
     * @return count of completed batches
     */
    long countCompleted();

    /**
     * Counts the total number of batches pending file generation.
     * 
     * @return count of pending batches
     */
    long countPending();

    /**
     * Finds all batches that have completed the file generation process.
     * 
     * @return list of completed batch DTOs
     */
    List<CxfBatchDTO> findCompletedBatches();

    /**
     * Finds all batches pending file generation.
     * 
     * @return list of pending batch DTOs
     */
    List<CxfBatchDTO> findPendingBatches();

    // ── Batches ready for generation ──────────────────────
    /** Returns all batches ready for generation */
    List<CxfBatchDTO> findVerifiedBatches();

    /** Returns one specific batch by batchId */
    CxfBatchDTO findVerifiedBatch(String batchId);

    // ── Cheques for one batch ─────────────────────────────
    /** Load all cheques for a batch including front_image and rear_image bytes */
    List<CxfChequeDTO> findChequesForBatch(String batchId);

    // ── Status updates ────────────────────────────────────
    void markCxfGenerated(String batchId, String cxfFileName,
                          String cibfFileName, LocalDateTime generatedAt);
}