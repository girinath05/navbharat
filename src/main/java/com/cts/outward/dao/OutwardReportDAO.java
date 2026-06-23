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

    /** Batches that have CXF / CIBF generated — shown in CXF + CIBF report tabs. */
    List<ReportBatchDTO> findGeneratedBatches();

    /** All batches across every status — shown in Batch Summary tab. */
    List<ReportBatchDTO> findAllBatches();

    /** All cheques from completed batches — shown in Cheque-level tab. */
    List<ReportChequeDTO> findAllCheques();

    /**
     * Retrieves a lightweight checksum string representing the database state for
     * change detection.
     */
    String getDatabaseSyncChecksum();

    /** Migrates legacy batch statuses. */
    void migrateBatchStatuses();
}
