/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ChequeService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Date        : 24-06-2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Service interface defining all business operations on
 *  individual CTS cheques. Composers use this contract to
 *  load, save, delete, and query cheques without depending on
 *  DAO or Hibernate directly.
 *
 *  Single implementation: ChequeServiceImpl.java
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS INTERFACE FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Composer Layer]              [Service Layer]           [DAO / External]
 *  ────────────────────────────  ────────────────────────  ───────────────────────────
 *  BatchDetailComposer           ← ChequeService (this)   ChequeDAOImpl → cts_cheques
 *  BatchChequeEntryComposer        ChequeServiceImpl   →  CBSService    → Firebase
 *  VerificationOneComposer
 *  VerificationTwoComposer
 *
 * ──────────────────────────────────────────────────────────────
 *  CHEQUE FIELD EDIT WORKFLOW
 * ──────────────────────────────────────────────────────────────
 *  The Maker fills in cheque fields in batch-detail.zul.
 *  On "Save" click:
 *
 *  BatchDetailComposer.onSaveChequeFields()
 *    → ChequeService.saveChequeFields(cheque)     [persist edits]
 *    → ChequeService.lookupAccount(accountNo)     [CBS validation]
 *    → BatchService.areAllChequesReady(batchId)   [enable Submit?]
 *
 * ──────────────────────────────────────────────────────────────
 *  CBS / FIREBASE ACCOUNT LOOKUP
 * ──────────────────────────────────────────────────────────────
 *  lookupAccount() calls CBSService which queries Firebase
 *  Firestore for live account data. Returns a String[] of
 *  account fields consumed directly by BatchDetailComposer
 *  to populate the cheque detail form.
 *
 *  If Firebase is unreachable, CBSService returns a fallback
 *  array so the Maker can still save fields manually.
 *
 * ──────────────────────────────────────────────────────────────
 *  CHEQUE DELETE — CASCADE RULE
 * ──────────────────────────────────────────────────────────────
 *  deleteCheque() is a compound operation:
 *    1. DELETE FROM cts_cheques WHERE id = ?
 *    2. UPDATE cts_batches SET
 *         total_cheques = total_cheques - 1,
 *         total_amount  = total_amount  - chequeAmount
 *       WHERE batch_id = parentBatchId
 *
 *  This keeps the batch control totals accurate when the
 *  Maker removes a cheque from the detail screen.
 *
 * ──────────────────────────────────────────────────────────────
 *  METHOD GROUPINGS
 * ──────────────────────────────────────────────────────────────
 *  Reads   : getChequesForBatch, countPending
 *  Writes  : saveChequeFields, deleteCheque
 *  External: lookupAccount (CBS/Firebase)
 * ============================================================
 */

package com.cts.outward.service;

import java.util.List;

import com.cts.outward.entity.ChequeEntity;

/**
 * Service contract for all CTS cheque-level business operations.
 *
 * <p>Composers depend on this interface — never on {@code ChequeServiceImpl}
 * directly — keeping the UI layer decoupled from persistence and external
 * service details.
 *
 * <p>Instantiation pattern (no DI framework):
 * <pre>
 *   ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());
 * </pre>
 *
 * @author Umesh M.
 * @see ChequeServiceImpl
 * @see com.cts.outward.dao.ChequeDAO
 */
public interface ChequeService {

    // ══════════════════════════════════════════════════════════════════════
    // READ OPERATIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns all cheques belonging to the given batch, ordered by their
     * position in the batch (cheque sequence number ascending).
     *
     * <p>This is the primary data-load call for the Batch Detail screen.
     * Results are rendered row-by-row in the cheque listbox, with each row
     * showing MICR data, amount, status, and an edit action link.
     *
     * <h3>Called by</h3>
     * <ul>
     *   <li>{@code BatchDetailComposer.loadChequesForBatch()} — on page load
     *       and after every save/delete to refresh the table</li>
     *   <li>{@code BatchChequeEntryComposer.loadChequesForBatch()} — ghost-row
     *       detection in the Scan Module batch filter</li>
     * </ul>
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.loadChequesForBatch()
     *   → ChequeService.getChequesForBatch(batchId)
     *       → ChequeServiceImpl.getChequesForBatch()
     *           → ChequeDAOImpl.loadChequesForBatch(batchId)
     *               [SELECT * FROM cts_cheques WHERE batch_id = ? ORDER BY seq ASC]
     * </pre>
     *
     * @param batchId the parent batch ID (e.g. "BATCH0042")
     * @return ordered list of {@link ChequeEntity} for this batch;
     *         empty list if none found or batchId is null
     */
    List<ChequeEntity> getChequesForBatch(String batchId);

