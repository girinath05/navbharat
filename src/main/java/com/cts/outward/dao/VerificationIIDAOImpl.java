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
import com.cts.util.HibernateUtil;

/**
 * VerificationIIDAOImpl
 *
 * Pure JDBC implementation of VerificationIIDAO.
 * All queries are plain SQL — no HQL or JPQL is used.
 * Uses only the setters available on the original BatchModel and ChequeModel
 * (no model changes are permitted).
 *
 * ── BatchModel setters used ──────────────────────────────────────────────────
 *   setBatchId, setBranchCode, setStatus,
 *   setTotalCheques(int), setExpectedCheques(int),
 *   setTotalAmount, setExpectedAmount,
 *   setCreatedAt, setUpdatedAt
 *
 * ── ChequeModel setters used ─────────────────────────────────────────────────
 *   setId(String), setBatchId, setChequeNo, setAccountNo,
 *   setSortCode, setTransactionCode, setAmount, setChequeDate,
 *   setIqaStatus, setVerStatus, setStatus, setReferred(boolean),
 *   setDuplicate(boolean), setFrontImageBytes, setRearImageBytes,
 *   setCreatedAt, setUpdatedAt
 *
 * ── Piggyback Encoding (no model changes) ────────────────────────────────────
 *   The iqa_status field carries the "ver_action" value from the database,
 *   because iqa_status is not displayed as a column on this screen.
 *   Format: "VACTION:<ver_action_value>"
 *   Examples:
 *     "VACTION:REFERRED"  — cheque was referred from Verification Level I
 *     "VACTION:VERIFIED"  — cheque has already been verified at Level II
 *     "VACTION:"          — no ver_action set yet (direct high-value cheque)
 *   The Composer reads this via getIqaStatus() and strips the prefix.
 *
 * ── Batch Count Encoding (presentingBankId) ───────────────────────────────────
 *   Format: "highValueCount|pendingCount|processedCount|referredCount"
 *   The Composer splits on "|" to extract each individual count.
 *   Status display logic:
 *     pendingCount == 0  →  "VERIFIED"
 *     pendingCount  > 0  →  "VERIFICATION IN PROGRESS"
 *
 * ── Pending Count Logic ───────────────────────────────────────────────────────
 *   Cheques created by the scan or batch workflow may carry ver_status values
 *   such as "V2_PENDING", "HV_PENDING", or other non-null strings that are
 *   still unverified. The pending filter counts anything that is NOT
 *   "verified" or "rejected" (case-insensitive) as pending.
 */
public class VerificationIIDAOImpl implements VerificationIIDAO {

    // ── SQL Constants ────────────────────────────────────────────────────────

    /**
     * Fetches all batches containing at least one high-value or referred cheque,
     * along with aggregated counts for display in the batch list table.
     *
     * pending_count: counts any cheque whose ver_status is NOT "verified" or "rejected"
     *   (catches NULL, "PENDING", "V2_PENDING", "HV_PENDING", and similar values).
     * ref_count: uses is_referred (set permanently by Verification Level I);
     *   this is the reliable source of truth because ver_action is overwritten
     *   when Level II processes the cheque.
     */
    private static final String SQL_FETCH_HIGH_VALUE_BATCHES =
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
        "    COUNT(c.id)                                                              AS high_value_count, " +
        "    COUNT(c.id) FILTER (WHERE LOWER(c.ver_status) NOT IN ('verified','rejected') " +
        "                           OR c.ver_status IS NULL)                          AS pending_count, " +
        "    COUNT(c.id) FILTER (WHERE LOWER(c.ver_status) IN ('verified','rejected')) AS processed_count, " +
        "    COUNT(c.id) FILTER (WHERE c.is_referred = true)                          AS referred_count " +
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

    /**
     * Fetches all cheques for a given batch that belong to Verification Level II
     * or were referred from Verification Level I.
     * ver_action is also selected to encode it into iqa_status for the Flag column.
     */
    private static final String SQL_FETCH_HIGH_VALUE_CHEQUES_FOR_BATCH =
        "SELECT " +
        "    id, batch_id, cheque_no, account_no, payee_account_no, payee_name, " +
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

