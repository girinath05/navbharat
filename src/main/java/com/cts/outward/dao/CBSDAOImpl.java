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