/*
 * Project  : Navbharat CTS Outward
 * File     : BatchSummary.java
 * Package  : com.cts.outward.model
 * Author   : Anusha M.
 * Created  : June 2026
 */
package com.cts.outward.model;

import com.cts.outward.enums.BatchStatus;

/**
 * Read-only view of a batch row as displayed in the Verification I batch list (Phase 1).
 * Carries pre-computed pending and processed cheque counts so the Composer
 * does not need to perform any counting logic itself.
 */
public class BatchSummary {

    private final String      batchId;
    private final int         totalCheques;
    private final long        pendingCount;
    private final long        processedCount;
    private final String      createdAt;
    private final BatchStatus status;

    public BatchSummary(String batchId,
                        int totalCheques,
                        long pendingCount,
                        long processedCount,
                        String createdAt,
                        BatchStatus status) {
        this.batchId        = batchId;
        this.totalCheques   = totalCheques;
        this.pendingCount   = pendingCount;
        this.processedCount = processedCount;
        this.createdAt      = createdAt;
        this.status         = status;
    }

    public String      getBatchId()        { return batchId;        }
    public int         getTotalCheques()   { return totalCheques;   }
    public long        getPendingCount()   { return pendingCount;   }
    public long        getProcessedCount() { return processedCount; }
    public String      getCreatedAt()      { return createdAt;      }
    public BatchStatus getStatus()         { return status;         }
}