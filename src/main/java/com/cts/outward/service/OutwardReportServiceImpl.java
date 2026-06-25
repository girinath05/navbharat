package com.cts.outward.service;

import com.cts.outward.dao.OutwardReportDAO;
import com.cts.outward.dao.OutwardReportDAOImpl;
import com.cts.outward.dto.ReportBatchDTO;
import com.cts.outward.dto.ReportChequeDTO;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service implementation for Outward Reports.
 * Delegates all queries to OutwardReportDAO — no business logic beyond delegation.
 */
public class OutwardReportServiceImpl implements OutwardReportService {

    private static final Logger LOG = Logger.getLogger(OutwardReportServiceImpl.class.getName());

    private final OutwardReportDAO dao;

    /**
     * Default constructor initializing the OutwardReportDAO implementation.
     */
    public OutwardReportServiceImpl() {
        this.dao = new OutwardReportDAOImpl();
    }

    /**
     * Retrieves all batches that have generated files (status is CXF_GENERATED, ACK_PENDING, or ACK_RECEIVED).
     *
     * @return list of generated batch DTOs, or an empty list if an exception occurs
     */
    @Override
    public List<ReportBatchDTO> getGeneratedBatches() {
        try {
            return dao.findGeneratedBatches();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "getGeneratedBatches failed", exception);
            return List.of();
        }
    }

    /**
     * Retrieves all batches across all statuses in the system.
     *
     * @return list of all batch DTOs, or an empty list if an exception occurs
     */
    @Override
    public List<ReportBatchDTO> getAllBatches() {
        try {
            return dao.findAllBatches();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "getAllBatches failed", exception);
            return List.of();
        }
    }

    /**
     * Retrieves all individual cheques belonging to completed batches for cheque-level reporting.
     *
     * @return list of cheque DTOs, or an empty list if an exception occurs
     */
    @Override
    public List<ReportChequeDTO> getAllCheques() {
        try {
            return dao.findAllCheques();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "getAllCheques failed", exception);
            return List.of();
        }
    }

    /**
     * Retrieves a lightweight checksum string representing the database state for change detection.
     *
     * @return checksum string, or empty string on error
     */
    @Override
    public String getDatabaseSyncChecksum() {
        try {
            return dao.getDatabaseSyncChecksum();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "getDatabaseSyncChecksum failed", exception);
            return "";
        }
    }

    /**
     * Migrates legacy batch statuses.
     */
    @Override
    public void migrateBatchStatuses() {
        try {
            dao.migrateBatchStatuses();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "migrateBatchStatuses failed", exception);
        }
    }
}

