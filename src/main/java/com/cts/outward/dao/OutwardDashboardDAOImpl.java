/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : OutwardDashboardDAOImpl.java
 *  Package     : com.cts.outward.dao
 *  Description : Hibernate-based implementation of
 *                OutwardDashboardDAO. Self-contained — logic
 *                copied from BatchDAOImpl.getDashboardStats() /
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
 *                  CXF_GENERATED            -> "CxfGenerated"
 *                  DISPATCHED               -> "Dispatched"
 *
 *                Card mapping:
 *                  Card 1 - Total Batches      : ALL batches today
 *                  Card 2 - Verification Stage : "ReadyForVerification" + "VerificationInProgress"
 *                  Card 3 - Verified Batches   : "Verified"
 *                  Card 4 - Dispatched Batches : "CxfGenerated" + "Dispatched"
 * ============================================================
 */

package com.cts.outward.dao;

import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

import org.hibernate.Session;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.model.OutwardDashboardStats;
import com.cts.outward.util.HibernateUtil;

public class OutwardDashboardDAOImpl implements OutwardDashboardDAO {

    private static final Logger LOG = Logger.getLogger(OutwardDashboardDAOImpl.class.getName());

    // ════════════════════════════════════════════════════════════════════
    //  DASHBOARD STATS
    // ════════════════════════════════════════════════════════════════════

