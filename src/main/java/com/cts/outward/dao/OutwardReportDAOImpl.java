package com.cts.outward.dao;

import com.cts.outward.dto.ReportBatchDTO;
import com.cts.outward.dto.ReportChequeDTO;
import com.cts.outward.enums.BatchStatus;
import com.cts.util.HibernateUtil;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DAO implementation for Outward Reports.
 *
 * Uses native SQL against:
 * cts_batches — batch_id, branch_code, status, total_cheques, total_amount,
 * cxf_file_name, cibf_file_name, generated_at, sent_at, ack_at, created_at
 * cts_cheques — id, batch_id, cheque_no, account_no, amount, cheque_date,
 * sort_code, iqa_status
 *
 * No entity mapping needed — all results go to plain DTOs.
 */
public class OutwardReportDAOImpl implements OutwardReportDAO {

    private static final Logger LOG = Logger.getLogger(OutwardReportDAOImpl.class.getName());

    // ── CXF / CIBF Report: batches that have generated files ──────────────────

    /**
     * Finds and retrieves all batches that have generated files.
     * Batches must be in one of the completed statuses: 'CXF_GENERATED',
     * 'ACK_PENDING', or 'ACK_RECEIVED'.
     *
     * @return list of ReportBatchDTO objects
     */
    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public List<ReportBatchDTO> findGeneratedBatches() {
        List<ReportBatchDTO> generatedBatchesList = new ArrayList<>();
        String sql = "SELECT batch_id, branch_code, status, total_cheques, total_amount, " +
                "       cxf_file_name, cibf_file_name, generated_at " +
                "FROM cts_batches " +
                "WHERE status = '" + BatchStatus.CXF_CIBF_GENERATED.db() + "' " +
                "ORDER BY generated_at DESC NULLS LAST";
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            List rows = session.createNativeQuery(sql).list();
            for (Object rawRow : rows) {
                Object[] rowArray = coerceObjectToRowArray(rawRow);
                ReportBatchDTO batchDTO = new ReportBatchDTO();
                batchDTO.setBatchId(coerceToString(rowArray[0]));
                batchDTO.setBranchCode(coerceToString(rowArray[1]));
                batchDTO.setStatus(coerceToString(rowArray[2]));
                batchDTO.setTotalCheques(coerceToInt(rowArray[3]));
                batchDTO.setTotalAmount(coerceToBigDecimal(rowArray[4]));
                batchDTO.setCxfFileName(coerceToString(rowArray[5]));
                batchDTO.setCibfFileName(coerceToString(rowArray[6]));
                batchDTO.setGeneratedAt(coerceToLocalDateTime(rowArray[7]));
                generatedBatchesList.add(batchDTO);
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "findGeneratedBatches failed", exception);
        }
        return generatedBatchesList;
    }

    // ── Batch Summary: every batch regardless of status ───────────────────────

    /**
     * Finds and retrieves all batches across all statuses in the system.
     *
     * @return list of ReportBatchDTO objects representing all batches
     */
    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public List<ReportBatchDTO> findAllBatches() {
        List<ReportBatchDTO> allBatchesList = new ArrayList<>();
        String sql = "SELECT batch_id, branch_code, status, total_cheques, total_amount, " +
                "       cxf_file_name, cibf_file_name, generated_at, created_at, created_by " +
                "FROM cts_batches " +
                "ORDER BY created_at DESC NULLS LAST";
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            List rows = session.createNativeQuery(sql).list();
            for (Object rawRow : rows) {
                Object[] rowArray = coerceObjectToRowArray(rawRow);
                ReportBatchDTO batchDTO = new ReportBatchDTO();
                batchDTO.setBatchId(coerceToString(rowArray[0]));
                batchDTO.setBranchCode(coerceToString(rowArray[1]));
                batchDTO.setStatus(coerceToString(rowArray[2]));
                batchDTO.setTotalCheques(coerceToInt(rowArray[3]));
                batchDTO.setTotalAmount(coerceToBigDecimal(rowArray[4]));
                batchDTO.setCxfFileName(coerceToString(rowArray[5]));
                batchDTO.setCibfFileName(coerceToString(rowArray[6]));
                batchDTO.setGeneratedAt(coerceToLocalDateTime(rowArray[7]));
                batchDTO.setCreatedAt(coerceToLocalDateTime(rowArray[8]));
                batchDTO.setCreatedBy(coerceToString(rowArray[9]));
                allBatchesList.add(batchDTO);
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "findAllBatches failed", exception);
        }
        return allBatchesList;
    }

    // ── Cheque-level: all cheques from completed batches ──────────────────────

    /**
     * Finds and retrieves all cheques belonging to completed batches.
     * Excludes image binary content since it is not needed for reports.
     *
     * @return list of ReportChequeDTO objects
     */
    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public List<ReportChequeDTO> findAllCheques() {
        List<ReportChequeDTO> chequesList = new ArrayList<>();
        // Join to cts_batches so we only return cheques belonging to generated batches.
        // Excludes front_image / rear_image (bytea) — not needed for reports.
        String sql = "SELECT c.cheque_no, c.batch_id, c.cheque_date, c.account_no, " +
                "       c.drawer_name, c.payee_name, c.amount, c.ver_status, b.branch_code, b.status, c.iqa_status, c.status "
                +
                "FROM cts_cheques c " +
                "JOIN cts_batches b ON b.batch_id = c.batch_id " +
                "ORDER BY c.batch_id, c.id ASC";
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            List rows = session.createNativeQuery(sql).list();
            for (Object rawRow : rows) {
                Object[] rowArray = coerceObjectToRowArray(rawRow);
                ReportChequeDTO chequeDTO = new ReportChequeDTO();
                chequeDTO.setChequeNo(coerceToString(rowArray[0]));
                chequeDTO.setBatchId(coerceToString(rowArray[1]));
                chequeDTO.setChequeDate(coerceToString(rowArray[2]));
                chequeDTO.setAccountNo(coerceToString(rowArray[3]));
                chequeDTO.setDrawerName(coerceToString(rowArray[4]));
                chequeDTO.setPayeeName(coerceToString(rowArray[5]));
                chequeDTO.setAmount(coerceToBigDecimal(rowArray[6]));
                chequeDTO.setVerStatus(coerceToString(rowArray[7]));
                chequeDTO.setBranchCode(coerceToString(rowArray[8]));
                chequeDTO.setBatchStatus(coerceToString(rowArray[9]));
                chequeDTO.setIqaStatus(coerceToString(rowArray[10]));
                chequeDTO.setStatus(coerceToString(rowArray[11]));
                chequesList.add(chequeDTO);
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "findAllCheques failed", exception);
        }
        return chequesList;
    }

    /**
     * Retrieves a lightweight checksum string representing the database state for
     * change detection.
     *
     * @return checksum string, or empty string on error
     */
    @Override
    public String getDatabaseSyncChecksum() {
        String sql = "SELECT COALESCE(CAST(COUNT(*) AS VARCHAR), '') || '-' || " +
                "       COALESCE(CAST(SUM(total_cheques) AS VARCHAR), '') || '-' || " +
                "       COALESCE(CAST(SUM(COALESCE(total_amount, 0)) AS VARCHAR), '') || '-' || " +
                "       COALESCE(CAST(MAX(created_at) AS VARCHAR), '') || '-' || " +
                "       COALESCE(CAST(MAX(generated_at) AS VARCHAR), '') || '-' || " +
                "       '' || '-' || " +
                "       '' || '-' || " +
                "       COALESCE(CAST((SELECT COUNT(*) FROM cts_cheques) AS VARCHAR), '') || '-' || " +
                "       COALESCE(CAST((SELECT SUM(amount) FROM cts_cheques) AS VARCHAR), '') " +
                "FROM cts_batches";
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Object result = session.createNativeQuery(sql).uniqueResult();
            return result != null ? result.toString() : "";
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "getDatabaseSyncChecksum failed", exception);
            return "";
        }
    }

    /**
     * Executes native SQL to migrate legacy batch statuses.
     */
    @Override
    public void migrateBatchStatuses() {
        String sql = "UPDATE cts_batches SET status = '" + BatchStatus.CXF_CIBF_GENERATED.db() + "' WHERE status IN ('ACK_PENDING', 'ACK_RECEIVED', 'CXF_GENERATED')";
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery(sql).executeUpdate();
            session.getTransaction().commit();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "migrateBatchStatuses failed", exception);
        }
    }


    // ── Type coercions ────────────────────────────────────────────────────────

    /**
     * Coerces a raw query row object into an Object array representation.
     *
     * @param rawRow raw row object from database
     * @return object array representing the query columns
     */
    private static Object[] coerceObjectToRowArray(Object rawRow) {

        return (rawRow instanceof Object[]) ? (Object[]) rawRow : new Object[] { rawRow };
    }

    /**
     * Coerces a database query column value safely into a Java String.
     *
     * @param inputObject raw database object reference
     * @return string value or empty string if null
     */
    private static String coerceToString(Object inputObject) {
        return inputObject != null ? inputObject.toString() : "";
    }

    /**
     * Coerces a database query column value safely into a primitive int.
     *
     * @param inputObject raw database object reference
     * @return int value or 0 if null
     */
    private static int coerceToInt(Object inputObject) {
        return inputObject != null ? ((Number) inputObject).intValue() : 0;
    }

    /**
     * Coerces a database query column value safely into a BigDecimal.
     *
     * @param inputObject raw database object reference
     * @return BigDecimal value or BigDecimal.ZERO if null
     */
    private static BigDecimal coerceToBigDecimal(Object inputObject) {
        if (inputObject == null)
            return BigDecimal.ZERO;
        return (inputObject instanceof BigDecimal) ? (BigDecimal) inputObject : new BigDecimal(inputObject.toString());
    }

    /**
     * Coerces a database query column value safely into a LocalDateTime.
     *
     * @param inputObject raw database object reference
     * @return LocalDateTime value, or null if null or invalid type
     */
    private static LocalDateTime coerceToLocalDateTime(Object inputObject) {
        if (inputObject == null)
            return null;
        if (inputObject instanceof Timestamp)
            return ((Timestamp) inputObject).toLocalDateTime();
        if (inputObject instanceof LocalDateTime)
            return (LocalDateTime) inputObject;
        return null;
    }
}