package com.cts.outward.service;

import java.util.List;

/**
 * Service interface for CXF-CIBF generation.
 * Follows existing BatchService / VerificationService interface+impl pattern.
 */
public interface CxfCibfService {

    /**
     * Generate CXF + CIBF + ZIP for all VER2_DONE batches.
     * Called by VerificationTwoComposer after approval.
     */
    List<CxfFileResult> generateAllVerified();

    /**
     * Generate CXF + CIBF + ZIP for one specific batch.
     * @param batchId the cts_batches.batch_id value
     */
    CxfFileResult generateForBatch(String batchId);

    // ── Stat counts for UI ────────────────────────────────
    long countCompleted();
    long countPending();
}
