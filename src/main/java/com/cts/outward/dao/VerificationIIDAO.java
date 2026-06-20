package com.cts.outward.dao;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * VerificationIIDAO
 *
 * Interface for Verification-II data access.
 * Follows the same pattern as BatchDAO / ChequeDAO in this project.
 *
 * Queries target:
 *   public.cts_batches   — batch header
 *   public.cts_cheques   — cheque rows  (high_value = true filter)
 */
public interface VerificationIIDAO {

    /**
     * Fetch all batches that contain at least one high-value cheque
     * (cts_cheques.high_value = TRUE).
     * Includes aggregated counts: hvChequeCount, pendingCount, processedCount.
     * Ordered by created_at DESC (newest batch first).
     *
     * @return List of BatchModel; empty list if none found.
     */
    List<BatchModel> getHighValueBatches();

    /**
     * Fetch all high-value cheques for a given batch,
     * ordered by cheque_no ASC.
     *
     * @param batchId  cts_batches.batch_id  e.g. "BATCH0106"
     * @return List of ChequeModel; empty list if none found.
     */
    List<ChequeModel> getHighValueChequesForBatch(String batchId);

    /**
     * Fetch a single cheque by its primary key (cts_cheques.id bigserial).
     * Used for popup detail view.
     *
     * @param id  cts_cheques.id
     * @return ChequeModel or null if not found.
     */
    ChequeModel getChequeById(long id);

    /**
     * Persist the V2 verification decision for a single cheque.
     *
     * Updates in cts_cheques:
     *   ver_action  → action ("ACCEPTED" or "REJECTED")
     *   ver_by      → verifier's login name
     *   ver_remarks → remarks entered by verifier (may be blank)
     *   ver_status  → "Accepted" or "Rejected"
     *   status      → "Accepted" or "Rejected"
     *   updated_at  → NOW()
     *
     * @param id       cts_cheques.id (bigserial PK)
     * @param action   "ACCEPTED" or "REJECTED"
     * @param verBy    logged-in verifier username
     * @param remarks  optional remarks from UI textbox
     */
    void updateVerification(long id, String action, String verBy, String remarks);

	/**
	 * Checks cts_cheques.status for ALL cheques in the batch (V1 + V2)
	 * and updates cts_batches.status accordingly:
	 *
	 *   All actioned  → "Verified"               (BatchStatus.VERIFIED.db())
	 *   Partially done → "VerificationInProgress" (BatchStatus.VERIFICATION_IN_PROGRESS.db())
	 */
	void checkAndUpdateBatchStatus(String batchId);
}