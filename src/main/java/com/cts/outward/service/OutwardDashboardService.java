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
     *
     * @param date  the date to fetch stats for — pass null to default to today
     * @return      populated {@link OutwardDashboardStats} with 4 card counts
     */
    OutwardDashboardStats getDashboardStats(LocalDate date);

    /**
     * Returns filtered batches as a {@link BatchModel} list,
     * including per-batch submitted / pending cheque counts.
     *
     * @param batchIdFilter  partial batch ID to search (LIKE %value%) — null = ignore
     * @param statusFilter   exact status string to match              — null = ignore
     * @param dateFilter     filter by batch created date              — null = ignore
     * @return               list of matching batches as models, empty list if none found
     */
    List<BatchModel> getBatchesFilteredAsModels(String batchIdFilter,
                                                 String statusFilter,
                                                 LocalDate dateFilter);
}