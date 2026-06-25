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

// JsonNode: Jackson tree-model type returned by getAccountDetails.
// Callers navigate the Firestore field envelope using .path() chains.
import com.fasterxml.jackson.databind.JsonNode;

/**
 * CBSDAO — Core Banking System Data Access interface.
 *
 * Defines the contract for all raw CBS lookups against Firestore.
 * No business logic here — only data retrieval primitives.
 * CBSDAOImpl provides the HTTP + JSON implementation.
 *
 * Callers (CBSService) depend on this interface, not the impl,
 * enabling mock injection in tests without a live Firestore connection.
 */
public interface CBSDAO {

    // ══════════════════════════════════════════════════════════
    // ACCOUNT QUERIES
    // ══════════════════════════════════════════════════════════

    /**
     * Fetches the Firestore "fields" node for the given account number.
     *
     * Firestore stores each document as:
     *   { "name": "...", "fields": { fieldName: { typeKey: value }, ... } }
     * This method returns only the inner "fields" map so callers don't
     * need to unwrap the envelope themselves.
     *
     * Caller usage pattern:
     *   JsonNode fields = dao.getAccountDetails("1234567890");
     *   if (fields == null) { // account not found or CBS unreachable }
     *   String name    = fields.path("accountHolderName").path("stringValue").asText();
     *   boolean active = fields.path("active").path("booleanValue").asBoolean(false);
     *   double balance = fields.path("balance").path("integerValue").asDouble(0);
     *
     * Returns null if:
     *   - Firestore returns HTTP non-200 (account absent / access denied)
     *   - Any network or JSON parse exception occurs (fail-closed)
     *
     * @param accountNumber the bank account number (Firestore document ID)
     * @return JsonNode of the document's "fields" object, or null on any failure
     */
    JsonNode getAccountDetails(String accountNumber);

    /**
     * Checks whether an account document exists in Firestore.
     *
     * Lighter than getAccountDetails — only inspects HTTP status code,
     * does not parse the response body. Use this when you only need
     * existence confirmation, not the actual field values.
     *
     * Fail-closed: returns false on any network/parse exception.
     * Callers should treat false as "cannot confirm existence" —
     * never grant access based on an ambiguous result.
     *
     * @param accountNumber the account number to check (Firestore document ID)
     * @return true if account document exists (HTTP 200); false if absent or error
     */
    boolean isAccountExists(String accountNumber);

    // ══════════════════════════════════════════════════════════
    // CHEQUE QUERIES
    // ══════════════════════════════════════════════════════════

    /**
     * Checks whether a cheque document exists in Firestore.
     *
     * Firestore document ID convention for cheques:
     *   "{accountNumber}_{chequeNumber}"
     *   e.g. account "1234567890", cheque "000123" → doc "1234567890_000123"
     *
     * Only checks HTTP 200/non-200 — does not parse body.
     * Use this before getChequeStatus to avoid parsing a 404 body.
     *
     * Fail-closed: returns false on any exception.
     *
     * @param accountNumber the account number the cheque belongs to
     * @param chequeNumber  the cheque serial number (MICR field)
     * @return true if cheque document exists (HTTP 200); false otherwise
     */
    boolean isChequeExists(String accountNumber, String chequeNumber);

    /**
     * Returns the status string of a cheque from Firestore.
     *
     * Reads the "status" → "stringValue" field from the cheque document.
     *
     * Return value contract (never returns null):
     *   "ACTIVE"     — cheque is valid and in circulation; can be presented
     *   "STOPPED"    — stop payment order issued by account holder; reject
     *   "CLEARED"    — cheque already presented once; reject as duplicate
     *   "NOT_FOUND"  — Firestore returned non-200 (cheque not registered in CBS)
     *   "UNKNOWN"    — document found but "status" field absent (data integrity issue)
     *   "ERROR"      — network timeout, DNS failure, or JSON parse exception
     *
     * Callers must distinguish "NOT_FOUND" (cheque absent) from "ERROR"
     * (CBS unreachable) to decide between rejection and retry/escalation.
     *
     * @param accountNumber the account number the cheque belongs to
     * @param chequeNumber  the cheque serial number (MICR field)
     * @return status string — one of ACTIVE / STOPPED / CLEARED / NOT_FOUND / UNKNOWN / ERROR
     */
    String getChequeStatus(String accountNumber, String chequeNumber);
}