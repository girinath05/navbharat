package com.cts.outward.dao;

import com.cts.outward.dto.ReportBatchDTO;
import com.cts.outward.dto.ReportChequeDTO;

import java.util.List;

/**
 * DAO interface for the Outward Reports page (outwardReports.zul).
 * Four report tabs:
 * 1. CXF Report — completed batches (status: CXF_GENERATED / ACK_PENDING /
 * ACK_RECEIVED)
 * 2. CIBF Report — same rows, different column focus (cibfFileName)
 * 3. Batch Summary — all batches across all statuses
 * 4. Cheque-level — individual cheques across all completed batches
 */
public interface OutwardReportDAO {

    /**
     * Finds and retrieves all batches that have completed file generation.
     * 
     * @return list of generated batches DTOs
     */
    List<ReportBatchDTO> findGeneratedBatches();

    /**
     * Finds and retrieves all batches regardless of their status.
     * 
     * @return list of all batches DTOs
     */
    List<ReportBatchDTO> findAllBatches();

    /**
     * Finds and retrieves all cheques belonging to completed batches.
     * 
     * @return list of report cheque DTOs
     */
    List<ReportChequeDTO> findAllCheques();

    /**
     * Retrieves a lightweight checksum string representing the database state for
     * change detection.
     */
    String getDatabaseSyncChecksum();

    /** Migrates legacy batch statuses. */
    void migrateBatchStatuses();
}