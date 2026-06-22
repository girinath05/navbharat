/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — CBS Integration Layer
 *  File        : CBSDao.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : DAO interface for Core Banking System (CBS)
 *                data access via Firebase Firestore REST API.
 *                Implemented by CBSDaoImpl.
 *                Raw fetch operations only — no business logic.
 * ============================================================
 */

package com.cts.outward.dao;

import com.fasterxml.jackson.databind.JsonNode;

public interface CBSDAO {

    // ══════════════════════════════════════════════════════════
    // ACCOUNT QUERIES
    // ══════════════════════════════════════════════════════════

    /**
     * Fetches the Firestore "fields" node for the given account number.
     * Returns null if account not found (HTTP 404) or on any error.
     *
     * Callers read typed values using the Firestore envelope pattern:
     *   fields.path("accountHolderName").path("stringValue").asText()
     *   fields.path("active").path("booleanValue").asBoolean(false)
     *   fields.path("balance").path("integerValue").asDouble(0)
     *
     * @param accountNumber the bank account number
     * @return JsonNode of the document's "fields" object, or null
     */
    JsonNode getAccountDetails(String accountNumber);

    /**
     * Returns true if a Firestore document exists for the account (HTTP 200).
     * Fail-closed: returns false on any network/parse exception.
     *
     * @param accountNumber the account number to check
     * @return true if account document exists; false otherwise
     */
    boolean isAccountExists(String accountNumber);

    // ══════════════════════════════════════════════════════════
    // CHEQUE QUERIES
    // ══════════════════════════════════════════════════════════

    /**
     * Returns true if a cheque document exists in Firestore for the
     * given account + cheque number pair.
     *
     * Document ID convention: "{accountNumber}_{chequeNumber}"
     *
     * @param accountNumber the account number the cheque belongs to
     * @param chequeNumber  the cheque serial number
     * @return true if cheque document exists (HTTP 200); false otherwise
     */
    boolean isChequeExists(String accountNumber, String chequeNumber);

    /**
     * Returns the status string of a cheque from Firestore.
     *
     * Return values:
     *   "ACTIVE"     — valid and in circulation
     *   "STOPPED"    — stop payment issued
     *   "CLEARED"    — already presented
     *   "NOT_FOUND"  — HTTP non-200
     *   "UNKNOWN"    — document found but status field absent
     *   "ERROR"      — network or parse exception
     *
     * Never returns null.
     *
     * @param accountNumber the account number the cheque belongs to
     * @param chequeNumber  the cheque serial number
     * @return status string
     */
    String getChequeStatus(String accountNumber, String chequeNumber);
}