    /**
     * Fetches a single cheque record by its primary key.
     */
    private static final String SQL_FETCH_CHEQUE_BY_ID =
        "SELECT " +
        "    id, batch_id, cheque_no, account_no, payee_account_no, payee_name, " +
        "    sort_code, transaction_code, " +
        "    amount, amount_in_words, cheque_date, " +
        "    ver_action, is_referred, " +
        "    status, ver_status, " +
        "    duplicate_flag, " +
        "    created_at, updated_at, " +
        "    front_image, rear_image " +
        "FROM  public.cts_cheques " +
        "WHERE id = ?";

    /**
     * Updates the Verification Level II decision columns for a single cheque.
     */
    private static final String SQL_PERSIST_VERIFICATION_DECISION =
        "UPDATE public.cts_cheques " +
        "SET    ver_action  = ?, " +
        "       ver_by      = ?, " +
        "       ver_remarks = ?, " +
        "       ver_status  = ?, " +
        "       status      = ?, " +
        "       updated_at  = NOW() " +
        "WHERE  id = ?";

    /**
     * Counts all cheques in the specified batch (Verification Level I and II combined).
     * Used to determine the batch status after each verification action.
     */
    private static final String SQL_COUNT_ALL_CHEQUES_IN_BATCH =
        "SELECT COUNT(*) FROM public.cts_cheques WHERE batch_id = ?";

    /**
     * Counts cheques in the batch that are fully actioned (VERIFIED or REJECTED).
     * Uses cts_cheques.status as the agreed source of truth.
     * LOWER() ensures safety against any legacy mixed-case data in the database.
     */
    private static final String SQL_COUNT_ACTIONED_CHEQUES_IN_BATCH =
        "SELECT COUNT(*) FROM public.cts_cheques " +
        "WHERE batch_id = ? " +
        "  AND LOWER(status) IN (LOWER('" + ChequeStatus.VERIFIED.db() + "'), " +
                                "LOWER('" + ChequeStatus.REJECTED.db() + "'))";

    /**
     * Updates the status of the specified batch in cts_batches.
     * Status values are sourced from the BatchStatus enum (never hardcoded strings).
     */
    private static final String SQL_UPDATE_BATCH_VERIFICATION_STATUS =
        "UPDATE public.cts_batches " +
        "SET    status     = ?, " +
        "       updated_at = NOW() " +
        "WHERE  batch_id   = ?";

    // ── DAO Method Implementations ───────────────────────────────────────────

