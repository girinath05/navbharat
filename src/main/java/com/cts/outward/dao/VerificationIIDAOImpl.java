package com.cts.outward.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.Session;

import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.util.HibernateUtil;

/**
 * VerificationIIDAOImpl
 *
 * JDBC implementation — uses only methods that exist in the
 * ORIGINAL BatchModel and ChequeModel (no model changes allowed).
 *
 * BatchModel available setters used here:
 *   setBatchId, setBranchCode, setStatus,
 *   setTotalCheques(int), setExpectedCheques(int),
 *   setTotalAmount, setExpectedAmount,
 *   setCreatedAt, setUpdatedAt
 *
 * ChequeModel available setters used here:
 *   setId(String), setBatchId, setChequeNo, setAccountNo,
 *   setSortCode, setTransactionCode, setAmount, setChequeDate,
 *   setIqaStatus, setVerStatus, setStatus, setReferred(boolean),
 *   setDuplicate(boolean), setFrontImageBytes, setRearImageBytes,
 *   setCreatedAt, setUpdatedAt
 *
 * PIGGYBACK ENCODING (no model changes):
 *   iqa_status field is used to carry "ver_action" value from DB
 *   because iqa_status is not displayed as a column in this screen.
 *   Format stored: "VACTION:<ver_action_value>"
 *   e.g.  "VACTION:REFERRED"  or  "VACTION:VERIFIED"
 *   Composer reads this via getIqaStatus() and strips the prefix.
 *
 * BATCH STATUS ENCODING (presentingBankId):
 *   Format: "hv_count|pending_count|processed_count"
 *   Composer splits on "|" to get each number.
 *   Batch STATUS column: if pending_count == 0 => "VERIFIED"
 *                        else                  => "VERIFICATION IN PROGRESS"
 *
 * FIX — pending_count logic:
 *   OLD filter only matched ver_status = 'PENDING' OR NULL.
 *   Cheques created by the scan/batch workflow may have ver_status values
 *   like 'V2_PENDING', 'HV_PENDING', or other non-null strings that are
 *   still unverified. The new filter counts anything that is NOT
 *   'verified' or 'rejected' (case-insensitive) as still pending.
 */
public class VerificationIIDAOImpl implements VerificationIIDAO {

    // ── SQL ──────────────────────────────────────────────────────────────────

    private static final String SQL_HV_BATCHES =
        "SELECT " +
        "    b.batch_id, " +
        "    b.branch_code, " +
        "    b.status, " +
        "    b.total_cheques, " +
        "    b.expected_cheques, " +
        "    b.total_amount, " +
        "    b.expected_amount, " +
        "    b.created_at, " +
        "    b.updated_at, " +
        "    COUNT(c.id)                                                              AS hv_count, " +
        // FIX: treat anything that is NOT verified/rejected as pending
        // This catches NULL, 'PENDING', 'V2_PENDING', 'HV_PENDING', etc.
        "    COUNT(c.id) FILTER (WHERE LOWER(c.ver_status) NOT IN ('verified','rejected') " +
        "                           OR c.ver_status IS NULL)                          AS pending_count, " +
        // processed = verified OR rejected only
        "    COUNT(c.id) FILTER (WHERE LOWER(c.ver_status) IN ('verified','rejected')) AS processed_count, " +
        // FIX: use the permanent is_referred flag (set once by V1) instead of
        // ver_action, because ver_action gets overwritten to VERIFIED/REJECTED
        // once V2 acts on the cheque — is_referred never changes.
        "    COUNT(c.id) FILTER (WHERE c.is_referred = true)                            AS ref_count " +
        "FROM  public.cts_batches b " +
        "JOIN  public.cts_cheques c " +
        "      ON  c.batch_id = b.batch_id " +
        "      AND (c.ver_level = 'V2' OR c.is_referred = true) " +
        "GROUP BY " +
        "    b.batch_id, b.branch_code, b.status, " +
        "    b.total_cheques, b.expected_cheques, " +
        "    b.total_amount, b.expected_amount, " +
        "    b.created_at, b.updated_at " +
        "HAVING COUNT(c.id) > 0 " +
        "ORDER BY b.created_at DESC";

    /*
     * Fetches cheques that belong to V2 OR were referred from V1.
     * ver_action is also selected so we can encode it into iqa_status
     * for the "Flag" column display without touching ChequeModel.
     */
    private static final String SQL_HV_CHEQUES_FOR_BATCH =
        "SELECT " +
        "    id, batch_id, cheque_no, account_no, payee_name, " +
        "    sort_code, transaction_code, " +
        "    amount, amount_in_words, cheque_date, " +
        "    ver_action, is_referred, " +
        "    status, ver_status, " +
        "    duplicate_flag, " +
        "    created_at, updated_at, " +
        "    front_image, rear_image " +
        "FROM  public.cts_cheques " +
        "WHERE batch_id = ? " +
        "  AND (ver_level = 'V2' OR is_referred = true) " +
        "ORDER BY cheque_no ASC";

