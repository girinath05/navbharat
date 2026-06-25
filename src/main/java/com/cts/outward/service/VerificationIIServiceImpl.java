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

    private final VerificationIIDAO verificationIIDAO = new VerificationIIDAOImpl();

    // ── Batch operations ─────────────────────────────────────────────────────

    @Override
    public List<BatchModel> fetchHighValueBatches() {
        return verificationIIDAO.fetchHighValueBatches();
    }

    @Override
    public List<ChequeModel> fetchHighValueChequesForBatch(String batchIdentifier) {
        if (batchIdentifier == null || batchIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: batchIdentifier must not be blank.");
        }
        return verificationIIDAO.fetchHighValueChequesForBatch(batchIdentifier.trim());
    }

    // ── Cheque retrieval ─────────────────────────────────────────────────────

    @Override
    public ChequeModel fetchChequeById(long chequeId) {
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
        if (chequeId <= 0) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: chequeId must be a positive value. Received: " + chequeId);
        }
        if (verificationAction == null || verificationAction.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verificationAction must not be blank.");
        }
        if (!"VERIFIED".equalsIgnoreCase(verificationAction)
                && !"REJECTED".equalsIgnoreCase(verificationAction)) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verificationAction must be VERIFIED or REJECTED. " +
                "Received: " + verificationAction);
        }
        if (verifierUsername == null || verifierUsername.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verifierUsername must not be blank.");
        }
        verificationIIDAO.persistVerificationDecision(
            chequeId,
            verificationAction.toUpperCase(),
            verifierUsername.trim(),
            verificationRemarks
        );
    }

    // ── Batch status update ──────────────────────────────────────────────────

    @Override
    public void evaluateAndUpdateBatchVerificationStatus(String batchIdentifier) {
        if (batchIdentifier == null || batchIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: batchIdentifier must not be blank.");
        }
        verificationIIDAO.evaluateAndUpdateBatchVerificationStatus(batchIdentifier.trim());
    }
}