    @Override
    public List<BatchModel> fetchHighValueBatches() {
        return executeWithConnection(connection -> {
            List<BatchModel> highValueBatchList = new ArrayList<>();
            try (PreparedStatement preparedStatement = connection.prepareStatement(SQL_FETCH_HIGH_VALUE_BATCHES);
                 ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    highValueBatchList.add(mapResultSetToBatchModel(resultSet));
                }
            }
            return highValueBatchList;
        });
    }

    @Override
    public List<ChequeModel> fetchHighValueChequesForBatch(String batchIdentifier) {
        return executeWithConnection(connection -> {
            List<ChequeModel> highValueChequeList = new ArrayList<>();
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SQL_FETCH_HIGH_VALUE_CHEQUES_FOR_BATCH)) {
                preparedStatement.setString(1, batchIdentifier);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        highValueChequeList.add(mapResultSetToChequeModel(resultSet));
                    }
                }
            }
            return highValueChequeList;
        });
    }

    @Override
    public ChequeModel fetchChequeById(long chequeId) {
        return executeWithConnection(connection -> {
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SQL_FETCH_CHEQUE_BY_ID)) {
                preparedStatement.setLong(1, chequeId);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    return resultSet.next() ? mapResultSetToChequeModel(resultSet) : null;
                }
            }
        });
    }

    @Override
    public void persistVerificationDecision(long chequeId, String verificationAction,
                                            String verifierUsername, String verificationRemarks) {
        // Resolve the action string to a canonical ChequeStatus enum constant.
        // ChequeStatus.VERIFIED.db() = "VERIFIED"
        // ChequeStatus.REJECTED.db() = "REJECTED"
        // Both ver_status and status columns receive the same resolved value.
        ChequeStatus resolvedChequeStatus = ChequeStatus.fromDb(verificationAction);
        String resolvedStatusValue = (resolvedChequeStatus == ChequeStatus.VERIFIED)
                ? ChequeStatus.VERIFIED.db()
                : ChequeStatus.REJECTED.db();

        executeWithConnection(connection -> {
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SQL_PERSIST_VERIFICATION_DECISION)) {
                preparedStatement.setString(1, resolvedStatusValue);   // ver_action
                preparedStatement.setString(2, verifierUsername);
                preparedStatement.setString(3,
                    (verificationRemarks == null || verificationRemarks.isBlank())
                        ? null
                        : verificationRemarks.trim());
                preparedStatement.setString(4, resolvedStatusValue);   // ver_status
                preparedStatement.setString(5, resolvedStatusValue);   // status
                preparedStatement.setLong(6, chequeId);
                preparedStatement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void evaluateAndUpdateBatchVerificationStatus(String batchIdentifier) {
        executeWithConnection(connection -> {
            long totalChequeCount    = 0;
            long actionedChequeCount = 0;

            // Count all cheques in the batch (Verification Level I and II combined)
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SQL_COUNT_ALL_CHEQUES_IN_BATCH)) {
                preparedStatement.setString(1, batchIdentifier);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) totalChequeCount = resultSet.getLong(1);
                }
            }

            // Count cheques that are fully actioned (Verified or Rejected)
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SQL_COUNT_ACTIONED_CHEQUES_IN_BATCH)) {
                preparedStatement.setString(1, batchIdentifier);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) actionedChequeCount = resultSet.getLong(1);
                }
            }

            // Determine the updated batch status using enum database values — never hardcoded strings.
            // BatchStatus.VERIFIED.db()                 = "Verified"
            // BatchStatus.VERIFICATION_IN_PROGRESS.db() = "VerificationInProgress"
            String updatedBatchStatus = (totalChequeCount > 0 && actionedChequeCount >= totalChequeCount)
                    ? BatchStatus.VERIFIED.db()
                    : BatchStatus.VERIFICATION_IN_PROGRESS.db();

            // Persist the updated batch status
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SQL_UPDATE_BATCH_VERIFICATION_STATUS)) {
                preparedStatement.setString(1, updatedBatchStatus);
                preparedStatement.setString(2, batchIdentifier);
                preparedStatement.executeUpdate();
            }

            return null;
        });
    }

    // ── ResultSet Mappers ────────────────────────────────────────────────────

    /**
     * Maps a ResultSet row to a BatchModel instance.
     *
     * Aggregated counts are encoded into presentingBankId as a pipe-delimited string:
     *   "highValueCount|pendingCount|processedCount|referredCount"
     *
     * The Composer derives the display status from pendingCount:
     *   pendingCount == 0  →  "VERIFIED"
     *   pendingCount  > 0  →  "VERIFICATION IN PROGRESS"
     */
    private BatchModel mapResultSetToBatchModel(ResultSet resultSet) throws SQLException {
        BatchModel batchModel = new BatchModel();

        batchModel.setBatchId(resultSet.getString("batch_id"));
        batchModel.setBranchCode(resultSet.getString("branch_code"));
        batchModel.setStatus(resultSet.getString("status"));
        batchModel.setTotalCheques(resultSet.getInt("total_cheques"));
        batchModel.setExpectedCheques(resultSet.getInt("expected_cheques"));
        batchModel.setTotalAmount(resultSet.getBigDecimal("total_amount"));
        batchModel.setExpectedAmount(resultSet.getBigDecimal("expected_amount"));

        Timestamp createdAtTimestamp = resultSet.getTimestamp("created_at");
        if (createdAtTimestamp != null) batchModel.setCreatedAt(createdAtTimestamp.toLocalDateTime());

        Timestamp updatedAtTimestamp = resultSet.getTimestamp("updated_at");
        if (updatedAtTimestamp != null) batchModel.setUpdatedAt(updatedAtTimestamp.toLocalDateTime());

        long highValueCount  = resultSet.getLong("high_value_count");
        long pendingCount    = resultSet.getLong("pending_count");
        long processedCount  = resultSet.getLong("processed_count");
        long referredCount   = resultSet.getLong("referred_count");

        batchModel.setPresentingBankId(
            highValueCount + "|" + pendingCount + "|" + processedCount + "|" + referredCount
        );

        return batchModel;
    }

    /**
     * Maps a ResultSet row to a ChequeModel instance.
     *
     * Piggyback encoding: ver_action is stored in iqa_status as "VACTION:<value>"
     * so the Composer can read it for the Flag column without any model changes.
     *
     * Example values stored in iqa_status:
     *   "VACTION:REFERRED"  — cheque was referred from Verification Level I via Refer action
     *   "VACTION:VERIFIED"  — cheque has already been verified at Verification Level II
     *   "VACTION:"          — no ver_action set yet (direct high-value cheque)
     */
    private ChequeModel mapResultSetToChequeModel(ResultSet resultSet) throws SQLException {
        ChequeModel chequeModel = new ChequeModel();

        chequeModel.setId(Long.toString(resultSet.getLong("id")));
        chequeModel.setBatchId(trimToNull(resultSet.getString("batch_id")));
        chequeModel.setChequeNo(trimToNull(resultSet.getString("cheque_no")));

        // account_no is the MICR account number displayed in the UI
        chequeModel.setAccountNo(trimToNull(resultSet.getString("account_no")));

        // payee_account_no is the CBS lookup key (same column used by Verification Level I).
        // Using account_no here would cause CBS lookups to return HTTP 404 (column mismatch).
        chequeModel.setPayeeAccountNo(trimToNull(resultSet.getString("payee_account_no")));
        chequeModel.setPayeeName(trimToNull(resultSet.getString("payee_name")));
        chequeModel.setSortCode(trimToNull(resultSet.getString("sort_code")));
        chequeModel.setTransactionCode(trimToNull(resultSet.getString("transaction_code")));
        chequeModel.setAmount(resultSet.getBigDecimal("amount"));
        chequeModel.setAmountInWords(trimToNull(resultSet.getString("amount_in_words")));
        chequeModel.setChequeDate(trimToNull(resultSet.getString("cheque_date")));

        // Encode ver_action into iqa_status using the "VACTION:" piggyback prefix
        String verificationAction = trimToNull(resultSet.getString("ver_action"));
        chequeModel.setIqaStatus("VACTION:" + (verificationAction != null ? verificationAction : ""));

        // is_referred is set permanently by Verification Level I and is never overwritten.
        // This is the reliable source of truth for the Referred flag and count on this screen.
        chequeModel.setReferred(resultSet.getBoolean("is_referred"));

        chequeModel.setStatus(trimToNull(resultSet.getString("status")));
        chequeModel.setVerStatus(trimToNull(resultSet.getString("ver_status")));
        chequeModel.setDuplicate(resultSet.getBoolean("duplicate_flag"));

        Timestamp createdAtTimestamp = resultSet.getTimestamp("created_at");
        if (createdAtTimestamp != null) chequeModel.setCreatedAt(createdAtTimestamp.toLocalDateTime());

        Timestamp updatedAtTimestamp = resultSet.getTimestamp("updated_at");
        if (updatedAtTimestamp != null) chequeModel.setUpdatedAt(updatedAtTimestamp.toLocalDateTime());

        byte[] frontImageBytes = resultSet.getBytes("front_image");
        if (frontImageBytes != null && frontImageBytes.length > 0) {
            chequeModel.setFrontImageBytes(frontImageBytes);
        }

        byte[] rearImageBytes = resultSet.getBytes("rear_image");
        if (rearImageBytes != null && rearImageBytes.length > 0) {
            chequeModel.setRearImageBytes(rearImageBytes);
        }

        return chequeModel;
    }

    // ── Connection Helper ────────────────────────────────────────────────────

    /**
     * Functional interface for a JDBC unit of work that returns a result.
     *
     * @param <T> the return type of the operation
     */
    @FunctionalInterface
    private interface JdbcOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * Obtains a Hibernate-managed JDBC connection, executes the given operation,
     * and returns its result. Wraps any SQLException in a RuntimeException.
     *
     * @param jdbcOperation  The unit of work to execute against the connection
     * @param <T>            The return type of the operation
     * @return The result produced by the operation
     */
    private <T> T executeWithConnection(JdbcOperation<T> jdbcOperation) {
        try (Session hibernateSession = HibernateUtil.getSession()) {
            return hibernateSession.doReturningWork(connection -> {
                try {
                    return jdbcOperation.execute(connection);
                } catch (SQLException sqlException) {
                    throw new RuntimeException(
                        "VerificationIIDAOImpl: Database operation failed. " +
                        sqlException.getMessage(), sqlException);
                }
            });
        }
    }

    /**
     * Trims whitespace from the given string value.
     * Returns null if the input is null.
     *
     * @param rawValue  The raw string value from the ResultSet
     * @return Trimmed string, or null if the input was null
     */
    private static String trimToNull(String rawValue) {
        return rawValue != null ? rawValue.trim() : null;
    }
}