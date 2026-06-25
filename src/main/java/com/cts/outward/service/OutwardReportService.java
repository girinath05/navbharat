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

    /**
     * Retrieves all batches that have generated CXF or CIBF files.
     * 
     * @return list of generated batches
     */
    List<ReportBatchDTO> getGeneratedBatches();

    /**
     * Retrieves all batches regardless of their current status.
     * 
     * @return list of all batches
     */
    List<ReportBatchDTO> getAllBatches();

    /**
     * Retrieves all individual cheques from generated batches.
     * 
     * @return list of all report cheques
     */
    List<ReportChequeDTO> getAllCheques();

    /**
     * Retrieves a lightweight checksum string representing the database state for
     * change detection.
     */
    String getDatabaseSyncChecksum();

    /** Migrates legacy batch statuses. */
    void migrateBatchStatuses();
}