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
    //    1. outwardDashboardDAO.getBatchesFiltered()  → List<BatchEntity>
    //    2. For each entity: map fields to BatchModel
    //    3. outwardDashboardDAO.countSubmittedByBatch() → submittedCheques
    //    4. outwardDashboardDAO.countPendingByBatch()   → pendingCheques
    //    5. Return List<BatchModel>
    // ════════════════════════════════════════════════════════════

    @Override
    public List<BatchModel> getBatchesFilteredAsModels(String batchIdFilter,
                                                       String statusFilter,
                                                       LocalDate dateFilter) {
        List<BatchEntity> entities = outwardDashboardDAO.getBatchesFiltered(
                batchIdFilter, statusFilter, dateFilter);

        List<BatchModel> models = new ArrayList<>();
        for (BatchEntity e : entities) {
            BatchModel m = new BatchModel();
            m.setBatchId(e.getBatchId());
            m.setBranchCode(e.getBranchCode());
            m.setTotalCheques(e.getTotalCheques());
            m.setExpectedCheques(e.getExpectedCheques());
            m.setTotalAmount(e.getTotalAmount());
            m.setExpectedAmount(e.getExpectedAmount());
            m.setStatus(e.getStatus());
            m.setCreatedAt(e.getCreatedAt());
            m.setUpdatedAt(e.getUpdatedAt());

            // Cheque-level counts — two lightweight COUNT queries per batch
            m.setSubmittedCheques(outwardDashboardDAO.countSubmittedByBatch(e.getBatchId()));
            m.setPendingCheques(outwardDashboardDAO.countPendingByBatch(e.getBatchId()));

            models.add(m);
        }
        return models;
    }
}