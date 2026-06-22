/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — CBS Integration Layer
 *  File        : CBSService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Service interface for Core Banking System (CBS)
 *                business operations.
 *
 *                Delegates raw Firebase access to CBSDao.
 *                Provides:
 *                  - Account lookups for Maker form (lookupAccountFields)
 *                  - Account age check (getIsNewAccount)
 *                  - Balance check (hasSufficientBalance)
 *                  - Full cheque validation pipeline (validateCheque)
 *                    used by V1 and V2 verification popups.
 *
 *  Called by:
 *    ChequeServiceImpl   — lookupAccountFields() for Maker CBS panel
 *    VerificationOneComposer — validateCheque() on Accept
 *    VerificationTwoComposer — validateCheque() on Accept
 * ============================================================
 */

package com.cts.outward.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface CBSService {

    // ══════════════════════════════════════════════════════════
    // ACCOUNT INFO
    // ══════════════════════════════════════════════════════════

    /**
     * Returns the Firestore "fields" node for the given account.
     * Thin delegation to CBSDao.getAccountDetails().
     * Callers use Firestore envelope pattern to read fields.
     *
     * @param accountNumber bank account number
     * @return JsonNode of fields, or null if not found / CBS down
     */
    JsonNode lookupAccountFields(String accountNumber);

    /**
     * Returns whether the account was opened within the last 90 days.
     * Reads issuedDate field (ISO-8601) from Firestore.
     *
     * Return values:
     *   "Yes" — issuedDate within last 90 days
     *   "No"  — issuedDate 90+ days ago
     *   "—"   — field missing, blank, unparseable, or CBS down
     *
     * @param accountNumber bank account number
     * @return "Yes", "No", or "—" — never null
     */
    String getIsNewAccount(String accountNumber);

    /**
     * Returns true if account balance >= requiredAmount.
     * Returns false if CBS is unreachable (balance = 0, always insufficient).
     *
     * @param accountNumber  account to check
     * @param requiredAmount minimum amount required
     * @return true if balance sufficient
     */
    boolean hasSufficientBalance(String accountNumber, double requiredAmount);

    // ══════════════════════════════════════════════════════════
    // FULL VALIDATION PIPELINE
    // ══════════════════════════════════════════════════════════

    /**
     * Runs the full cheque validation pipeline against CBS data.
     * Returns the first failure code, or "VALID" if all checks pass.
     *
     * Validation sequence (short-circuit on first failure):
     *   1. Account exists          → "ACCOUNT_NOT_FOUND"
     *   2. Account active          → "ACCOUNT_INACTIVE"
     *   3. Cheque exists in CBS    → "CHEQUE_NOT_FOUND"
     *   4. Cheque not stopped      → "STOPPED_CHEQUE"
     *   5. Cheque not cleared      → "CHEQUE_ALREADY_CLEARED"
     *   6. Balance >= amount       → "INSUFFICIENT_FUNDS"
     *   All pass                   → "VALID"
     *
     * Steps 1+2+6 share a single HTTP call (account fields fetched once).
     * Steps 3+4 require two more HTTP calls to the cheques collection.
     *
     * Called by:
     *   VerificationOneComposer.onDlgAccept()
     *   VerificationTwoComposer.onDlgAccept()
     *
     * @param accountNumber bank account number drawn on cheque
     * @param chequeNumber  cheque serial number
     * @param amount        cheque amount in INR
     * @return result code string — never null
     */
    String validateCheque(String accountNumber, String chequeNumber, double amount);
}