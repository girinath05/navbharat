/*
 * File        : VerificationOneService.java
 * Package     : com.cts.outward.service
 * Description : Contract (interface) for all business logic that powers the
 *               Verification I (Checker) screen.
 *
 *               Design rule — the composer (UI layer) must NEVER contain
 *               business rules.  Every decision about cheque statuses, batch
 *               transitions, CBS validation, or "what comes next" belongs
 *               here.  The composer only calls these methods and updates
 *               ZK components with the results.
 *
 *               Sections
 *               ─────────────────────────────────────────────────────────────
 *               1. Batch list        — load, filter, open, status check
 *               2. Cheque list       — load, filter, count by status
 *               3. Cheque actions    — accept / reject / refer, batch finalise
 *               4. In-memory helpers — update entity state after an action,
 *                                      find next pending cheque
 *               5. CBS integration   — account lookup + payee-name match
 *               6. Date formatting   — display and comparison helpers
 */
package com.cts.outward.service;

import java.util.Date;
import java.util.List;

import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.model.BatchSummary;
import com.cts.outward.model.CbsAccountDetails;

public interface VerificationOneService {

    // ══════════════════════════════════════════════════════════════════════
    // 1. BATCH LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the Phase-1 batch list that the verifier sees on screen.
     *
     * Returns one row per batch that has any V1-level cheque (pending or
     * already processed).  Each row carries counts for pending and
     * processed cheques so the verifier can see progress at a glance.
     *
     * Rows are sorted: READY_FOR_VERIFICATION first, then
     * VERIFICATION_IN_PROGRESS, then VERIFIED — newest batch first
     * within each group.
     */
    List<BatchSummary> getVerifiableBatchSummaries();

    /**
     * Applies the user's filter selections to the already-loaded batch list.
     * All filtering is done in memory — no extra database calls.
     *
     * @param allBatches  the full unfiltered list returned by getVerifiableBatchSummaries()
     * @param searchText  typed text; matches anywhere in the Batch ID (case-insensitive).
     *                    Pass an empty string to skip text filtering.
     * @param statusValue the DB status string chosen from the status drop-down
     *                    (e.g. "READY_FOR_VERIFICATION"), or null / "ALL" to skip.
     * @param fromDate    earliest allowed batch creation date (inclusive); null = no lower bound.
     * @param toDate      latest  allowed batch creation date (inclusive); null = no upper bound.
     * @return a new list containing only the batches that pass all active filters.
     */
    List<BatchSummary> filterBatchSummaries(
            List<BatchSummary> allBatches,
            String searchText,
            String statusValue,
            Date   fromDate,
            Date   toDate);

    /**
     * Looks up and returns the current status of a single batch from the database.
     * Used by the composer to decide whether to open a batch in read-only mode
     * (VERIFIED) or in editable mode.
     *
     * @param batchId the unique batch identifier
     * @return the current BatchStatus; defaults to VERIFICATION_IN_PROGRESS if the
     *         batch row cannot be found (defensive fallback).
     */
    BatchStatus getBatchStatus(String batchId);

    /**
     * Returns true when the batch is VERIFIED, meaning the verifier must not
     * be allowed to change any cheque inside it.
     *
     * Business rule lives here so the composer never compares BatchStatus values
     * directly — it simply asks "is this batch read-only?".
     *
     * @param batchId the unique batch identifier
     * @return true if the batch status is VERIFIED; false for all other statuses.
     */
    boolean isBatchReadOnly(String batchId);

    /**
     * Transitions the batch status from READY_FOR_VERIFICATION to
     * VERIFICATION_IN_PROGRESS the first time a verifier opens it.
     *
     * This is a no-op (does nothing) if the batch is already
     * IN_PROGRESS or VERIFIED, so it is safe to call every time the
     * verifier opens a batch.
     *
     * @param batchId the unique batch identifier
     */
    void openBatchForVerification(String batchId);

    // ══════════════════════════════════════════════════════════════════════
    // 2. CHEQUE LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Loads every V1-level cheque that belongs to the given batch,
     * regardless of the cheque's current status (pending, accepted, rejected).
     *
     * Note: referred cheques are excluded from this list because referring a
     * cheque flips its verification level to "V2" in the database.  The
     * composer keeps referred cheques visible for the current session by
     * updating the in-memory entity — see applyActionToInMemoryCheque().
     *
     * @param batchId the unique batch identifier
     * @return list of V1 cheques; never null (returns an empty list if none found).
     */
    List<ChequeEntity> getAllV1ChequesForBatch(String batchId);

    /**
     * Applies the user's filter selections to the already-loaded cheque list.
     * All filtering is done in memory — no extra database calls.
     *
     * Text search matches the cheque number, the payee name, or the amount.
     *
     * @param allCheques  the full unfiltered list for the active batch
     * @param searchText  typed text; case-insensitive partial match. Empty = no filter.
     * @param statusValue the DB status string (e.g. "V1_PENDING"), or null / "ALL" to skip.
     * @param fromDate    earliest allowed cheque date (inclusive); null = no lower bound.
     * @param toDate      latest  allowed cheque date (inclusive); null = no upper bound.
     * @return a new list containing only the cheques that pass all active filters.
     */
    List<ChequeEntity> filterCheques(
            List<ChequeEntity> allCheques,
            String searchText,
            String statusValue,
            Date   fromDate,
            Date   toDate);

