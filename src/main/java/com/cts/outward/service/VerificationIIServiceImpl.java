package com.cts.outward.service;

import com.cts.outward.dao.VerificationIIDAO;
import com.cts.outward.dao.VerificationIIDAOImpl;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * VerificationIIServiceImpl
 *
 * Implementation of VerificationIIService.
 * Follows the same pattern as ChequeServiceImpl / BatchServiceImpl.
 */
public class VerificationIIServiceImpl implements VerificationIIService {

    private final VerificationIIDAO verificationIIDAO = new VerificationIIDAOImpl();

    @Override
    public List<BatchModel> getHighValueBatches() {
        return verificationIIDAO.getHighValueBatches();
    }

    @Override
    public List<ChequeModel> getHighValueChequesForBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: batchId must not be blank");
        }
        return verificationIIDAO.getHighValueChequesForBatch(batchId.trim());
    }

    @Override
    public ChequeModel getChequeById(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: cheque id must be positive. Got: " + id);
        }
        return verificationIIDAO.getChequeById(id);
    }

    @Override
    public void verifyHighValueCheque(long id, String action, String verBy, String remarks) {
        if (id <= 0) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: cheque id must be positive. Got: " + id);
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: action must not be blank");
        }
        if (!"VERIFIED".equalsIgnoreCase(action) && !"REJECTED".equalsIgnoreCase(action)) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: action must be VERIFIED or REJECTED. Got: " + action);
        }
        if (verBy == null || verBy.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: verBy (username) must not be blank");
        }
        verificationIIDAO.updateVerification(id, action.toUpperCase(), verBy.trim(), remarks);
    }

    @Override
    public void checkAndUpdateBatchStatus(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException(
                "VerificationIIServiceImpl: batchId must not be blank");
        }
        verificationIIDAO.checkAndUpdateBatchStatus(batchId.trim());
    }
}