    @Override
    public OutwardDashboardStats getDashboardStats(LocalDate date) {

        OutwardDashboardStats stats = new OutwardDashboardStats();

        LocalDate filterDate = (date != null) ? date : LocalDate.now();

        String hql = "SELECT b.status, COUNT(b) "
                   + "FROM BatchEntity b "
                   + "WHERE FUNCTION('DATE', b.createdAt) = :filterDate "
                   + "GROUP BY b.status";

        try (Session session = HibernateUtil.getSession()) {

            List<Object[]> rows = session.createQuery(hql, Object[].class)
                                         .setParameter("filterDate", filterDate)
                                         .list();

            int total = 0;
            for (Object[] row : rows) {
                String status = (String) row[0];
                int    cnt    = ((Long) row[1]).intValue();
                total += cnt;

                if (status == null) continue;

                // Resolve raw DB string to enum constant — no hardcoded strings below this line.
                BatchStatus bs = BatchStatus.fromDb(status);

                // Card 2 : Verification Stage
                // BatchStatus.READY_FOR_VERIFICATION.db()   = "ReadyForVerification"
                // BatchStatus.VERIFICATION_IN_PROGRESS.db() = "VerificationInProgress"
                if (bs == BatchStatus.READY_FOR_VERIFICATION
                 || bs == BatchStatus.VERIFICATION_IN_PROGRESS) {

                    stats.setVerificationBatches(
                            stats.getVerificationBatches() + cnt);
                }

                // Card 3 : Verified Batches
                // BatchStatus.VERIFIED.db() = "Verified"
                else if (bs == BatchStatus.VERIFIED) {

                    stats.setVerifiedBatches(
                            stats.getVerifiedBatches() + cnt);
                }

                // Card 4 : Dispatched Batches
                // BatchStatus.CXF_GENERATED.db() = "CxfGenerated"
                // BatchStatus.DISPATCHED.db()     = "Dispatched"
                else if (bs == BatchStatus.CXF_GENERATED
                      || bs == BatchStatus.DISPATCHED) {

                    stats.setDispatchedBatches(
                            stats.getDispatchedBatches() + cnt);
                }

                // DRAFT / PENDING (VerificationInProgressAtMaker) go into totalBatches only
            }
            stats.setTotalBatches(total);

        } catch (Exception ex) {
            LOG.severe("getDashboardStats(date) error: " + ex.getMessage());
        }

        return stats;
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTERED BATCH LIST
    // ════════════════════════════════════════════════════════════════════

    @Override
    public List<BatchEntity> getBatchesFiltered(String batchIdLike,
                                                String status,
                                                LocalDate date) {

        StringBuilder hql = new StringBuilder("FROM BatchEntity b WHERE 1=1 ");
        if (batchIdLike != null) hql.append("AND b.batchId LIKE :batchId ");
        if (status      != null) hql.append("AND b.status = :status ");
        if (date        != null) hql.append("AND FUNCTION('DATE', b.createdAt) = :date ");
        hql.append("ORDER BY b.createdAt DESC");

        try (Session session = HibernateUtil.getSession()) {

            var query = session.createQuery(hql.toString(), BatchEntity.class);

            if (batchIdLike != null) query.setParameter("batchId", "%" + batchIdLike + "%");
            if (status      != null) query.setParameter("status",  status);
            if (date        != null) query.setParameter("date",    date);

            return query.list();

        } catch (Exception ex) {
            LOG.severe("getBatchesFiltered error: " + ex.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  OUTWARD DASHBOARD — BATCH-LEVEL CHEQUE COUNTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Count of cheques in a batch that have been processed (decision made).
     * Lifecycle: V1_PENDING → V1_APPROVED → V2_PENDING → V2_APPROVED → Verified/Rejected → CXF_Generated → Exported
     */
    @Override
    public int countSubmittedByBatch(String batchId) {
        // Terminal cheque states per ChequeStatus enum:
        //   ChequeStatus.VERIFIED.db() = "VERIFIED"
        //   ChequeStatus.REJECTED.db() = "REJECTED"
        // Legacy statuses V1_APPROVED / V2_APPROVED / APPROVED / CXF_Generated / Exported
        // are NOT in the ChequeStatus enum and do not exist in this system's DB.
        // Removed to prevent accidental matches against unrelated data.
        try (Session session = HibernateUtil.getSession()) {
            Long result = session.createQuery(
                    "SELECT COUNT(c) FROM ChequeEntity c "
                    + "WHERE c.batchId = :batchId "
                    + "  AND c.status IN ('"
                    + ChequeStatus.VERIFIED.db() + "', '"
                    + ChequeStatus.REJECTED.db() + "')",
                    Long.class)
                    .setParameter("batchId", batchId)
                    .uniqueResult();
            return result != null ? result.intValue() : 0;
        } catch (Exception ex) {
            LOG.severe("countSubmittedByBatch error: " + ex.getMessage());
            return 0;
        }
    }

    /**
     * Count of cheques in a batch still waiting to be verified.
     * Covers both old-style and new-style status values found in DB:
     *   V1_PENDING, V2_PENDING, Ready, Pending
     */
    @Override
    public int countPendingByBatch(String batchId) {
        // Pending cheque states per ChequeStatus enum:
        //   ChequeStatus.V1_PENDING.db() = "V1_PENDING"
        //   ChequeStatus.V2_PENDING.db() = "V2_PENDING"
        //   ChequeStatus.READY.db()      = "Ready"
        //   ChequeStatus.PENDING.db()    = "Pending"
        //   ChequeStatus.SUBMITTED.db()  = "Submitted"  (submitted to verifier queue, not yet actioned)
        try (Session session = HibernateUtil.getSession()) {
            Long result = session.createQuery(
                    "SELECT COUNT(c) FROM ChequeEntity c "
                    + "WHERE c.batchId = :batchId "
                    + "  AND c.status IN ('"
                    + ChequeStatus.V1_PENDING.db()  + "', '"
                    + ChequeStatus.V2_PENDING.db()  + "', '"
                    + ChequeStatus.READY.db()       + "', '"
                    + ChequeStatus.PENDING.db()     + "', '"
                    + ChequeStatus.SUBMITTED.db()   + "')",
                    Long.class)
                    .setParameter("batchId", batchId)
                    .uniqueResult();
            return result != null ? result.intValue() : 0;
        } catch (Exception ex) {
            LOG.severe("countPendingByBatch error: " + ex.getMessage());
            return 0;
        }
    }
}