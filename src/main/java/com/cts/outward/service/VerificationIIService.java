package com.cts.outward.service;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * VerificationIIService
 *
 * Service interface for Verification Level II (High-Value Cheque Authorisation).
 * Follows the same interface pattern as ChequeService and BatchService.
 *
 * The Composer (controller) communicates only through this interface.
 * Implementation: VerificationIIServiceImpl.
 */
public interface VerificationIIService {

    /**
     * Retrieves all batches that contain at least one high-value cheque.
     * Includes derived counts (highValueChequeCount, pendingCount, processedCount)
     * required for the batch list table display.
     *
     * @return List of BatchModel representing high-value batches; empty list if none found.
     */
    List<BatchModel> fetchHighValueBatches();

    /**
     * Retrieves all high-value cheques belonging to the specified batch,
     * ordered by cheque number in ascending order.
     *
     * @param batchIdentifier  The unique batch identifier, e.g. "BATCH0106"
     * @return List of ChequeModel for the given batch; empty list if none found.
     */
    List<ChequeModel> fetchHighValueChequesForBatch(String batchIdentifier);

    /**
     * Retrieves a single cheque record by its primary key (cts_cheques.id).
     * Used for popup detail display and Previous / Next navigation.
     *
     * @param chequeId  The primary key of the cheque record (cts_cheques.id, bigserial)
     * @return ChequeModel for the specified identifier, or null if not found.
     */
    ChequeModel fetchChequeById(long chequeId);

    /**
     * Persists the Verification Level II decision for a single cheque.
     *
     * Updates the following columns in cts_cheques:
     *   ver_action, ver_by, ver_remarks, ver_status, status, updated_at.
     *
     * @param chequeId             Primary key of the cheque (cts_cheques.id, bigserial)
     * @param verificationAction   The verification decision: "VERIFIED" or "REJECTED"
     * @param verifierUsername     The logged-in verifier's username from the ZK session
     * @param verificationRemarks  Optional remarks entered by the verifier (may be blank)
     */
    void submitHighValueChequeVerification(long chequeId, String verificationAction,
                                           String verifierUsername, String verificationRemarks);

    /**
     * Evaluates the verification status of all cheques in the specified batch
     * and updates the batch-level status in cts_batches accordingly.
     *
     * @param batchIdentifier  The unique batch identifier, e.g. "BATCH0106"
     */
    void evaluateAndUpdateBatchVerificationStatus(String batchIdentifier);
}