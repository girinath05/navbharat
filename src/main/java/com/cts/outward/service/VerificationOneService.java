/*
 * Project  : Navbharat CTS Outward
 * File     : VerificationOneService.java
 * Package  : com.cts.outward.service
 * Author   : Anusha M.
 * Created  : June 2026
 * Description : Service contract for all business operations used by the
 *               Verification I (Checker) screen.
 */
package com.cts.outward.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.model.BatchSummary;
import com.cts.outward.model.CbsAccountDetails;

public interface VerificationOneService {

    // ── Batch list (Phase 1) ─────────────────────────────────────────────

    /**
     * Returns a summary row for every batch that has at least one V1-pending cheque
     * and is in a verifiable status (READY_FOR_VERIFICATION or VERIFICATION_IN_PROGRESS).
     * Each row includes pre-computed pending and processed cheque counts.
     */
    List<BatchSummary> getVerifiableBatchSummaries();

    // ── Batch open (Phase 1 → Phase 2) ──────────────────────────────────

    /**
     * Transitions the batch to VERIFICATION_IN_PROGRESS if it is being opened for the
     * first time (i.e., currently in READY_FOR_VERIFICATION). Returns only the
     * V1-pending cheques in that batch.
     *
     * @param batchId  batch to open; must not be null
     * @return V1-pending cheques, or an empty list if none remain
     */
    List<ChequeEntity> openBatchForVerification(String batchId);

    // ── Cheque verification popup ────────────────────────────────────────

    /**
     * Fetches and parses the CBS account details for the given account number.
     * Compares the CBS account holder name against the payee name on the cheque.
     * Returns a fully populated {@link CbsAccountDetails} DTO with display-ready
     * values; never returns null (uses safe fallback states for missing/unknown data).
     *
     * @param accountNumber      payee account number to look up; may be null or blank
     * @param payeeNameOnCheque  payee name written on the cheque, used for the match check
     */
    CbsAccountDetails getCbsAccountDetails(String accountNumber, String payeeNameOnCheque);

    /**
     * Validates the CBS account (must exist and be active) and, if it passes,
     * marks the cheque as VERIFIED.
     *
     * <p>Returns {@code null} on success. Returns a user-facing error message if the
     * account is not found in CBS or is inactive — the caller should show this message
     * and abort the action.
     *
     * @param chequeId       cheque to accept
     * @param accountNumber  payee account number (validation is skipped if null or blank)
     * @param verifiedBy     username of the verifier performing the action
     * @return null on success, or an error message string on CBS validation failure
     */
    String validateAndAcceptCheque(Long chequeId, String accountNumber, String verifiedBy);

    /**
     * Marks the cheque as REJECTED.
     *
     * @param chequeId         cheque to reject
     * @param rejectedBy       username of the verifier performing the action
     * @param rejectionReason  reason code selected by the verifier
     */
    void rejectCheque(Long chequeId, String rejectedBy, String rejectionReason);

    /**
     * Escalates the cheque to Verification II (status → V2_PENDING).
     *
     * @param chequeId    cheque to refer
     * @param referredBy  username of the verifier performing the action
     * @param referReason reason code selected by the verifier
     */
    void referCheque(Long chequeId, String referredBy, String referReason);

    // ── Batch finalization ───────────────────────────────────────────────

    /**
     * Checks whether all cheques in the batch have been actioned and, if so,
     * advances the batch status to VERIFIED.
     *
     * @param batchId  batch to check; null → no-op with a warning log
     */
    void checkAndFinalizeBatch(String batchId);

    // ── Low-level reads ──────────────────────────────────────────────────

    /** Returns all batches from the database. */
    List<BatchEntity> getAllBatches();

    /** Returns the batch with the given ID, or null if not found. */
    BatchEntity getBatchById(String batchId);

    /** Returns all cheques that belong to the given batch. */
    List<ChequeEntity> getChequesForBatch(String batchId);

    /**
     * Returns all cheques at verification level "V1" whose status matches
     * the given DB status string.
     */
    List<ChequeEntity> getV1PendingCheques(String status);

    /** Directly updates the status column of a batch row. */
    void updateBatchStatus(String batchId, String newStatus);

    /** Looks up live account fields from CBS (Firestore). */
    JsonNode lookupAccountFields(String accountNumber);

    /** Returns "Yes", "No", or "\u2014" depending on whether the account is less than 90 days old. */
    String getIsNewAccount(String accountNumber);
}