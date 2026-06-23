package com.cts.outward.dao;
//hi
import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.dto.CxfChequeDTO;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.util.HibernateUtil;
import org.hibernate.Session;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DAO implementation for CXF-CIBF Generation.
 *
 * Uses real Supabase table names:
 *   cts_batches  – batch_id (varchar PK), branch_code, status,
 *                  total_cheques, total_amount, created_at, updated_at,
 *                  cxf_file_name*, cibf_file_name*, generated_at*,
 *                  sent_at*, ack_at*   (* added via migration)
 *
 *   cts_cheques  – id (int8 PK), batch_id, cheque_no, cheque_id,
 *                  account_no, amount, cheque_date, drawer_name,
 *                  payee_name, payee_account_no, sort_code, base_no,
 *                  transaction_code, iqa_status,
 *                  front_image (bytea), rear_image (bytea),
 *                  ver_status, ...
 *
 * Status lifecycle:
 *   SCAN_DONE → VER1_DONE → VER2_DONE → CXF_GENERATED → ACK_PENDING → ACK_RECEIVED
 */
public class CxfCibfDAOImpl implements CxfCibfDAO {

    private static final Logger LOG = Logger.getLogger(CxfCibfDAOImpl.class.getName());

    // ═══════════════════════════════════════════════════════
    // STAT COUNTS
    // ═══════════════════════════════════════════════════════

