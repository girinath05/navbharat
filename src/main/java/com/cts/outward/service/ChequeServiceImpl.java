/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ChequeServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Concrete implementation of {@link ChequeService}.
 *
 *  Responsibilities:
 *    1. Load cheques for a batch from the DB via ChequeDAO.
 *    2. Persist all Maker field edits (MICR data, payee account,
 *       amount, cheque date) via ChequeDAO.updateChequeFields().
 *    3. Look up live account details from the Core Banking System
 *       (CBS) via Firebase Firestore through CBSService, and
 *       derive the "Account Is New" flag from issuedDate.
 *    4. Delete a single cheque and atomically decrement the
 *       parent batch's control totals (cheque count + amount)
 *       within a single Hibernate transaction.
 *    5. Count cheques in "Pending" status for the dashboard
 *       stat card.
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS CLASS FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Composer Layer]          [This Class]              [DAO / External]
 *  ────────────────────────  ────────────────────────  ──────────────────────────
 *  BatchDetailComposer                                 ChequeDAO / ChequeDAOImpl
 *  BatchChequeEntryComposer  ──► ChequeServiceImpl ──► CBSService → Firebase
 *  VerificationOneComposer                             HibernateUtil (deleteCheque only)
 *  VerificationTwoComposer
 *
 * ──────────────────────────────────────────────────────────────
 *  CBS ACCOUNT LOOKUP — RETURN VALUE CONTRACT
 * ──────────────────────────────────────────────────────────────
 *  lookupAccount() always returns a String[3] — never null:
 *
 *    Index  Field               Happy path          Not-found / Error
 *    ─────  ──────────────────  ──────────────────  ─────────────────
 *    [0]    accountHolderName   "RAMESH KUMAR"      "Not found"
 *    [1]    accountStatus       "Active"/"Inactive" "—"  (em-dash)
 *    [2]    isAccountNew        "Yes" / "No"        "—"  (em-dash)
 *
 *  The "Account Is New" flag ([2]) is derived by CBSService
 *  from the account's issuedDate field:
 *    issuedDate within last 90 days → "Yes"
 *    older than 90 days             → "No"
 *
 *  BatchDetailComposer reads these by index to populate the
 *  cheque edit form's read-only CBS fields.
 *
 * ──────────────────────────────────────────────────────────────
 *  DELETE TRANSACTION DESIGN
 * ──────────────────────────────────────────────────────────────
 *  deleteCheque() opens its OWN Hibernate session and transaction
 *  rather than going through ChequeDAO. This is intentional:
 *  the delete requires two operations in a single atomic unit:
 *
 *    1. session.remove(cheque)         — DELETE FROM cts_cheques
 *    2. native UPDATE cts_batches      — decrement count + amount
 *
 *  Using a native mutation query for step 2 avoids loading the
 *  full BatchEntity object just to modify two columns. GREATEST(0,…)
 *  guards prevent negative values if data is inconsistent.
 *
 * ──────────────────────────────────────────────────────────────
 *  DESIGN NOTE — saveChequeFields()
 * ──────────────────────────────────────────────────────────────
 *  This method calls ChequeDAO.updateChequeFields() — NOT
 *  ChequeDAO.saveOrUpdate(). This distinction is important:
 *  updateChequeFields() uses a targeted HQL UPDATE that writes
 *  only Maker-editable columns and deliberately excludes
 *  verLevel/verStatus (which are set by BatchServiceImpl.submitBatch()
 *  and must not be overwritten by Maker edits after submission).
 *
 * ──────────────────────────────────────────────────────────────
 *  INSTANTIATION (manual DI — no Spring/CDI)
 * ──────────────────────────────────────────────────────────────
 *  new ChequeServiceImpl(new ChequeDAOImpl())
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.dao.CBSDAOImpl;
import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.util.HibernateUtil;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Concrete implementation of {@link ChequeService}.
 *
 * <p>Delegates reads and field-level writes to {@link ChequeDAO}.
 * Performs CBS account lookup via {@link CBSService} (Firebase Firestore).
 * Manages cheque deletion in its own Hibernate transaction to atomically
 * update parent batch control totals alongside the delete.
 *
 * @author Umesh M.
 * @see ChequeService
 * @see com.cts.outward.dao.ChequeDAOImpl
 * @see CBSService
 */
