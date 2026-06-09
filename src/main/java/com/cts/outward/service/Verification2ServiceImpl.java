package com.cts.outward.service;

import com.cts.outward.dao.Verification2DAO;
import com.cts.outward.dao.Verification2DAOImpl;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;

import java.util.List;

/**
 * Verification2ServiceImpl
 *
 * Implementation of Verification2Service.
 * Follows the same pattern as ChequeServiceImpl / BatchServiceImpl.
 */
public class Verification2ServiceImpl implements Verification2Service {

    private final Verification2DAO verification2DAO = new Verification2DAOImpl();

    @Override
    public List<BatchModel> getHighValueBatches() {
        return verification2DAO.getHighValueBatches();
    }

    @Override
    public List<ChequeModel> getHighValueChequesForBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException(
                "Verification2ServiceImpl: batchId must not be blank");
        }
        return verification2DAO.getHighValueChequesForBatch(batchId.trim());
    }

    @Override
    public ChequeModel getChequeById(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException(
                "Verification2ServiceImpl: cheque id must be positive. Got: " + id);
        }
        return verification2DAO.getChequeById(id);
    }

    @Override
    public void verifyHighValueCheque(long id, String action, String verBy, String remarks) {
        if (id <= 0) {
            throw new IllegalArgumentException(
                "Verification2ServiceImpl: cheque id must be positive. Got: " + id);
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException(
                "Verification2ServiceImpl: action must not be blank");
        }
        if (!"ACCEPTED".equalsIgnoreCase(action) && !"REJECTED".equalsIgnoreCase(action)) {
            throw new IllegalArgumentException(
                "Verification2ServiceImpl: action must be ACCEPTED or REJECTED. Got: " + action);
        }
        if (verBy == null || verBy.isBlank()) {
            throw new IllegalArgumentException(
                "Verification2ServiceImpl: verBy (username) must not be blank");
        }
        verification2DAO.updateVerification(id, action.toUpperCase(), verBy.trim(), remarks);
    }
}