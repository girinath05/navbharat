package com.cts.outward.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.Session;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.util.HibernateUtil;

/**
 * Verification2DAOImpl
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
 *   setIqaStatus, setVerStatus, setStatus,
 *   setDuplicate(boolean), setFrontImageBytes, setRearImageBytes,
 *   setCreatedAt, setUpdatedAt
 *
 * PIGGYBACK ENCODING (no model changes):
 *   iqa_status field is used to carry "ver_action" value from DB
 *   because iqa_status is not displayed as a column in this screen.
 *   Format stored: "VACTION:<ver_action_value>"
 *   e.g.  "VACTION:REFERRED"  or  "VACTION:ACCEPTED"
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
 *   'accepted' or 'rejected' (case-insensitive) as still pending.
 */
public class Verification2DAOImpl implements Verification2DAO {

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
        // FIX: treat anything that is NOT accepted/rejected as pending
        // This catches NULL, 'PENDING', 'V2_PENDING', 'HV_PENDING', etc.
        "    COUNT(c.id) FILTER (WHERE LOWER(c.ver_status) NOT IN ('accepted','rejected') " +
        "                           OR c.ver_status IS NULL)                          AS pending_count, " +
        // processed = accepted OR rejected only
        "    COUNT(c.id) FILTER (WHERE LOWER(c.ver_status) IN ('accepted','rejected')) AS processed_count " +
        "FROM  public.cts_batches b " +
        "JOIN  public.cts_cheques c " +
        "      ON  c.batch_id = b.batch_id " +
        "      AND (c.ver_level = 'V2' OR c.ver_action = 'REFERRED') " +
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
        "    id, batch_id, cheque_no, account_no, " +
        "    sort_code, transaction_code, " +
        "    amount, cheque_date, " +
        "    ver_action, " +
        "    status, ver_status, " +
        "    duplicate_flag, " +
        "    created_at, updated_at, " +
        "    front_image, rear_image " +
        "FROM  public.cts_cheques " +
        "WHERE batch_id = ? " +
        "  AND (ver_level = 'V2' OR ver_action = 'REFERRED') " +
        "ORDER BY cheque_no ASC";

    private static final String SQL_CHEQUE_BY_ID =
        "SELECT " +
        "    id, batch_id, cheque_no, account_no, " +
        "    sort_code, transaction_code, " +
        "    amount, cheque_date, " +
        "    ver_action, " +
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
        String statusValue = "ACCEPTED".equalsIgnoreCase(action) ? "Accepted" : "Rejected";

        executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_VERIFICATION)) {
                ps.setString(1, action.toUpperCase());
                ps.setString(2, verBy);
                ps.setString(3, (remarks == null || remarks.isBlank()) ? null : remarks.trim());
                ps.setString(4, statusValue);
                ps.setString(5, statusValue);
                ps.setLong(6, id);
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
        m.setPresentingBankId(hvCount + "|" + pendingCount + "|" + processedCount);

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
     *   "VACTION:ACCEPTED"   — already accepted at V2
     *   "VACTION:"           — no ver_action set yet (direct HV)
     */
    private ChequeModel mapCheque(ResultSet rs) throws SQLException {
        ChequeModel m = new ChequeModel();

        m.setId(Long.toString(rs.getLong("id")));
        m.setBatchId(rs.getString("batch_id"));
        m.setChequeNo(rs.getString("cheque_no"));
        m.setAccountNo(rs.getString("account_no"));
        m.setSortCode(rs.getString("sort_code"));
        m.setTransactionCode(rs.getString("transaction_code"));
        m.setAmount(rs.getBigDecimal("amount"));
        m.setChequeDate(rs.getString("cheque_date"));

        // Encode ver_action into iqa_status using "VACTION:" prefix
        String verAction = rs.getString("ver_action");
        m.setIqaStatus("VACTION:" + (verAction != null ? verAction : ""));

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
                        "Verification2DAOImpl error: " + e.getMessage(), e);
                }
            });
        }
    }
}