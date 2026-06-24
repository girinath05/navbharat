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

    // ── Stat counts for the header cards ─────────────────
    long countCompleted();
    long countPending();

    // ── List queries for the tables ──────────────────────
    List<CxfBatchDTO> findCompletedBatches();
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