public class ChequeServiceImpl implements ChequeService {

    // ══════════════════════════════════════════════════════════════════════
    // DEPENDENCY (injected via constructor)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * DAO for all {@code cts_cheques} table read/write operations.
     * Injected at construction; never re-created inside methods.
     * Exception: {@link #deleteCheque(long)} opens its own Hibernate session
     * to run a two-step atomic transaction (cheque delete + batch decrement).
     */
    private final ChequeDAO   chequeDAO;
    private final CBSService  cbsService;

    // ══════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Constructs a ChequeServiceImpl with the required DAO dependency.
     *
     * <p>Standard usage:
     * <pre>
     *   ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());
     * </pre>
     *
     * @param chequeDAO DAO for cheque persistence and query operations
     */
    public ChequeServiceImpl(ChequeDAO chequeDAO) {
        this.chequeDAO  = chequeDAO;
        this.cbsService = new CBSServiceImpl(new CBSDAOImpl());
    }

    // ══════════════════════════════════════════════════════════════════════
    // READ OPERATIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns all cheques belonging to the given batch, ordered by cheque
     * sequence number ascending.
     *
     * <p>Thin delegation to {@code ChequeDAO.loadChequesForBatch()} — no
     * business logic applied at this level. Results are rendered directly
     * by the calling composer into the cheque table listbox.
     *
     * <h3>Called by</h3>
     * <ul>
     *   <li>{@code BatchDetailComposer.loadChequesForBatch()} — initial load
     *       and after every save/delete to refresh the table</li>
     *   <li>{@code BatchChequeEntryComposer.loadChequesForBatch()} — ghost-row
     *       guard in the Scan Module filter (checks if a VerificationInProgressAtMaker
     *       batch has zero cheques)</li>
     * </ul>
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.loadChequesForBatch()
     *   → ChequeServiceImpl.getChequesForBatch(batchId)
     *       → ChequeDAOImpl.loadChequesForBatch(batchId)
     *           [SELECT * FROM cts_cheques WHERE batch_id = ? ORDER BY seq ASC]
     * </pre>
     *
     * @param batchId the parent batch ID (e.g. "BATCH0042")
     * @return ordered list of {@link ChequeEntity}; empty list if none or null batchId
     */
    @Override
    public List<ChequeEntity> getChequesForBatch(String batchId) {
        return chequeDAO.loadChequesForBatch(batchId);
    }

    /**
     * Returns the count of cheques in "Pending" verification status across
     * all batches — used to populate the Scan Module dashboard stat card.
     *
     * <h3>Called by</h3>
     * {@code BatchChequeEntryComposer.refreshStats()} — after every import,
     * batch create, and batch discard.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchChequeEntryComposer.refreshStats()
     *   → ChequeServiceImpl.countPending()
     *       → ChequeDAOImpl.countPendingCheques()
     *           [SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending']
     * </pre>
     *
     * @return total count of Pending cheques; 0 on DB error
     */
    @Override
    public long countPending() {
        return chequeDAO.countPendingCheques();
    }

    // ══════════════════════════════════════════════════════════════════════
    // WRITE OPERATIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists the Maker's field edits for a single cheque by delegating to
     * {@code ChequeDAO.updateChequeFields()}.
     *
     * <h3>Why updateChequeFields() and not saveOrUpdate()</h3>
     * {@code ChequeDAO.updateChequeFields()} issues a targeted HQL UPDATE that
     * writes only Maker-editable columns (MICR fields, payeeAccountNo, amount,
     * cheque date, status, etc.) and explicitly excludes {@code verLevel} and
     * {@code verStatus}. This prevents a Maker save after submission from
     * accidentally overwriting the verification routing set by
     * {@code BatchServiceImpl.submitBatch()}.
     *
     * <h3>Called by</h3>
     * {@code BatchDetailComposer.onSaveChequeFields()} — Maker clicks "Save"
     * on the cheque edit inline form in the Batch Detail screen.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.onSaveChequeFields()
     *   → ChequeServiceImpl.saveChequeFields(cheque)
     *       → ChequeDAOImpl.updateChequeFields(cheque)
     *           [UPDATE cts_cheques SET micr_data=?, amount=?, status=?, ...
     *            WHERE id=?    -- verLevel and verStatus NOT included]
     * </pre>
     *
     * @param cheque {@link ChequeEntity} with all Maker edits applied by the
     *               calling composer; must have a valid non-null {@code id}
     */
    @Override
    public void saveChequeFields(ChequeEntity cheque) {
        chequeDAO.updateChequeFields(cheque);
    }