    private static final String SQL_CHEQUE_BY_ID =
        "SELECT " +
        "    id, batch_id, cheque_no, account_no, payee_name, " +
        "    sort_code, transaction_code, " +
        "    amount, amount_in_words, cheque_date, " +
        "    ver_action, is_referred, " +
        "    status, ver_status, " +
        "    duplicate_flag, " +
        "    created_at, updated_at, " +
        "    front_image, rear_image " +
        "FROM  public.cts_cheques " +
        "WHERE id = ?";

    private static final String SQL_UPDATE_VERIFICATION =
        "UPDATE public.cts_cheques " +
        "SET    ver_action  = ?, " +
        "       ver_by      = ?, " +
        "       ver_remarks = ?, " +
        "       ver_status  = ?, " +
        "       status      = ?, " +
        "       updated_at  = NOW() " +
        "WHERE  id = ?";

    /**
     * Count ALL cheques in the batch (V1 + V2 combined).
     * Used to decide batch status after each verification action.
     */
    private static final String SQL_COUNT_ALL_CHEQUES_IN_BATCH =
        "SELECT COUNT(*) FROM public.cts_cheques WHERE batch_id = ?";

    /**
     * Count cheques that are fully actioned (VERIFIED OR REJECTED) in the batch.
     * Uses cts_cheques.status — the agreed source of truth.
     * Tied to ChequeStatus enum: VERIFIED.db()="VERIFIED", REJECTED.db()="REJECTED".
     * LOWER() makes this safe against any legacy mixed-case data in the DB.
     */
    private static final String SQL_COUNT_ACTIONED_CHEQUES_IN_BATCH =
        "SELECT COUNT(*) FROM public.cts_cheques " +
        "WHERE batch_id = ? " +
        "  AND LOWER(status) IN (LOWER('" + ChequeStatus.VERIFIED.db() + "'), " +
                                "LOWER('" + ChequeStatus.REJECTED.db() + "'))";

    /**
     * Update cts_batches.status for the given batch.
     * "VerificationInProgress" and "Verified" are exact dbValues from BatchStatus enum.
     */
    private static final String SQL_UPDATE_BATCH_STATUS =
        "UPDATE public.cts_batches " +
        "SET    status     = ?, " +
        "       updated_at = NOW() " +
        "WHERE  batch_id   = ?";

    // ── DAO methods ──────────────────────────────────────────────────────────

