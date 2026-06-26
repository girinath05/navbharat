package com.cts.outward.service;

import com.cts.outward.dao.VerificationIIDAO;
import com.cts.outward.dao.VerificationIIDAOImpl;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * VerificationIIServiceImpl
 *
 * Concrete implementation of VerificationIIService.
 * Follows the same pattern as ChequeServiceImpl and BatchServiceImpl.
 *
 * Delegates all data-access operations to VerificationIIDAO.
 * Applies input validation before each delegation.
 */
public class VerificationIIServiceImpl implements VerificationIIService {

    // DAO instance is created eagerly; no DI framework in this project (matches ChequeServiceImpl pattern).
    private final VerificationIIDAO verificationIIDAO = new VerificationIIDAOImpl();

    // ── Batch operations ─────────────────────────────────────────────────────

    @Override
    public List<BatchModel> fetchHighValueBatches() {
        // No input to validate — just delegate directly to the DAO.
        return verificationIIDAO.fetchHighValueBatches();
    }

    @Override
    public List<ChequeModel> fetchHighValueChequesForBatch(String batchIdentifier) {
        // Guard: a blank batchIdentifier would silently return an empty list or cause a SQL error.
        if (batchIdentifier == null || batchIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: batchIdentifier must not be blank.");
        }
        // Trim whitespace before passing to the DAO to avoid accidental SQL mismatches.
        return verificationIIDAO.fetchHighValueChequesForBatch(batchIdentifier.trim());
    }

    // ── Cheque retrieval ─────────────────────────────────────────────────────

    @Override
    public ChequeModel fetchChequeById(long chequeId) {
        // Guard: cts_cheques.id is a bigserial (starts at 1); 0 or negative is always invalid.
        if (chequeId <= 0) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: chequeId must be a positive value. Received: " + chequeId);
        }
        return verificationIIDAO.fetchChequeById(chequeId);
    }

    // ── Verification action ──────────────────────────────────────────────────

    @Override
    public void submitHighValueChequeVerification(long chequeId, String verificationAction,
                                                  String verifierUsername, String verificationRemarks) {
        // Guard: same PK rule as fetchChequeById — must be a positive bigserial value.
        if (chequeId <= 0) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: chequeId must be a positive value. Received: " + chequeId);
        }
        // Guard: verificationAction drives the DB column update; blank would corrupt ver_action.
        if (verificationAction == null || verificationAction.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verificationAction must not be blank.");
        }
        // Guard: only the two known actions are valid — anything else is an unexpected caller bug.
        if (!"VERIFIED".equalsIgnoreCase(verificationAction)
                && !"REJECTED".equalsIgnoreCase(verificationAction)) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verificationAction must be VERIFIED or REJECTED. " +
                "Received: " + verificationAction);
        }
        // Guard: verifierUsername is written to ver_by; blank would store an empty audit trail entry.
        if (verifierUsername == null || verifierUsername.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verifierUsername must not be blank.");
        }
        // verificationRemarks is intentionally NOT validated — null or blank is a valid "no remarks" case.
        verificationIIDAO.persistVerificationDecision(
            chequeId,
            verificationAction.toUpperCase(),  // Normalise to uppercase before writing to ver_action column.
            verifierUsername.trim(),            // Trim whitespace to keep ver_by values consistent.
            verificationRemarks
        );
    }

    // ── Batch status update ──────────────────────────────────────────────────

    @Override
    public void evaluateAndUpdateBatchVerificationStatus(String batchIdentifier) {
        // Guard: blank batchIdentifier would update the wrong batch or throw a SQL error.
        if (batchIdentifier == null || batchIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: batchIdentifier must not be blank.");
        }
        // Trim before delegation — keeps the batch_id lookup consistent with fetchHighValueChequesForBatch.
        verificationIIDAO.evaluateAndUpdateBatchVerificationStatus(batchIdentifier.trim());
    }
}