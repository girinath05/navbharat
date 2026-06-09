package com.cts.outward.service;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * Verification2Service
 *
 * Service interface for Verification-II (High Value Cheque Authorisation).
 * Follows the same interface pattern as ChequeService / BatchService.
 *
 * The Composer (controller) talks only to this interface.
 * Implementation: Verification2ServiceImpl.
 */
public interface Verification2Service {

    /**
     * Returns all batches that contain at least one high-value cheque.
     * Includes derived counts (hvChequeCount, pendingCount, processedCount)
     * needed for the batch list table.
     */
    List<BatchModel> getHighValueBatches();

    /**
     * Returns all high-value cheques for the given batch,
     * ordered by cheque_no ascending.
     *
     * @param batchId  e.g. "BATCH0106"
     */
    List<ChequeModel> getHighValueChequesForBatch(String batchId);

    /**
     * Returns a single cheque by its PK (cts_cheques.id).
     * Used for popup detail or Prev/Next navigation.
     *
     * @param id  cts_cheques.id  (bigserial)
     */
    ChequeModel getChequeById(long id);

    /**
     * Persists the V2 verification decision for a single cheque.
     *
     * Updates: ver_action, ver_by, ver_remarks, ver_status, status, updated_at.
     *
     * @param id       cts_cheques.id (bigserial PK)
     * @param action   "ACCEPTED" or "REJECTED"
     * @param verBy    logged-in verifier username from ZK session
     * @param remarks  optional remarks text entered by verifier (may be blank)
     */
    void verifyHighValueCheque(long id, String action, String verBy, String remarks);
}