    /**
     * Returns the count of cheques currently in "Pending" verification status
     * across all batches visible to the current Maker.
     *
     * <p>Used to populate the "Pending" stat card on the Scan Module dashboard.
     * A cheque is "Pending" when it has been imported but the Maker has not yet
     * completed all required field edits.
     *
     * <h3>Called by</h3>
     * {@code BatchChequeEntryComposer.refreshStats()} — updates the stat card
     * label after every import, create, and discard operation.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchChequeEntryComposer.refreshStats()
     *   → ChequeService.countPending()
     *       → ChequeServiceImpl.countPending()
     *           → ChequeDAOImpl.countByStatus("Pending")
     *               [SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending']
     * </pre>
     *
     * @return total count of Pending cheques; 0 if none or on DB error
     */
    long countPending();

    // ══════════════════════════════════════════════════════════════════════
    // WRITE OPERATIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists the Maker's field edits for a single cheque to the DB and
     * updates its status to "Ready" if all required fields are now filled.
     *
     * <p>The cheque entity passed in must already have all edited fields set
     * by the caller (BatchDetailComposer). This method does not re-read the
     * entity from DB — it persists what it receives.
     *
     * <p>Status transition on save:
     * <ul>
     *   <li>All required fields complete → status set to "Ready"</li>
     *   <li>Any required field still missing → status remains "Pending"</li>
     * </ul>
     *
     * <h3>Called by</h3>
     * {@code BatchDetailComposer.onSaveChequeFields()} — Maker clicks "Save"
     * on the cheque edit form in the Batch Detail screen.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.onSaveChequeFields()
     *   → ChequeService.saveChequeFields(cheque)
     *       → ChequeServiceImpl.saveChequeFields()
     *           → validates required fields
     *           → sets status = "Ready" if all fields present
     *           → ChequeDAOImpl.updateChequeFields(cheque)
     *               [UPDATE cts_cheques SET ... WHERE id = ?]
     * </pre>
     *
     * @param cheque {@link ChequeEntity} with all edited fields populated by
     *               the Maker; must have a valid non-null {@code id}
     */
    void saveChequeFields(ChequeEntity cheque);

    /**
     * Deletes a cheque from the DB and decrements the parent batch's
     * control totals (cheque count and total amount).
     *
     * <p>This is a compound operation — both the cheque row and the parent
     * batch's aggregates are updated atomically within a single transaction:
     * <ol>
     *   <li>{@code DELETE FROM cts_cheques WHERE id = ?}</li>
     *   <li>{@code UPDATE cts_batches SET total_cheques = total_cheques - 1,
     *       total_amount = total_amount - chequeAmount WHERE batch_id = ?}</li>
     * </ol>
     *
     * <p>If the parent batch reaches zero cheques after deletion, it remains
     * in the DB (not auto-deleted). The Maker can then either upload a new ZIP
     * or manually discard the batch.
     *
     * <h3>Called by</h3>
     * {@code BatchDetailComposer.onDeleteCheque()} — Maker clicks the delete
     * icon on a cheque row in the Batch Detail screen.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.onDeleteCheque()
     *   → ChequeService.deleteCheque(chequeId)
     *       → ChequeServiceImpl.deleteCheque()
     *           → ChequeDAOImpl.loadChequeById(chequeId)   [get amount + batchId]
     *           → ChequeDAOImpl.deleteCheque(chequeId)     [DELETE row]
     *           → BatchDAOImpl.decrementBatchTotals(batchId, amount)
     *               [UPDATE cts_batches SET total_cheques-=1, total_amount-=amount]
     * </pre>
     *
     * @param chequeId the primary key of the cheque to delete (DB column: id)
     */
    void deleteCheque(long chequeId);

