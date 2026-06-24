package com.cts.outward.service;

import com.cts.outward.dto.ReportBatchDTO;
import com.cts.outward.dto.ReportChequeDTO;

import java.util.List;

/**
 * Service interface for the Outward Reports page.
 * Thin layer over OutwardReportDAO — delegates directly; no business logic
 * needed.
 */
public interface OutwardReportService {

    /** Batches with CXF/CIBF generated — CXF and CIBF report tabs. */
    List<ReportBatchDTO> getGeneratedBatches();

    /** All batches across every status — Batch Summary tab. */
    List<ReportBatchDTO> getAllBatches();

    /** All cheques from generated batches — Cheque-level Report tab. */
    List<ReportChequeDTO> getAllCheques();

    /**
     * Retrieves a lightweight checksum string representing the database state for
     * change detection.
     */
    String getDatabaseSyncChecksum();

    /** Migrates legacy batch statuses. */
    void migrateBatchStatuses();
}