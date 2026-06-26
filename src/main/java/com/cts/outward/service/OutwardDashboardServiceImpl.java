package com.cts.outward.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.cts.outward.dao.OutwardDashboardDAO;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.OutwardDashboardStats;

public class OutwardDashboardServiceImpl implements OutwardDashboardService {

    private final OutwardDashboardDAO outwardDashboardDAO;

    public OutwardDashboardServiceImpl(OutwardDashboardDAO outwardDashboardDAO) {
        this.outwardDashboardDAO = outwardDashboardDAO;
    }

    @Override
    public OutwardDashboardStats getDashboardStats(LocalDate date) {

        // Get raw { status, count } rows from DB and map them to stat cards here
        List<Object[]> rawStatusCounts = outwardDashboardDAO.getRawStatusCountsByDate(date);

        OutwardDashboardStats stats = new OutwardDashboardStats();
        int totalBatchCount = 0;

        for (Object[] row : rawStatusCounts) {
            String rawStatus  = (String) row[0];
            int    batchCount = ((Number) row[1]).intValue();
            totalBatchCount  += batchCount;

            if (rawStatus == null) continue;

            BatchStatus batchStatus = BatchStatus.fromDb(rawStatus);

            if (batchStatus == BatchStatus.READY_FOR_VERIFICATION
             || batchStatus == BatchStatus.VERIFICATION_IN_PROGRESS) {
                stats.setVerificationBatches(stats.getVerificationBatches() + batchCount);

            } else if (batchStatus == BatchStatus.VERIFIED) {
                stats.setVerifiedBatches(stats.getVerifiedBatches() + batchCount);

            } else if (batchStatus == BatchStatus.CXF_CIBF_GENERATED
                    || batchStatus == BatchStatus.DISPATCHED) {
                stats.setDispatchedBatches(stats.getDispatchedBatches() + batchCount);
            }
            // DRAFT / PENDING — counted in total only, no separate card
        }

        stats.setTotalBatches(totalBatchCount);
        return stats;
    }

    @Override
    public List<BatchModel> getBatchesFilteredAsModels(String batchIdFilter,
                                                        String statusFilter,
                                                        LocalDate dateFilter) {

        List<BatchEntity> batchEntities =
                outwardDashboardDAO.getBatchesFiltered(batchIdFilter, statusFilter, dateFilter);

        List<BatchModel> batchModels = new ArrayList<>();

        for (BatchEntity batchEntity : batchEntities) {

            // Map entity fields to model
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

            // Fetch submitted and pending cheque counts for this batch
            batchModel.setSubmittedCheques(outwardDashboardDAO.countSubmittedByBatch(batchEntity.getBatchId()));
            batchModel.setPendingCheques(outwardDashboardDAO.countPendingByBatch(batchEntity.getBatchId()));

            batchModels.add(batchModel);
        }

        return batchModels;
    }
}