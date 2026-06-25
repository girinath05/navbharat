/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : OutwardDashboardServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Description : Implementation of OutwardDashboardService.
 *                Delegates to OutwardDashboardDAO. Logic copied
 *                from BatchServiceImpl.getDashboardStats(date) /
 *                getBatchesFilteredAsModels(), unchanged.
 * ============================================================
 */

package com.cts.outward.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.cts.outward.dao.OutwardDashboardDAO;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.OutwardDashboardStats;
// OutwardDashboardStats — used as return type for getDashboardStats() passthrough

public class OutwardDashboardServiceImpl implements OutwardDashboardService {

    private final OutwardDashboardDAO outwardDashboardDAO;

    public OutwardDashboardServiceImpl(OutwardDashboardDAO outwardDashboardDAO) {
        this.outwardDashboardDAO = outwardDashboardDAO;
    }

    @Override
    public OutwardDashboardStats getDashboardStats(LocalDate date) {
        return outwardDashboardDAO.getDashboardStats(date);
    }

    // ════════════════════════════════════════════════════════════
    //  FILTERED BATCH LIST AS MODELS — returns BatchModel list
    //  Used by OutwardDashboardComposer to show submitted /
    //  pending cheque counts per batch row.
    //
    //  Flow:
    //    1. outwardDashboardDAO.getBatchesFiltered()    → List<BatchEntity>
    //    2. For each batchEntity: map fields to BatchModel
    //    3. outwardDashboardDAO.countSubmittedByBatch() → submittedCheques
    //    4. outwardDashboardDAO.countPendingByBatch()   → pendingCheques
    //    5. Return List<BatchModel>
    // ════════════════════════════════════════════════════════════

    @Override
    public List<BatchModel> getBatchesFilteredAsModels(String batchIdFilter,
                                                        String statusFilter,
                                                        LocalDate dateFilter) {
        List<BatchEntity> batchEntities = outwardDashboardDAO.getBatchesFiltered(
                batchIdFilter, statusFilter, dateFilter);

        List<BatchModel> batchModels = new ArrayList<>();
        for (BatchEntity batchEntity : batchEntities) {
            BatchModel batchModel = new BatchModel();
            batchModel.setBatchId(batchEntity.getBatchId());
            batchModel.setBranchCode(batchEntity.getBranchCode());
            batchModel.setTotalCheques(batchEntity.getTotalCheques());
            batchModel.setExpectedCheques(batchEntity.getExpectedCheques());
            batchModel.setTotalAmount(batchEntity.getTotalAmount());
            batchModel.setExpectedAmount(batchEntity.getExpectedAmount());
            batchModel.setStatus(batchEntity.getStatus());
            batchModel.setCreatedAt(batchEntity.getCreatedAt());
            batchModel.setUpdatedAt(batchEntity.getUpdatedAt());

            // Cheque-level counts — two lightweight COUNT queries per batch
            batchModel.setSubmittedCheques(outwardDashboardDAO.countSubmittedByBatch(batchEntity.getBatchId()));
            batchModel.setPendingCheques(outwardDashboardDAO.countPendingByBatch(batchEntity.getBatchId()));

            batchModels.add(batchModel);
        }
        return batchModels;
    }
}