    /**
     * Deletes a cheque and atomically decrements the parent batch's cheque
     * count and total amount within a single Hibernate transaction.
     *
     * <h3>Transaction design</h3>
     * This method manages its own Hibernate session (via {@link HibernateUtil})
     * rather than delegating to the DAO. This is required because the operation
     * spans two separate DB mutations that must commit or roll back together:
     * <ol>
     *   <li>{@code session.remove(cheque)} — deletes the cheque row</li>
     *   <li>Native UPDATE on {@code cts_batches} — decrements total_cheques
     *       by 1 and subtracts the cheque amount from control_amount</li>
     * </ol>
     *
     * <h3>GREATEST(0, …) guard</h3>
     * The native UPDATE uses {@code GREATEST(0, total_cheques - 1)} and
     * {@code GREATEST(0, control_amount - :amt)} to prevent negative values
     * in the unlikely event of data inconsistency (e.g. a cheque was counted
     * twice). This is a safety net — normal operation will never trigger it.
     *
     * <h3>Called by</h3>
     * {@code BatchDetailComposer.onDeleteCheque()} — Maker clicks the delete
     * icon on a cheque row in the Batch Detail screen.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.onDeleteCheque(chequeId)
     *   → ChequeServiceImpl.deleteCheque(chequeId)
     *       → HibernateUtil.getSession()              [open session]
     *       → session.beginTransaction()
     *       → session.get(ChequeEntity.class, chequeId)  [load to get batchId + amount]
     *       → session.remove(cheque)                  [DELETE FROM cts_cheques WHERE id=?]
     *       → session.createNativeMutationQuery(...)   [UPDATE cts_batches SET ...]
     *       → session.getTransaction().commit()
     *   (on any exception → rollback → throw RuntimeException)
     * </pre>
     *
     * @param chequeId the primary key ({@code id} column) of the cheque to delete
     * @throws RuntimeException wrapping the underlying exception if the transaction fails
     */
    @Override
    public void deleteCheque(long chequeId) {
        try (org.hibernate.Session hibernateSession = HibernateUtil.getSession()) {
            hibernateSession.beginTransaction();

            // Step 1: Load cheque entity to retrieve batchId and amount before deleting.
            // Both are needed for the batch decrement UPDATE that follows.
            ChequeEntity chequeToDelete = hibernateSession.get(ChequeEntity.class, chequeId);
            if (chequeToDelete == null) {
                // Cheque not found — nothing to delete; roll back the empty transaction
                hibernateSession.getTransaction().rollback();
                return;
            }

            String parentBatchId = chequeToDelete.getBatchId();
            BigDecimal chequeAmount = chequeToDelete.getAmount() != null
                ? chequeToDelete.getAmount()
                : BigDecimal.ZERO;

            // Step 2: Delete the cheque row from cts_cheques
            hibernateSession.remove(chequeToDelete);

            // Step 3: Decrement parent batch control totals.
            // Native SQL used to avoid loading the full BatchEntity just to modify
            // two columns. GREATEST(0, …) prevents negative values on data inconsistency.
            hibernateSession.createNativeMutationQuery(
                "UPDATE cts_batches " +
                "SET total_cheques  = GREATEST(0, total_cheques - 1), " +
                "    control_amount = GREATEST(0, control_amount - :chequeAmount) " +
                "WHERE batch_id = :parentBatchId")
                .setParameter("chequeAmount", chequeAmount)
                .setParameter("parentBatchId", parentBatchId)
                .executeUpdate();

            // Commit both operations atomically
            hibernateSession.getTransaction().commit();

        } catch (Exception transactionException) {
            transactionException.printStackTrace();
            throw new RuntimeException(
                "Failed to delete cheque #" + chequeId + ": " + transactionException.getMessage(),
                transactionException
            );
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXTERNAL SERVICE OPERATIONS — CBS / FIREBASE LOOKUP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Looks up live account details from the Core Banking System (CBS) via
     * Firebase Firestore and returns a flat String array for the cheque edit form.
     *
     * <h3>Return value — String[3] index contract</h3>
     * <pre>
     *   [0]  accountHolderName  — "RAMESH KUMAR"   or "Not found"
     *   [1]  accountStatus      — "Active"/"Inactive"  or "—" (em-dash)
     *   [2]  isAccountNew       — "Yes"/"No"           or "—" (em-dash)
     * </pre>
     * Never returns null — always returns a 3-element array so
     * {@code BatchDetailComposer} can safely read by index without null checks.
     *
     * <h3>"Account Is New" derivation</h3>
     * {@code CBSService.getIsNewAccount()} reads the {@code issuedDate} field
     * from the Firebase document and compares it to today:
     * <ul>
     *   <li>issuedDate within the last 90 days → "Yes"</li>
     *   <li>issuedDate older than 90 days      → "No"</li>
     * </ul>
     *
     * <h3>Failure modes</h3>
     * Any exception (Firebase unreachable, account document missing, JSON parse
     * error) is caught here and results in the fallback array
     * {@code ["Not found", "—", "—"]} so the Maker can still proceed with
     * manual data entry.
     *
     * <h3>Called by</h3>
     * {@code BatchDetailComposer.onLookupAccount()} — triggered when the Maker
     * clicks "Fetch Account" after entering an account number in the cheque
     * edit form.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchDetailComposer.onLookupAccount()
     *   → ChequeServiceImpl.lookupAccount(accountNo)
     *       → CBSService.getAccountDetails(accountNo)
     *           → Firebase Firestore REST API
     *           → returns JsonNode of account document fields
     *       → extract accountHolderName from fields["accountHolderName"]["stringValue"]
     *       → extract active flag    from fields["active"]["booleanValue"]
     *       → CBSService.getIsNewAccount(accountNo)
     *           → reads issuedDate → compares to today → "Yes"/"No"
     *       → returns String[3]
     * </pre>
     *
     * @param accountNo the bank account number entered by the Maker
     * @return String[3] of account details; fallback values on not-found or error
     */
    @Override
    public String[] lookupAccount(String accountNo) {
        try {
            // Query Firebase Firestore via CBSService for the account document
            JsonNode accountFields = cbsService.lookupAccountFields(accountNo);

            // Guard: missing or null document → account does not exist in CBS
            if (accountFields == null || accountFields.isMissingNode()) {
                return new String[]{ "Not found", "\u2014", "\u2014" };
            }

            // Extract account holder name — primary identifier for the form
            String accountHolderName = accountFields
                .path("accountHolderName")
                .path("stringValue")
                .asText(null);

            if (accountHolderName == null || accountHolderName.isBlank()) {
                // Document exists but holder name is absent — treat as not found
                return new String[]{ "Not found", "\u2014", "\u2014" };
            }

            // Derive account status label from boolean "active" field
            boolean isAccountActive = accountFields
                .path("active")
                .path("booleanValue")
                .asBoolean(false);
            String accountStatusLabel = isAccountActive ? "Active" : "Inactive";

            // Derive "Account Is New" flag from issuedDate via CBSService
            // (< 90 days from today → "Yes", otherwise → "No")
            String isAccountNew = cbsService.getIsNewAccount(accountNo);

            return new String[]{ accountHolderName, accountStatusLabel, isAccountNew };

        } catch (Exception cbsLookupException) {
            // CBS unreachable or JSON parse failure — return fallback so Maker
            // can still enter data manually without the form crashing
            return new String[]{ "Not found", "\u2014", "\u2014" };
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // VERIFICATION QUEUE OPERATIONS — thin pass-through to ChequeDAO
    // Added by: Anusha M.
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public List<ChequeEntity> getChequesByVerLevel(String verLevel, String status) {
        return chequeDAO.loadChequesByVerLevel(verLevel, status);
    }

    @Override
    public void applyVerifierAction(Long chequeId, String status, String verLevel,
            String verAction, String verBy, String verRemarks) {
        chequeDAO.applyVerifierAction(chequeId, status, verLevel, verAction, verBy, verRemarks);
    }

    @Override
    public void referToVerificationTwo(Long chequeId, String verBy, String verRemarks) {
        chequeDAO.referToVerificationTwo(chequeId, verBy, verRemarks);
    }
}