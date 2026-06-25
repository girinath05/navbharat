/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — CBS Integration Layer
 *  File        : CBSDaoImpl.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Firebase Firestore REST implementation of CBSDao.
 *
 *                HttpClient and ObjectMapper are instance fields
 *                (thread-safe, expensive to create — one instance
 *                per CBSDaoImpl, shared across all calls).
 *
 *                All methods are fail-closed: on Firebase outage
 *                they return safe defaults (null / false / "ERROR")
 *                rather than throwing, so the caller can deny
 *                rather than approve on uncertainty.
 *
 *  Firestore document structure:
 *    Collection: accounts
 *      Document ID: accountNumber (e.g. "1234567890")
 *      Fields: accountHolderName, active, balance, issuedDate
 *
 *    Collection: cheques
 *      Document ID: "{accountNumber}_{chequeNumber}"
 *      Fields: status ("ACTIVE" | "STOPPED" | "CLEARED")
 *
 *  Firestore wraps every field in a type envelope:
 *    { "fields": { "accountHolderName": { "stringValue": "RAMESH KUMAR" } } }
 *
 *  Balance field may be integerValue or doubleValue — both checked.
 * ============================================================
 */

package com.cts.outward.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Logger;

public class CBSDAOImpl implements CBSDAO {

    private static final Logger LOG = Logger.getLogger(CBSDAOImpl.class.getName());

    private static final String FIRESTORE_BASE_URL =
        "https://firestore.googleapis.com/v1/projects/express-clear-cbs/databases/(default)/documents";

    // Thread-safe singletons — expensive to create, safe to share.
    private final HttpClient   httpClient  = HttpClient.newHttpClient();
    private final ObjectMapper jsonMapper  = new ObjectMapper();

    // ══════════════════════════════════════════════════════════
    // ACCOUNT QUERIES
    // ══════════════════════════════════════════════════════════

    /**
     * Service method: getAccountDetails
     *
     * GET /accounts/{accountNumber} from Firestore REST.
     * Returns the "fields" node of the Firestore document on HTTP 200.
     * Callers read typed values via Firestore envelope:
     *   fields.path("accountHolderName").path("stringValue").asText()
     *   fields.path("active").path("booleanValue").asBoolean(false)
     *   fields.path("balance").path("integerValue").asDouble(0)
     * Fail-closed: returns null on non-200 or any exception.
     *
     * Called by: CBSServiceImpl.getAccountDetails() → BatchDetailComposer (account lookup)
     */
    @Override
    public JsonNode getAccountDetails(String accountNumber) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/accounts/" + accountNumber))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                return null;
            }

            return jsonMapper.readTree(res.body()).path("fields");

        } catch (Exception ex) {
            LOG.severe("CBSDaoImpl.getAccountDetails error for account=" + accountNumber + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Service method: isAccountExists
     *
     * GET /accounts/{accountNumber} — checks HTTP status only, ignores body.
     * HTTP 200 → account exists → true.
     * Any non-200 or exception → false (fail-closed: deny on uncertainty).
     *
     * Called by: CBSServiceImpl.isAccountExists() → BatchDetailComposer (pre-entry validation)
     */
    @Override
    public boolean isAccountExists(String accountNumber) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/accounts/" + accountNumber))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;

        } catch (Exception ex) {
            LOG.severe("CBSDaoImpl.isAccountExists error for account=" + accountNumber + ": " + ex.getMessage());
            return false; // fail closed
        }
    }

    // ══════════════════════════════════════════════════════════
    // CHEQUE QUERIES
    // ══════════════════════════════════════════════════════════

    /**
     * Service method: isChequeExists
     *
     * GET /cheques/{accountNumber}_{chequeNumber} — checks HTTP status only.
     * Document ID constructed as "{accountNumber}_{chequeNumber}" per Firestore convention.
     * HTTP 200 → cheque document exists → true.
     * Any non-200 or exception → false (fail-closed).
     *
     * Called by: CBSServiceImpl.isChequeExists() → BatchDetailComposer (cheque validation)
     */
    @Override
    public boolean isChequeExists(String accountNumber, String chequeNumber) {
        try {
            String docId = accountNumber + "_" + chequeNumber;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/cheques/" + docId))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;

        } catch (Exception ex) {
            LOG.severe("CBSDaoImpl.isChequeExists error for cheque=" + accountNumber + "_" + chequeNumber + ": " + ex.getMessage());
            return false; // fail closed
        }
    }

    /**
     * Service method: getChequeStatus
     *
     * GET /cheques/{accountNumber}_{chequeNumber} — reads status field from Firestore envelope.
     * Path: response.fields.status.stringValue
     * Return values:
     *   "ACTIVE"    — valid and in circulation
     *   "STOPPED"   — stop payment issued
     *   "CLEARED"   — already presented
     *   "NOT_FOUND" — HTTP non-200 (document absent)
     *   "UNKNOWN"   — document found but status field missing (asText fallback)
     *   "ERROR"     — network or parse exception
     * Never returns null.
     *
     * Called by: CBSServiceImpl.getChequeStatus() → BatchDetailComposer (stop-payment check)
     */
    @Override
    public String getChequeStatus(String accountNumber, String chequeNumber) {
        try {
            String docId = accountNumber + "_" + chequeNumber;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/cheques/" + docId))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                return "NOT_FOUND";
            }

            return jsonMapper.readTree(res.body())
                .path("fields")
                .path("status")
                .path("stringValue")
                .asText("UNKNOWN");

        } catch (Exception ex) {
            LOG.severe("CBSDaoImpl.getChequeStatus error for cheque=" + accountNumber + "_" + chequeNumber + ": " + ex.getMessage());
            return "ERROR";
        }
    }
}