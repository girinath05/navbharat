/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : OutwardDashboardDAOImpl.java
 *  Package     : com.cts.outward.dao
 *  Description : Native-SQL implementation of OutwardDashboardDAO.
 *                Self-contained — logic copied from
 *                BatchDAOImpl.getDashboardStats() /
 *                getBatchesFiltered() and
 *                ChequeDAOImpl.countSubmittedByBatch() /
 *                countPendingByBatch(), unchanged.
 *
 *                BatchStatus enum dbValues used for matching:
 *                  DRAFT                    -> "Draft"
 *                  PENDING                  -> "VerificationInProgressAtMaker"
 *                  READY_FOR_VERIFICATION   -> "ReadyForVerification"
 *                  VERIFICATION_IN_PROGRESS -> "VerificationInProgress"
 *                  VERIFIED                 -> "Verified"
 *                  CXF_CIBF_GENERATED       -> "CxfGenerated"
 *                  DISPATCHED               -> "Dispatched"
 *
 *                Card mapping:
 *                  Card 1 - Total Batches      : ALL batches today
 *                  Card 2 - Verification Stage : "ReadyForVerification" + "VerificationInProgress"
 *                  Card 3 - Verified Batches   : "Verified"
 *                  Card 4 - Dispatched Batches : "CxfGenerated" + "Dispatched"
 *
 *                Fixes applied:
 *                  1. Table names corrected: batches   → public.cts_batches
 *                                            cheques   → public.cts_cheques
 *                  2. Column name corrected: batch_id  → batch_id (cts_cheques FK column)
 *                  3. Named parameter :filterDate for LocalDate requires
 *                     java.sql.Date binding — fixed via java.sql.Date.valueOf()
 *                  4. NativeQuery for COUNT queries does not map to an entity —
 *                     removed BatchEntity.class type from COUNT createNativeQuery calls
 * ============================================================
 */

package com.cts.outward.dao;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.model.OutwardDashboardStats;
import com.cts.util.HibernateUtil;

public class OutwardDashboardDAOImpl implements OutwardDashboardDAO {

    private static final Logger LOG = Logger.getLogger(OutwardDashboardDAOImpl.class.getName());

    // ════════════════════════════════════════════════════════════════════
    //  DASHBOARD STATS
    //  FIX: Table name was "batches" — corrected to "public.cts_batches"
    //       LocalDate passed directly to native SQL doesn't bind correctly
    //       in all JDBC drivers — converted to java.sql.Date via valueOf()
    // ════════════════════════════════════════════════════════════════════