    @Override
    public List<BatchModel> getHighValueBatches() {
        return executeWithConnection(conn -> {
            List<BatchModel> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SQL_HV_BATCHES);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBatch(rs));
                }
            }
            return list;
        });
    }

    @Override
    public List<ChequeModel> getHighValueChequesForBatch(String batchId) {
        return executeWithConnection(conn -> {
            List<ChequeModel> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SQL_HV_CHEQUES_FOR_BATCH)) {
                ps.setString(1, batchId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapCheque(rs));
                    }
                }
            }
            return list;
        });
    }

    @Override
    public ChequeModel getChequeById(long id) {
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SQL_CHEQUE_BY_ID)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapCheque(rs) : null;
                }
            }
        });
    }

    @Override
    public void updateVerification(long id, String action, String verBy, String remarks) {
        // Resolve the action string to a canonical ChequeStatus enum constant.
        // ChequeStatus.VERIFIED.db() = "VERIFIED"
        // ChequeStatus.REJECTED.db() = "REJECTED"
        // Both ver_status and status columns receive the same value.
        ChequeStatus resolved = ChequeStatus.fromDb(action);
        String statusValue = (resolved == ChequeStatus.VERIFIED)
                ? ChequeStatus.VERIFIED.db()
                : ChequeStatus.REJECTED.db();

        executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_VERIFICATION)) {
                ps.setString(1, statusValue);        // ver_action — canonical db value
                ps.setString(2, verBy);
                ps.setString(3, (remarks == null || remarks.isBlank()) ? null : remarks.trim());
                ps.setString(4, statusValue);        // ver_status
                ps.setString(5, statusValue);        // status
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Checks cts_cheques.status for ALL cheques in the batch (V1 + V2)
     * and updates cts_batches.status accordingly:
     *
     *   All actioned  → "Verified"               (BatchStatus.VERIFIED.db())
     *   Partially done → "VerificationInProgress" (BatchStatus.VERIFICATION_IN_PROGRESS.db())
     */
    @Override
    public void checkAndUpdateBatchStatus(String batchId) {
        executeWithConnection(conn -> {
            long totalCount    = 0;
            long actionedCount = 0;

            // Count all cheques in the batch
            try (PreparedStatement ps = conn.prepareStatement(SQL_COUNT_ALL_CHEQUES_IN_BATCH)) {
                ps.setString(1, batchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) totalCount = rs.getLong(1);
                }
            }

            // Count actioned (Verified or Rejected) cheques in the batch
            try (PreparedStatement ps = conn.prepareStatement(SQL_COUNT_ACTIONED_CHEQUES_IN_BATCH)) {
                ps.setString(1, batchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) actionedCount = rs.getLong(1);
                }
            }

            // Determine new batch status using enum db values — never hardcoded strings.
            // BatchStatus.VERIFIED.db()                  = "Verified"
            // BatchStatus.VERIFICATION_IN_PROGRESS.db()  = "VerificationInProgress"
            String newBatchStatus = (totalCount > 0 && actionedCount >= totalCount)
                    ? BatchStatus.VERIFIED.db()
                    : BatchStatus.VERIFICATION_IN_PROGRESS.db();

            // Update cts_batches.status
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_BATCH_STATUS)) {
                ps.setString(1, newBatchStatus);
                ps.setString(2, batchId);
                ps.executeUpdate();
            }

            return null;
        });
    }

    // ── Mappers ──────────────────────────────────────────────────────────────

    /**
     * Maps ResultSet → BatchModel.
     *
     * hv_count | pending_count | processed_count  encoded into presentingBankId.
     * Composer derives STATUS display from pending_count:
     *   pending_count == 0  →  "VERIFIED"
     *   pending_count  > 0  →  "VERIFICATION IN PROGRESS"
     */
    private BatchModel mapBatch(ResultSet rs) throws SQLException {
        BatchModel m = new BatchModel();

        m.setBatchId(rs.getString("batch_id"));
        m.setBranchCode(rs.getString("branch_code"));
        m.setStatus(rs.getString("status"));
        m.setTotalCheques(rs.getInt("total_cheques"));
        m.setExpectedCheques(rs.getInt("expected_cheques"));
        m.setTotalAmount(rs.getBigDecimal("total_amount"));
        m.setExpectedAmount(rs.getBigDecimal("expected_amount"));

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) m.setCreatedAt(ca.toLocalDateTime());

        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) m.setUpdatedAt(ua.toLocalDateTime());

        long hvCount        = rs.getLong("hv_count");
        long pendingCount   = rs.getLong("pending_count");
        long processedCount = rs.getLong("processed_count");
        long refCount       = rs.getLong("ref_count");
        m.setPresentingBankId(hvCount + "|" + pendingCount + "|" + processedCount + "|" + refCount);

        return m;
    }

    /**
     * Maps ResultSet → ChequeModel.
     *
     * PIGGYBACK: ver_action is stored in iqa_status as "VACTION:<value>"
     * so the composer can read it for the FLAG column without any model change.
     *
     * Example values stored in iqa_status:
     *   "VACTION:REFERRED"   — cheque came from V1 via REFER
     *   "VACTION:VERIFIED"   — already verified at V2
     *   "VACTION:"           — no ver_action set yet (direct HV)
     */
    private ChequeModel mapCheque(ResultSet rs) throws SQLException {
        ChequeModel m = new ChequeModel();

        m.setId(Long.toString(rs.getLong("id")));
        m.setBatchId(rs.getString("batch_id"));
        m.setChequeNo(rs.getString("cheque_no"));
        m.setAccountNo(rs.getString("account_no"));
        m.setPayeeName(rs.getString("payee_name"));
        m.setSortCode(rs.getString("sort_code"));
        m.setTransactionCode(rs.getString("transaction_code"));
        m.setAmount(rs.getBigDecimal("amount"));
        m.setAmountInWords(rs.getString("amount_in_words"));
        m.setChequeDate(rs.getString("cheque_date"));

        // Encode ver_action into iqa_status using "VACTION:" prefix
        // (kept as-is — other modules may still rely on this encoding)
        String verAction = rs.getString("ver_action");
        m.setIqaStatus("VACTION:" + (verAction != null ? verAction : ""));

        // Permanent referred flag — set once by V1, does NOT change when
        // V2 verifies/rejects the cheque. This is now the source of truth
        // for the REF flag/count on this screen.
        m.setReferred(rs.getBoolean("is_referred"));

        m.setStatus(rs.getString("status"));
        m.setVerStatus(rs.getString("ver_status"));
        m.setDuplicate(rs.getBoolean("duplicate_flag"));

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) m.setCreatedAt(ca.toLocalDateTime());

        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) m.setUpdatedAt(ua.toLocalDateTime());

        byte[] frontBytes = rs.getBytes("front_image");
        if (frontBytes != null && frontBytes.length > 0) {
            m.setFrontImageBytes(frontBytes);
        }

        byte[] rearBytes = rs.getBytes("rear_image");
        if (rearBytes != null && rearBytes.length > 0) {
            m.setRearImageBytes(rearBytes);
        }

        return m;
    }

    // ── Connection helper ────────────────────────────────────────────────────

    @FunctionalInterface
    private interface JdbcWork<T> {
        T execute(Connection conn) throws SQLException;
    }

    private <T> T executeWithConnection(JdbcWork<T> work) {
        try (Session session = HibernateUtil.getSession()) {
            return session.doReturningWork(conn -> {
                try {
                    return work.execute(conn);
                } catch (SQLException e) {
                    throw new RuntimeException(
                        "VerificationIIDAOImpl error: " + e.getMessage(), e);
                }
            });
        }
    }
}