    /**
     * Counts the number of batches that have completed the CXF/CIBF generation process.
     * Statuses considered completed are 'CXF_GENERATED', 'ACK_PENDING', and 'ACK_RECEIVED'.
     *
     * @return count of completed batches
     */
    @Override
    public long countCompleted() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = "SELECT COUNT(*) FROM cts_batches " +
                         "WHERE status = '" + BatchStatus.CXF_CIBF_GENERATED.db() + "'";
            Object result = session.createNativeQuery(sql, Object.class).uniqueResult();
            return result != null ? ((Number) result).longValue() : 0L;
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "countCompleted failed", exception);
            return 0L;
        }
    }

    /**
     * Counts the number of verified batches that are currently pending file generation.
     *
     * @return count of pending batches
     */
    @Override
    public long countPending() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            String sql = "SELECT COUNT(*) FROM cts_batches " +
                         "WHERE status IN ('FILE_GENERATION_PENDING', 'VER2_DONE', 'VERIFIED', 'Verified')";
            Object result = session.createNativeQuery(sql, Object.class).uniqueResult();
            return result != null ? ((Number) result).longValue() : 0L;
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "countPending failed", exception);
            return 0L;
        }
    }

    // countAckPending, countAckReceived, and countBatchesByStatus removed.

    // ═══════════════════════════════════════════════════════
    // LIST QUERIES
    // ═══════════════════════════════════════════════════════

    /**
     * Retrieves all batches that have successfully generated CXF/CIBF files.
     *
     * @return list of completed batches DTOs
     */
    @Override
    public List<CxfBatchDTO> findCompletedBatches() {
        String sql =
            "SELECT batch_id, status, total_cheques, total_amount, " +
            "       cxf_file_name, cibf_file_name, generated_at " +
            "FROM cts_batches " +
            "WHERE status = '" + BatchStatus.CXF_CIBF_GENERATED.db() + "' " +
            "ORDER BY generated_at DESC NULLS LAST";
        return retrieveBatchesFromDatabase(sql, "COMPLETED");
    }

    /**
     * Retrieves all verified batches pending file generation.
     *
     * @return list of pending batches DTOs
     */
    @Override
    public List<CxfBatchDTO> findPendingBatches() {
        String sql =
            "SELECT batch_id, branch_code, status, total_cheques, total_amount, created_at " +
            "FROM cts_batches " +
            "WHERE status IN ('FILE_GENERATION_PENDING', 'VER2_DONE', 'VERIFIED', 'Verified') " +
            "ORDER BY created_at DESC NULLS LAST";
        return retrieveBatchesFromDatabase(sql, "PENDING");
    }

    /**
     * Retrieves all verified batches ready for generation.
     *
     * @return list of verified batches DTOs
     */
    @Override
    public List<CxfBatchDTO> findVerifiedBatches() {
        String sql =
            "SELECT batch_id, branch_code, status, total_cheques, total_amount, created_at " +
            "FROM cts_batches " +
            "WHERE status IN ('FILE_GENERATION_PENDING', 'VER2_DONE', 'VERIFIED', 'Verified') " +
            "ORDER BY created_at ASC";
        return retrieveBatchesFromDatabase(sql, "PENDING");
    }

    /**
     * Retrieves details for a specific verified batch.
     *
     * @param batchId unique batch identifier
     * @return batch DTO, or null if not found or not in pending status
     */
    @Override
    @SuppressWarnings({"unchecked","rawtypes"})
    public CxfBatchDTO findVerifiedBatch(String batchId) {
        String sql =
            "SELECT batch_id, branch_code, status, total_cheques, total_amount, created_at " +
            "FROM cts_batches " +
            "WHERE status IN ('FILE_GENERATION_PENDING', 'VER2_DONE', 'VERIFIED', 'Verified') AND batch_id = :bid";
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Object raw = session.createNativeQuery(sql, Object[].class)
                          .setParameter("bid", batchId)
                          .uniqueResult();
            if (raw == null) return null;
            Object[] row = (raw instanceof Object[]) ? (Object[]) raw : new Object[]{raw};
            return mapDatabaseRowToCxfBatchDTO(row, "PENDING");
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "findVerifiedBatch(" + batchId + ") failed", exception);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════
    // CHEQUES FOR ONE BATCH  (includes bytea images)
    // ═══════════════════════════════════════════════════════

    /**
     * Loads all cheques belonging to a specific batch including binary images.
     *
     * @param batchId unique batch identifier
     * @return list of cheque DTOs
     */
    @Override
    @SuppressWarnings({"unchecked","rawtypes"})
    public List<CxfChequeDTO> findChequesForBatch(String batchId) {
        List<CxfChequeDTO> list = new ArrayList<>();
        String sql =
            "SELECT id, batch_id, cheque_no, cheque_id, account_no, amount, " +
            "       cheque_date, drawer_name, payee_name, payee_account_no, " +
            "       sort_code, base_no, transaction_code, iqa_status, " +
            "       front_image, rear_image " +
            "FROM cts_cheques " +
            "WHERE batch_id = :bid " +
            "ORDER BY id ASC";

        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            List rows = session.createNativeQuery(sql, Object[].class)
                          .setParameter("bid", batchId)
                          .list();
            for (Object raw : rows) {
                Object[] rowArray = (raw instanceof Object[]) ? (Object[]) raw : new Object[]{raw};
                CxfChequeDTO chequeDTO = new CxfChequeDTO();
                chequeDTO.setId(coerceToLong(rowArray[0]));
                chequeDTO.setBatchId(coerceToString(rowArray[1]));
                chequeDTO.setChequeNo(coerceToString(rowArray[2]));
                chequeDTO.setChequeId(coerceToString(rowArray[3]));
                chequeDTO.setAccountNo(coerceToString(rowArray[4]));
                chequeDTO.setAmount(coerceToBigDecimal(rowArray[5]));
                chequeDTO.setChequeDate(coerceToString(rowArray[6]));
                chequeDTO.setDrawerName(coerceToString(rowArray[7]));
                chequeDTO.setPayeeName(coerceToString(rowArray[8]));
                chequeDTO.setPayeeAccountNo(coerceToString(rowArray[9]));
                chequeDTO.setSortCode(coerceToString(rowArray[10]));
                chequeDTO.setBaseNo(coerceToString(rowArray[11]));
                chequeDTO.setTransactionCode(coerceToString(rowArray[12]));
                chequeDTO.setIqaStatus(coerceToString(rowArray[13]));
                chequeDTO.setFrontImage(coerceToBytes(rowArray[14]));
                chequeDTO.setRearImage(coerceToBytes(rowArray[15]));
                list.add(chequeDTO);
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "findChequesForBatch(" + batchId + ") failed", exception);
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════
    // STATUS UPDATES
    // ═══════════════════════════════════════════════════════

    /**
     * Marks a batch status as 'CXF_GENERATED' and saves the file names and generation timestamp.
     *
     * @param batchId      unique batch identifier
     * @param cxfFileName  name of generated CXF file
     * @param cibfFileName name of generated CIBF file
     * @param generatedAt  generation timestamp
     */
    @Override
    public void markCxfGenerated(String batchId, String cxfFileName,
                                  String cibfFileName, LocalDateTime generatedAt) {
        String sql =
            "UPDATE cts_batches SET status='" + BatchStatus.CXF_CIBF_GENERATED.db() + "', " +
            "  cxf_file_name=:cxf, cibf_file_name=:cibf, " +
            "  generated_at=:gat, updated_at=NOW() " +
            "WHERE batch_id=:bid";
        executeUpdateBatchCxfCibfStatus(sql, batchId, cxfFileName, cibfFileName, generatedAt);
    }

    // markAckPending and markAckReceived removed.

    // ═══════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════

    /**
     * Internal helper to execute native SQL queries and map output to batch DTOs.
     *
     * @param sql     native SELECT query
     * @param context mapping context descriptor
     * @return list of batch DTOs
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    private List<CxfBatchDTO> retrieveBatchesFromDatabase(String sql, String context) {
        List<CxfBatchDTO> list = new ArrayList<>();
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            session.clear();  // flush L1 cache — ensures fresh read after markAckPending/markCxfGenerated
            List rows = session.createNativeQuery(sql, Object[].class).list();
            LOG.info("retrieveBatchesFromDatabase[" + context + "] raw row count = " + rows.size());
            for (Object raw : rows) {
                Object[] rowArray;
                if (raw instanceof Object[]) {
                    rowArray = (Object[]) raw;
                } else {
                    // Single-column result — wrap it
                    rowArray = new Object[]{raw};
                }
                LOG.info("retrieveBatchesFromDatabase[" + context + "] rowArray.length=" + rowArray.length);
                list.add(mapDatabaseRowToCxfBatchDTO(rowArray, context));
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "retrieveBatchesFromDatabase(" + context + ") FAILED: " + exception.getMessage(), exception);
        }
        return list;
    }

    /**
     * Maps database query result row into a CxfBatchDTO based on context.
     *
     * @param rowArray  raw row array from database
     * @param context descriptor context for database fields mapping
     * @return mapped batch DTO
     */
    private CxfBatchDTO mapDatabaseRowToCxfBatchDTO(Object[] rowArray, String context) {
        CxfBatchDTO batchDTO = new CxfBatchDTO();

        if ("COMPLETED".equals(context)) {
            // SELECT: batch_id[0], status[1], total_cheques[2], total_amount[3],
            //         cxf_file_name[4], cibf_file_name[5], generated_at[6]
            batchDTO.setBatchId(coerceToString(rowArray[0]));
            batchDTO.setStatus(coerceToString(rowArray[1]));
            batchDTO.setTotalCheques(rowArray[2] != null ? ((Number) rowArray[2]).intValue() : 0);
            batchDTO.setTotalAmount(coerceToBigDecimal(rowArray[3]));
            batchDTO.setCxfFileName(coerceToString(safe(rowArray, 4)));
            batchDTO.setCibfFileName(coerceToString(safe(rowArray, 5)));
            batchDTO.setGeneratedAt(coerceToLocalDateTime(safe(rowArray, 6)));
            return batchDTO;
        }

        // All other queries:
        // batch_id[0], branch_code[1], status[2], total_cheques[3], total_amount[4], ...
        batchDTO.setBatchId(coerceToString(rowArray[0]));
        batchDTO.setBranchCode(coerceToString(safe(rowArray, 1)));
        batchDTO.setStatus(coerceToString(safe(rowArray, 2)));
        batchDTO.setTotalCheques(rowArray[3] != null ? ((Number) rowArray[3]).intValue() : 0);
        batchDTO.setTotalAmount(coerceToBigDecimal(safe(rowArray, 4)));

        switch (context) {
            case "PENDING":
                // SELECT: ..., created_at[5]
                batchDTO.setCreatedAt(coerceToLocalDateTime(safe(rowArray, 5)));
                batchDTO.setStatusReason(resolvePendingReasonText(coerceToString(safe(rowArray, 2))));
                break;
            case "VER2_DONE":
                batchDTO.setCreatedAt(coerceToLocalDateTime(safe(rowArray, 5)));
                break;
            default:
                break;
        }
        return batchDTO;
    }

    /** Safe array access — returns null instead of ArrayIndexOutOfBoundsException */
    private static Object safe(Object[] rowArray, int idx) {
        return (rowArray != null && idx < rowArray.length) ? rowArray[idx] : null;
    }

    /**
     * Internal mutation helper specifically for updating the batch's generated CXF/CIBF status.
     *
     * @param sql      native UPDATE query
     * @param batchId  unique batch identifier
     * @param cxf      name of the CXF file
     * @param cibf     name of the CIBF file
     * @param generatedAt timestamp when file was generated
     */
    private void executeUpdateBatchCxfCibfStatus(String sql, String batchId, String cxf, String cibf,
                                                 LocalDateTime generatedAt) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery(sql)
             .setParameter("cxf",  cxf)
             .setParameter("cibf", cibf)
             .setParameter("gat",  Timestamp.valueOf(generatedAt))
             .setParameter("bid",  batchId)
             .executeUpdate();
            session.getTransaction().commit();
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "executeUpdateBatchCxfCibfStatus failed for " + batchId, exception);
            throw new RuntimeException("DB update failed", exception);
        }
    }

    /**
     * Internal helper to resolve readable reason from database status code.
     *
     * @param status batch status code
     * @return readable status reason
     */
    private String resolvePendingReasonText(String status) {
        if ("FILE_GENERATION_PENDING".equals(status)) return "Ready for File Generation";
        if ("SCAN_DONE".equals(status))  return "Awaiting Verifier I approval";
        if ("VER1_DONE".equals(status))  return "Awaiting Verifier II approval";
        return "";
    }

    // ── Type coercions ─────────────────────────────────────

    /**
     * Coerces database object representation into a Java String.
     *
     * @param inputObject raw object
     * @return string representation
     */
    private static String coerceToString(Object inputObject)           { return inputObject != null ? inputObject.toString() : ""; }

    /**
     * Coerces database numeric object representation into a Java Long.
     *
     * @param inputObject raw object
     * @return java Long representation
     */
    private static Long   coerceToLong(Object inputObject)        { return inputObject != null ? ((Number) inputObject).longValue() : null; }

    /**
     * Coerces database object representation into a raw byte array.
     *
     * @param inputObject raw object
     * @return byte array
     */
    private static byte[] coerceToBytes(Object inputObject)       { return (inputObject instanceof byte[]) ? (byte[]) inputObject : null; }

    /**
     * Coerces database object representation into a Java BigDecimal.
     *
     * @param inputObject raw object
     * @return java BigDecimal
     */
    private static BigDecimal coerceToBigDecimal(Object inputObject) {
        if (inputObject == null) return BigDecimal.ZERO;
        return (inputObject instanceof BigDecimal) ? (BigDecimal) inputObject : new BigDecimal(inputObject.toString());
    }

    /**
     * Coerces database date/time object representation into a Java LocalDateTime.
     *
     * @param inputObject raw object
     * @return java LocalDateTime
     */
    private static LocalDateTime coerceToLocalDateTime(Object inputObject) {
        if (inputObject == null) return null;
        if (inputObject instanceof Timestamp) return ((Timestamp) inputObject).toLocalDateTime();
        if (inputObject instanceof LocalDateTime) return (LocalDateTime) inputObject;
        return null;
    }
}