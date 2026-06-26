package com.cts.outward.service;

import java.time.LocalDate;
import java.util.List;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.OutwardDashboardStats;

public interface OutwardDashboardService {

    // Returns stat card counts (total, verification, verified, dispatched) for the given date
    OutwardDashboardStats getDashboardStats(LocalDate date);

    // Returns filtered batches as models with submitted/pending cheque counts per batch
    List<BatchModel> getBatchesFilteredAsModels(String batchIdFilter,
                                                 String statusFilter,
                                                 LocalDate dateFilter);
}