    /**
     * Counts how many cheques in the supplied list have the given status.
     * Used to drive the Pending / Accepted / Rejected / Referred counters
     * shown above the cheque table.
     *
     * @param cheques the in-memory cheque list for the active batch
     * @param status  the status to count
     * @return the count; 0 if the list is null or empty.
     */
    long countByStatus(List<ChequeEntity> cheques, ChequeStatus status);

    // ══════════════════════════════════════════════════════════════════════
    // 3. CHEQUE ACTIONS  (persist to database)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validates CBS rules and, if they pass, marks the cheque as VERIFIED
     * (accepted) in the database.
     *
     * Validation rule: if the cheque carries an account number, that account
     * must exist in CBS and must be active.  If validation fails, the cheque
     * is NOT saved and a user-facing error message is returned instead.
     *
     * @param chequeId      database primary key of the cheque
     * @param accountNumber payee account number printed on the cheque; may be blank
     * @param acceptedBy    username of the logged-in verifier
     * @return null when the cheque is accepted successfully; a non-null error
     *         string (ready to show in a message box) when validation fails.
     */
    String validateAndAcceptCheque(Long chequeId, String accountNumber, String acceptedBy);

    /**
     * Marks the cheque as REJECTED in the database and stores the reason
     * the verifier selected.
     *
     * @param chequeId        database primary key of the cheque
     * @param rejectedBy      username of the logged-in verifier
     * @param rejectionReason the reason code or text chosen from the drop-down
     */
    void rejectCheque(Long chequeId, String rejectedBy, String rejectionReason);

    /**
     * Escalates the cheque to Verification II.
     *
     * Database side effects:
     *   • status     → V2_PENDING
     *   • ver_level  → "V2"
     *   • is_referred → true
     *
     * Because ver_level flips to V2, the cheque will no longer appear in
     * getAllV1ChequesForBatch() on the next page load.  The composer keeps
     * it visible for the current session by calling applyActionToInMemoryCheque().
     *
     * @param chequeId   database primary key of the cheque
     * @param referredBy username of the logged-in verifier
     * @param referReason the reason code or text chosen from the drop-down
     */
    void referCheque(Long chequeId, String referredBy, String referReason);

    /**
     * Checks whether all cheques in the batch have been actioned and, if so,
     * advances the batch status from VERIFICATION_IN_PROGRESS to VERIFIED.
     *
     * Call this after every accept / reject / refer action so the batch
     * automatically finalises when the last cheque is processed.
     *
     * @param batchId the unique batch identifier
     */
    void checkAndFinalizeBatch(String batchId);

    // ══════════════════════════════════════════════════════════════════════
    // 4. IN-MEMORY HELPERS  (update entity state without hitting the DB)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Updates the in-memory ChequeEntity fields to reflect the action just
     * persisted to the database.
     *
     * Why this exists: after accept / reject / refer, the database is updated
     * but the in-memory list is not re-fetched (to avoid an extra round-trip).
     * This method applies the same status change to the entity object so the
     * on-screen counters and badges stay accurate without a reload.
     *
     * Business rules applied here:
     *   • Always set both status and verStatus fields.
     *   • For a REFER action: additionally set the isReferred flag to true,
     *     because the DB column is_referred is a separate boolean that must
     *     also be kept in sync in memory.
     *
     * @param cheque    the entity object held in the composer's in-memory list
     * @param newStatus the new DB status string (e.g. "VERIFIED", "REJECTED", "V2_PENDING")
     */
    void applyActionToInMemoryCheque(ChequeEntity cheque, String newStatus);

    /**
     * Scans the full (unfiltered) in-memory cheque list and returns the list
     * index of the first cheque that still has status V1_PENDING.
     *
     * Business rule: V1_PENDING is the only status that the verifier can act on.
     * All other statuses (VERIFIED, REJECTED, referred) are already done.
     *
     * @param allCheques the full unfiltered in-memory list for the active batch
     * @return the zero-based index of the next pending cheque, or -1 if every
     *         cheque has already been actioned.
     */
    int findNextPendingChequeIndex(List<ChequeEntity> allCheques);

    // ══════════════════════════════════════════════════════════════════════
    // 5. CBS INTEGRATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Calls the Core Banking System (CBS) to look up the account that the
     * cheque payee holds, and returns a result object the composer can display
     * directly in the verification popup.
     *
     * The result object carries:
     *   • Account holder name from CBS
     *   • Account active / inactive status
     *   • Whether the account is a new account (opened recently)
     *   • Whether the CBS name matches the name printed on the cheque
     *
     * If the cheque has no account number, or CBS returns no record, the
     * result object still has safe placeholder values so the popup never
     * shows blank or null fields.
     *
     * Note: CSS style class names (sclass) are NOT part of this result.
     * The composer applies those based on the lookup state and boolean flags,
     * keeping all presentation decisions out of the service layer.
     *
     * @param accountNumber      the payee account number from the cheque; may be blank
     * @param payeeNameOnCheque  the payee name printed on the cheque; used for name-match
     * @return a fully populated CbsAccountDetails object; never null.
     */
    CbsAccountDetails getCbsAccountDetails(String accountNumber, String payeeNameOnCheque);

    // ══════════════════════════════════════════════════════════════════════
    // 6. DATE FORMATTING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Converts an ISO-format timestamp string (e.g. "2026-06-24T18:07:32")
     * to a short display date ("24/06/2026") for the batch table DATE column.
     *
     * Returns an em-dash (—) for null, blank, or unparseable input so the
     * table cell is never empty.
     *
     * @param isoTimestamp the raw timestamp string stored in the database
     * @return formatted date string, or "—" if the input cannot be parsed.
     */
    String formatDisplayDate(String isoTimestamp);
}