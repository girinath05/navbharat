/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : OutwardDashboardService.java
 *  Package     : com.cts.outward.service
 *  Description : Dedicated service interface for the Outward
 *                Dashboard screen. Mirrors the Verification2
 *                pattern — used only by OutwardDashboardComposer.
 * ============================================================
 */

package com.cts.outward.service;

import java.time.LocalDate;
import java.util.List;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.OutwardDashboardStats;

public interface OutwardDashboardService {

    /**
     * Returns stat-card counts for batches on a specific date.
     * Pass null to get today's data.
     */
    OutwardDashboardStats getDashboardStats(LocalDate date);

    /**
     * Returns filtered batches as BatchModel list, including
     * per-batch submitted / pending cheque counts.
     */
    List<BatchModel> getBatchesFilteredAsModels(String batchIdFilter,
                                                 String statusFilter,
                                                 LocalDate dateFilter);
}