    @Override
    public OutwardDashboardStats getDashboardStats(LocalDate date) {

        OutwardDashboardStats dashboardStats = new OutwardDashboardStats();

        LocalDate filterDate = (date != null) ? date : LocalDate.now();

        String dashboardStatsSql =
            "SELECT status, COUNT(*) AS batch_count " +
            "FROM   public.cts_batches " +
            "WHERE  DATE(created_at) = :filterDate " +
            "GROUP  BY status";

        try (Session session = HibernateUtil.getSession()) {

            List<Object[]> statusCountRows = session.createNativeQuery(dashboardStatsSql, Object[].class)
                                                    .setParameter("filterDate", Date.valueOf(filterDate))
                                                    .list();

            int totalBatchCount = 0;

            for (Object[] statusCountRow : statusCountRows) {
                String rawStatus  = (String) statusCountRow[0];
                int    batchCount = ((Number) statusCountRow[1]).intValue();
                totalBatchCount  += batchCount;

                if (rawStatus == null) continue;

                // Resolve raw DB string → enum constant
                // No hardcoded status strings below this line
                BatchStatus batchStatus = BatchStatus.fromDb(rawStatus);

                // Card 2 : Verification Stage
                // BatchStatus.READY_FOR_VERIFICATION.db()   = "ReadyForVerification"
                // BatchStatus.VERIFICATION_IN_PROGRESS.db() = "VerificationInProgress"
                if (batchStatus == BatchStatus.READY_FOR_VERIFICATION
                 || batchStatus == BatchStatus.VERIFICATION_IN_PROGRESS) {

                    dashboardStats.setVerificationBatches(
                            dashboardStats.getVerificationBatches() + batchCount);
                }

                // Card 3 : Verified Batches
                // BatchStatus.VERIFIED.db() = "Verified"
                else if (batchStatus == BatchStatus.VERIFIED) {

                    dashboardStats.setVerifiedBatches(
                            dashboardStats.getVerifiedBatches() + batchCount);
                }

                // Card 4 : Dispatched Batches
                // BatchStatus.CXF_CIBF_GENERATED.db() = "CxfGenerated"
                // BatchStatus.DISPATCHED.db()          = "Dispatched"
                else if (batchStatus == BatchStatus.CXF_CIBF_GENERATED
                      || batchStatus == BatchStatus.DISPATCHED) {

                    dashboardStats.setDispatchedBatches(
                            dashboardStats.getDispatchedBatches() + batchCount);
                }

                // DRAFT / PENDING (VerificationInProgressAtMaker) — totalBatches only
            }

            dashboardStats.setTotalBatches(totalBatchCount);

        } catch (Exception ex) {
            LOG.severe("getDashboardStats error: " + ex.getMessage());
        }

        return dashboardStats;
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTERED BATCH LIST
    //  FIX: Table name was "batches" — corrected to "public.cts_batches"
    //       LocalDate binding corrected to java.sql.Date.valueOf()
    //       NativeQuery mapped to BatchEntity using @SqlResultSetMapping
    //       or addEntity() — using addEntity() here since no mapping defined
    // ════════════════════════════════════════════════════════════════════

    @Override
    public List<BatchEntity> getBatchesFiltered(String batchIdLike,
                                                 String status,
                                                 LocalDate date) {

        StringBuilder batchFilterSql = new StringBuilder(
            "SELECT * FROM public.cts_batches WHERE 1=1 ");

        if (batchIdLike != null) batchFilterSql.append("AND batch_id LIKE :batchId ");
        if (status      != null) batchFilterSql.append("AND status = :status ");
        if (date        != null) batchFilterSql.append("AND DATE(created_at) = :date ");

        batchFilterSql.append("ORDER BY created_at DESC");

        try (Session session = HibernateUtil.getSession()) {

            NativeQuery<BatchEntity> batchFilterQuery =
                    session.createNativeQuery(batchFilterSql.toString(), BatchEntity.class);

            if (batchIdLike != null) batchFilterQuery.setParameter("batchId", "%" + batchIdLike + "%");
            if (status      != null) batchFilterQuery.setParameter("status",  status);
            if (date        != null) batchFilterQuery.setParameter("date",    Date.valueOf(date));

            return batchFilterQuery.list();

        } catch (Exception ex) {
            LOG.severe("getBatchesFiltered error: " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  BATCH-LEVEL CHEQUE COUNTS
    //  FIX: Table name was "cheques" — corrected to "public.cts_cheques"
    //       COUNT NativeQuery must NOT pass an entity class (no entity mapping)
    //       — removed BatchEntity.class, cast result to Number safely
    // ════════════════════════════════════════════════════════════════════

    /**
     * Count of cheques in a batch that have been processed (decision made).
     * Terminal states: VERIFIED, REJECTED.
     */
    @Override
    public int countSubmittedByBatch(String batchId) {
        // Terminal cheque states per ChequeStatus enum:
        //   ChequeStatus.VERIFIED.db() = "VERIFIED"
        //   ChequeStatus.REJECTED.db() = "REJECTED"
        String submittedCountSql =
            "SELECT COUNT(*) " +
            "FROM   public.cts_cheques " +
            "WHERE  batch_id = :batchId " +
            "  AND  status IN (:verifiedStatus, :rejectedStatus)";

        try (Session session = HibernateUtil.getSession()) {

            Number submittedCount = (Number) session.createNativeQuery(submittedCountSql)
                    .setParameter("batchId",        batchId)
                    .setParameter("verifiedStatus", ChequeStatus.VERIFIED.db())
                    .setParameter("rejectedStatus", ChequeStatus.REJECTED.db())
                    .uniqueResult();

            return submittedCount != null ? submittedCount.intValue() : 0;

        } catch (Exception ex) {
            LOG.severe("countSubmittedByBatch error: " + ex.getMessage());
            return 0;
        }
    }

    /**
     * Count of cheques in a batch still waiting to be verified.
     * Covers all pending states: V1_PENDING, V2_PENDING, Ready, Pending, Submitted.
     */
    @Override
    public int countPendingByBatch(String batchId) {
        // Pending cheque states per ChequeStatus enum:
        //   ChequeStatus.V1_PENDING.db() = "V1_PENDING"
        //   ChequeStatus.V2_PENDING.db() = "V2_PENDING"
        //   ChequeStatus.READY.db()      = "Ready"
        //   ChequeStatus.PENDING.db()    = "Pending"
        //   ChequeStatus.SUBMITTED.db()  = "Submitted"
        String pendingCountSql =
            "SELECT COUNT(*) " +
            "FROM   public.cts_cheques " +
            "WHERE  batch_id = :batchId " +
            "  AND  status IN (:v1PendingStatus, :v2PendingStatus, " +
            "                  :readyStatus, :pendingStatus, :submittedStatus)";

        try (Session session = HibernateUtil.getSession()) {

            Number pendingCount = (Number) session.createNativeQuery(pendingCountSql)
                    .setParameter("batchId",         batchId)
                    .setParameter("v1PendingStatus", ChequeStatus.V1_PENDING.db())
                    .setParameter("v2PendingStatus", ChequeStatus.V2_PENDING.db())
                    .setParameter("readyStatus",     ChequeStatus.READY.db())
                    .setParameter("pendingStatus",   ChequeStatus.PENDING.db())
                    .setParameter("submittedStatus", ChequeStatus.SUBMITTED.db())
                    .uniqueResult();

            return pendingCount != null ? pendingCount.intValue() : 0;

        } catch (Exception ex) {
            LOG.severe("countPendingByBatch error: " + ex.getMessage());
            return 0;
        }
    }
}