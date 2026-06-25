package com.cts.outward.service;

import com.cts.outward.dto.CxfBatchDTO;

import java.util.List;

/**
 * Service interface for CXF-CIBF generation.
 * Follows existing BatchService / VerificationService interface+impl pattern.
 *
 * Flow: ZUL → Composer → Service (this) → DAO → Database
 * Composer must NEVER call DAO directly — only through this interface.
 */
public interface CxfCibfService {

    // ── List queries (used by Composer for page data) ──────────────────────

    /**
     * Returns all verified batches pending CXF-CIBF file generation.
     *
     * @return list of pending batch DTOs
     */
    List<CxfBatchDTO> findPendingBatches();

    /**
     * Returns all batches that have completed CXF-CIBF file generation.
     *
     * @return list of completed batch DTOs
     */
    List<CxfBatchDTO> findCompletedBatches();

    // ── Stat counts ────────────────────────────────────────────────────────

    /**
     * Counts the total number of completed batches.
     *
     * @return count of completed batches
     */
    long countCompleted();

    /**
     * Counts the total number of pending batches.
     *
     * @return count of pending batches
     */
    long countPending();

    // ── Generation ─────────────────────────────────────────────────────────

    /**
     * Generate CXF + CIBF + ZIP for all VER2_DONE batches.
     * Called by VerificationTwoComposer after approval.
     */
    List<CxfFileResult> generateAllVerified();

    /**
     * Generate CXF + CIBF + ZIP for one specific batch.
     *
     * @param batchId the cts_batches.batch_id value
     */
    CxfFileResult generateForBatch(String batchId);

    /**
     * Generates ONE combined CXF+CIBF output for all the supplied batch IDs.
     * Cheques from every batch are pooled together and processed in a single run.
     * All valid batch DB records are updated upon success.
     *
     * @param batchIds list of batch IDs selected by the user (1 or more)
     * @return a single CxfFileResult representing the combined generation outcome
     */
    CxfFileResult generateForBatches(List<String> batchIds);
}