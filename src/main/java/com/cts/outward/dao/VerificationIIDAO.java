package com.cts.outward.dao;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * VerificationIIDAO
 *
 * Data-access interface for Verification Level II operations.
 * Follows the same pattern as BatchDAO and ChequeDAO in this project.
 *
 * Queries target:
 *   public.cts_batches  — batch header records
 *   public.cts_cheques  — cheque detail rows  (filtered by high_value = TRUE)
 */
public interface VerificationIIDAO {

    /**
     * Fetches all batches that contain at least one high-value cheque
     * (cts_cheques.high_value = TRUE).
     * Includes aggregated counts: highValueChequeCount, pendingCount, processedCount.
     * Results are ordered by created_at DESC (most recent batch first).
     *
     * @return List of BatchModel; empty list if no high-value batches exist.
     */
    List<BatchModel> fetchHighValueBatches();

    /**
     * Fetches all high-value cheques associated with the specified batch,
     * ordered by cheque_no ASC.
     *
     * @param batchIdentifier  The unique batch identifier from cts_batches.batch_id,
     *                         e.g. "BATCH0106"
     * @return List of ChequeModel; empty list if no matching cheques are found.
     */
    List<ChequeModel> fetchHighValueChequesForBatch(String batchIdentifier);

    /**
     * Fetches a single cheque record by its primary key (cts_cheques.id bigserial).
     * Used for the popup detail view.
     *
     * @param chequeId  The primary key value from cts_cheques.id
     * @return ChequeModel for the specified identifier, or null if not found.
     */
    ChequeModel fetchChequeById(long chequeId);

    /**
     * Persists the Verification Level II decision for a single cheque.
     *
     * Updates the following columns in cts_cheques:
     *   ver_action  → verificationAction  ("VERIFIED" or "REJECTED")
     *   ver_by      → verifierUsername    (logged-in user's login name)
     *   ver_remarks → verificationRemarks (optional remarks entered in the UI)
     *   ver_status  → resolved status     ("Verified" or "Rejected")
     *   status      → resolved status     ("Verified" or "Rejected")
     *   updated_at  → NOW()
     *
     * @param chequeId             Primary key of the cheque (cts_cheques.id, bigserial)
     * @param verificationAction   The verification decision: "VERIFIED" or "REJECTED"
     * @param verifierUsername     The logged-in verifier's login name
     * @param verificationRemarks  Optional remarks from the UI remarks textbox
     */
    void persistVerificationDecision(long chequeId, String verificationAction,
                                     String verifierUsername, String verificationRemarks);

    /**
     * Evaluates cts_cheques.status for all cheques in the specified batch (V1 and V2 combined)
     * and updates cts_batches.status accordingly:
     *
     *   All cheques actioned  → "Verified"               (BatchStatus.VERIFIED.db())
     *   Partially actioned    → "VerificationInProgress" (BatchStatus.VERIFICATION_IN_PROGRESS.db())
     *
     * @param batchIdentifier  The unique batch identifier from cts_batches.batch_id
     */
    void evaluateAndUpdateBatchVerificationStatus(String batchIdentifier);
}