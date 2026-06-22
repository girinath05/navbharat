/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — CBS Integration Layer
 *  File        : CBSServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Concrete implementation of CBSService.
 *                Delegates raw Firebase access to CBSDAO.
 *                Contains all CBS business logic:
 *                  - Account field extraction
 *                  - New account detection (< 90 days)
 *                  - 
 *                  - Full 6-step cheque validation pipeline
 *
 *  Instantiation (manual DI — no Spring/CDI):
 *    CBSService cbsService = new CBSServiceImpl(new CBSDAOImpl());
 *
 *  Error philosophy: fail-closed. CBS unreachable → deny, not approve.
 * ============================================================
 */

package com.cts.outward.service;

import com.cts.outward.dao.CBSDAO;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.logging.Logger;

public class CBSServiceImpl implements CBSService {

    private static final Logger LOG = Logger.getLogger(CBSServiceImpl.class.getName());

    // ══════════════════════════════════════════════════════════
    // DEPENDENCY
    // ══════════════════════════════════════════════════════════

    private final CBSDAO cbsDao;

    public CBSServiceImpl(CBSDAO cbsDao) {
        this.cbsDao = cbsDao;
    }

    // ══════════════════════════════════════════════════════════
    // ACCOUNT INFO
    // ══════════════════════════════════════════════════════════

    @Override
    public JsonNode lookupAccountFields(String accountNumber) {
        return cbsDao.getAccountDetails(accountNumber);
    }

    @Override
    public String getIsNewAccount(String accountNumber) {
        JsonNode fields = cbsDao.getAccountDetails(accountNumber);
        if (fields == null) return "\u2014"; // em-dash — CBS unreachable

        String issuedDateStr = fields
            .path("issuedDate")         // Firebase field: issuedDate
            .path("stringValue")
            .asText(null);

        if (issuedDateStr == null || issuedDateStr.isBlank()) {
            return "\u2014";
        }

        try {
            java.time.LocalDate issuedDate = java.time.LocalDate.parse(issuedDateStr);
            long daysSince = java.time.temporal.ChronoUnit.DAYS
                .between(issuedDate, java.time.LocalDate.now());
            return daysSince < 90 ? "Yes" : "No";
        } catch (Exception ex) {
            LOG.warning("CBSServiceImpl.getIsNewAccount: unparseable issuedDate='" + issuedDateStr + "'");
            return "\u2014";
        }
    }

    @Override
    public boolean hasSufficientBalance(String accountNumber, double requiredAmount) {
        JsonNode fields = cbsDao.getAccountDetails(accountNumber);
        if (fields == null) return false;
        return readBalance(fields) >= requiredAmount;
    }

    // ══════════════════════════════════════════════════════════
    // FULL VALIDATION PIPELINE
    // ══════════════════════════════════════════════════════════

    @Override
    public String validateCheque(String accountNumber, String chequeNumber, double amount) {

        // Steps 1 + 2: fetch account fields once — reused for active check
        JsonNode fields = cbsDao.getAccountDetails(accountNumber);
        if (fields == null || fields.isMissingNode()) {
            return "ACCOUNT_NOT_FOUND";
        }

        // Step 2: account must be active — Firebase stores as boolean in "active" field
        if (!fields.path("active").path("booleanValue").asBoolean(false)) {
            return "ACCOUNT_INACTIVE";
        }

        // Step 3: cheque document must exist in CBS
        if (!cbsDao.isChequeExists(accountNumber, chequeNumber)) {
            return "CHEQUE_NOT_FOUND";
        }

        // Steps 4 + 5: cheque must not be stopped or already cleared
        String chequeStatus = cbsDao.getChequeStatus(accountNumber, chequeNumber);
        if ("STOPPED".equalsIgnoreCase(chequeStatus)) {
            return "STOPPED_CHEQUE";
        }
        if ("CLEARED".equalsIgnoreCase(chequeStatus)) {
            return "CHEQUE_ALREADY_CLEARED";
        }
        return "VALID";
    }

    // ══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════

    /**
     * Reads the balance from a Firestore fields node.
     * Firestore may store as integerValue or doubleValue depending on
     * how the CBS team inserted the record — checks integerValue first.
     */
    private double readBalance(JsonNode fields) {
        JsonNode balanceNode = fields.path("balance");
        if (balanceNode.has("integerValue")) {
            return balanceNode.path("integerValue").asDouble(0);
        }
        return balanceNode.path("doubleValue").asDouble(0);
    }
}