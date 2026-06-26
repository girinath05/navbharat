package com.cts.outward.dao;

import java.time.LocalDate;
import java.util.List;

import com.cts.outward.entity.BatchEntity;

public interface OutwardDashboardDAO {

    // Returns { statusString, count } rows for batches on the given date
    List<Object[]> getRawStatusCountsByDate(LocalDate date);

    // Returns filtered batches — pass null for any param to skip that filter
    List<BatchEntity> getBatchesFiltered(String batchIdLike, String status, LocalDate date);

    // Count of cheques with terminal status (VERIFIED or REJECTED) in a batch
    int countSubmittedByBatch(String batchId);

    // Count of cheques still waiting to be processed in a batch
    int countPendingByBatch(String batchId);
}