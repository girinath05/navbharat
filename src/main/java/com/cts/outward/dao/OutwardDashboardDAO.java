/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : OutwardDashboardDAO.java
 *  Package     : com.cts.outward.dao
 *  Description : Dedicated DAO interface for the Outward
 *                Dashboard screen. Mirrors the Verification2
 *                pattern — self-contained, used only by
 *                OutwardDashboardServiceImpl.
 * ============================================================
 */

package com.cts.outward.dao;

import java.time.LocalDate;
import java.util.List;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.model.OutwardDashboardStats;

public interface OutwardDashboardDAO {

    /**
     * Returns stat-card counts for batches on a specific date.
     * Pass null to fall back to today.
     */
    OutwardDashboardStats getDashboardStats(LocalDate date);

    /**
     * Returns all batches with optional filters.
     * Pass null for any param you want to skip.
     *
     * @param batchIdLike  partial batch ID (LIKE %value%)  — null = ignore
     * @param status       exact status string              — null = ignore
     * @param date         filter by DATE(created_at)       — null = ignore
     */
    List<BatchEntity> getBatchesFiltered(String batchIdLike, String status, LocalDate date);

    /**
     * Count of cheques in a batch that have been submitted / processed forward.
     */
    int countSubmittedByBatch(String batchId);

    /**
     * Count of cheques in a batch still waiting to be processed.
     */
    int countPendingByBatch(String batchId);
}