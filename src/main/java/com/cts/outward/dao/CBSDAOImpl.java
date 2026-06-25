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

// Jackson tree-model node — used to navigate Firestore's nested JSON envelope
import com.fasterxml.jackson.databind.JsonNode;
// Deserializes the raw HTTP response body (String) into a traversable JsonNode tree
import com.fasterxml.jackson.databind.ObjectMapper;

// Converts the Firestore REST URL string into a typed URI for HttpRequest
import java.net.URI;
// Java 11+ built-in HTTP client — sends blocking HTTP requests without external libs
import java.net.http.HttpClient;
// Immutable HTTP request builder — sets URI, method (GET), and headers
import java.net.http.HttpRequest;
// Typed HTTP response container — holds status code + body string
import java.net.http.HttpResponse;
// JUL logger — logs errors without crashing the caller (fail-closed pattern)
import java.util.logging.Logger;

// Implements CBSDAO — all CBS data access goes through this class
public class CBSDAOImpl implements CBSDAO {

    // Class-level logger; name tied to class so log output identifies source precisely
    private static final Logger LOG = Logger.getLogger(CBSDAOImpl.class.getName());

    // Root REST URL for the Firestore project "express-clear-cbs", default database
    // All collection/document paths are appended to this base (e.g. + "/accounts/1234567890")
    private static final String FIRESTORE_BASE_URL =
        "https://firestore.googleapis.com/v1/projects/express-clear-cbs/databases/(default)/documents";

    // HttpClient: thread-safe, reused across all calls — avoids connection setup overhead per request
    private final HttpClient   httpClient  = HttpClient.newHttpClient();
    // ObjectMapper: thread-safe after construction — shared to avoid repeated instantiation cost
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
            // Build a GET request to: .../documents/accounts/{accountNumber}
            // Firestore REST: GET on a document URL returns the full document JSON
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/accounts/" + accountNumber))
                .GET()
                .build();

            // Send synchronously; body handler collects response as a plain String
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            // Firestore returns 200 for found, 404 for missing — any non-200 = not usable
            if (res.statusCode() != 200) {
                return null; // caller treats null as "account not found"
            }

            // Parse JSON body and navigate directly to "fields" node.
            // Firestore envelope: { "name": "...", "fields": { ... }, "createTime": "..." }
            // Returning only "fields" hides the envelope from callers — they work with field map directly
            return jsonMapper.readTree(res.body()).path("fields");

        } catch (Exception ex) {
            // Network timeout, DNS failure, JSON parse error — log and return null (fail-closed)
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
            // Same URL as getAccountDetails — we only care about HTTP status, not the body
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/accounts/" + accountNumber))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            // HTTP 200 = document exists; 404 = no such account; anything else = treat as absent
            return res.statusCode() == 200;

        } catch (Exception ex) {
            // Any exception (network/timeout) = fail-closed: assume account does NOT exist
            LOG.severe("CBSDaoImpl.isAccountExists error for account=" + accountNumber + ": " + ex.getMessage());
            return false; // fail closed — deny on uncertainty, never approve blindly
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
            // Firestore document ID for cheques is composite: "{accountNumber}_{chequeNumber}"
            // e.g. account "1234567890", cheque "000123" → docId = "1234567890_000123"
            String docId = accountNumber + "_" + chequeNumber;

            // Build GET request to: .../documents/cheques/{accountNumber}_{chequeNumber}
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/cheques/" + docId))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            // 200 = cheque document found; anything else = cheque not registered in CBS
            return res.statusCode() == 200;

        } catch (Exception ex) {
            // Fail-closed: on any error, report cheque as non-existent (safer than assuming valid)
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
            // Same composite key as isChequeExists — consistent document ID convention
            String docId = accountNumber + "_" + chequeNumber;

            // GET the cheque document from Firestore /cheques collection
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FIRESTORE_BASE_URL + "/cheques/" + docId))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            // Non-200 means cheque document absent — not an error, just not registered
            if (res.statusCode() != 200) {
                return "NOT_FOUND"; // sentinel: cheque not in CBS (distinct from ERROR)
            }

            // Navigate Firestore envelope: body → "fields" → "status" → "stringValue"
            // Example body: { "fields": { "status": { "stringValue": "ACTIVE" } } }
            // asText("UNKNOWN") — safe default if "status" field missing from document
            return jsonMapper.readTree(res.body())
                .path("fields")   // unwrap Firestore envelope
                .path("status")   // locate the status field map
                .path("stringValue") // extract the actual string value from Firestore type wrapper
                .asText("UNKNOWN"); // "UNKNOWN" if path missing (field absent but doc exists)

        } catch (Exception ex) {
            // Network/parse failure — return "ERROR" sentinel so caller can distinguish
            // "cheque not found" (NOT_FOUND) from "CBS unreachable" (ERROR)
            LOG.severe("CBSDaoImpl.getChequeStatus error for cheque=" + accountNumber + "_" + chequeNumber + ": " + ex.getMessage());
            return "ERROR"; // fail-closed: caller must treat ERROR as unverifiable
        }
    }
}