    // ══════════════════════════════════════════════════════════════════════
    // EXTERNAL SERVICE OPERATIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Looks up live account details from the Core Banking System (CBS)
     * via Firebase Firestore and returns them as a flat String array.
     *
     * <p>The returned array is consumed directly by
     * {@code BatchDetailComposer} to auto-populate account-related fields
     * in the cheque edit form (account holder name, account type,
     * IFSC, branch name, account issuance date, etc.).
     *
     * <p>Array index contract (defined by {@link CBSService}):
     * <pre>
     *   [0]  accountHolderName
     *   [1]  accountType         (e.g. "Savings", "Current")
     *   [2]  ifscCode
     *   [3]  branchName
     *   [4]  issuedDate          (ISO-8601 string, used to compute "Account Is New")
     *   [5]  accountStatus       (e.g. "Active", "Dormant")
     *   [6]  isAccountNew        ("Yes" if issuedDate within last 90 days, else "No")
     * </pre>
     *
     * <p>If the account is not found in Firebase or the CBS is unreachable,
     * the array is returned with empty strings (never null) so the Maker
     * can still fill in fields manually.
     *
     * <h3>Called by</h3>
     * {@code BatchDetailComposer.onLookupAccount()} — Maker clicks the
     * "Fetch Account" button after entering an account number.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.onLookupAccount()
     *   → ChequeService.lookupAccount(accountNo)
     *       → ChequeServiceImpl.lookupAccount()
     *           → CBSService.getAccountDetails(accountNo)
     *               → Firebase Firestore REST query
     *               → returns account document fields
     *           → derives isAccountNew from issuedDate (< 90 days = "Yes")
     *           → returns String[7] of account fields
     * </pre>
     *
     * @param accountNo the bank account number to look up (as entered by Maker)
     * @return String array of account fields (length 7); fields are empty strings
     *         if account not found or CBS unreachable — never returns null
     */
    String[] lookupAccount(String accountNo);

    // ══════════════════════════════════════════════════════════════════════
    // VERIFICATION QUEUE OPERATIONS
    // Author : Anusha M. (V1) / Girinath (V2)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns all cheques routed to the given verification level and currently
     * sitting in the given status — the queue a Verifier screen renders.
     *
     * <p>Thin pass-through to {@link com.cts.outward.dao.ChequeDAO#loadChequesByVerLevel}.
     * Kept in the service layer so composers never call the DAO directly.
     *
     * <h3>Called by</h3>
     * {@code VerificationOneComposer.loadBatchList()} — verLevel="V1", status="V1_PENDING"
     * {@code VerificationTwoComposer.loadBatchList()} — verLevel="V2", status="V2_PENDING"
     *
     * @param verLevel routing bucket — "V1" or "V2"
     * @param status   cheque status to match — use {@link com.cts.outward.enums.ChequeStatus#db()}
     * @return matching cheques (no BLOB images); empty list if none
     */
    List<ChequeEntity> getChequesByVerLevel(String verLevel, String status);

    /**
     * Persists a Verifier's action (Accept / Reject / Send Back) on a single cheque.
     * Thin pass-through to {@link com.cts.outward.dao.ChequeDAO#applyVerifierAction}.
     *
     * <h3>Called by</h3>
     * {@code VerificationOneComposer.onDlgAccept/onDlgReject/onDlgSendBack()}
     *
     * @param chequeId   cheque PK
     * @param status     new cheque status — use {@link com.cts.outward.enums.ChequeStatus#db()}
     * @param verLevel   routing bucket — "V1" or "V2"
     * @param verAction  action taken — "Accept" / "Reject" / "SendBack"
     * @param verBy      username of the verifier
     * @param verRemarks reason (mandatory for Reject / Send Back)
     */
    void applyVerifierAction(Long chequeId, String status, String verLevel,
            String verAction, String verBy, String verRemarks);

    /**
     * V1 → V2 escalation (Refer).
     * Sets status/ver_status = 'V2_PENDING', ver_level = 'V2', is_referred = true.
     * Thin pass-through to {@link com.cts.outward.dao.ChequeDAO#referToVerificationTwo}.
     *
     * @param chequeId   cheque PK
     * @param verBy      V1 username performing the refer
     * @param verRemarks reason (mandatory in UI)
     */
    void referToVerificationTwo(Long chequeId, String verBy, String verRemarks);
}