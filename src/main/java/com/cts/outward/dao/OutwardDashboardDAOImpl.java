package com.cts.outward.dao;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.enums.ChequeStatus;
import com.cts.util.HibernateUtil;

public class OutwardDashboardDAOImpl implements OutwardDashboardDAO {

    private static final Logger LOG = Logger.getLogger(OutwardDashboardDAOImpl.class.getName());

    @Override
    public List<Object[]> getRawStatusCountsByDate(LocalDate date) {

        LocalDate filterDate = (date != null) ? date : LocalDate.now();

        String sql =
            "SELECT status, COUNT(*) AS batch_count " +
            "FROM   public.cts_batches " +
            "WHERE  DATE(created_at) = :filterDate " +
            "GROUP  BY status";

        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(sql, Object[].class)
                          .setParameter("filterDate", Date.valueOf(filterDate))
                          .list();
        } catch (Exception ex) {
            LOG.severe("getRawStatusCountsByDate error: " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<BatchEntity> getBatchesFiltered(String batchIdLike, String status, LocalDate date) {

        // Build query dynamically based on which filters are provided
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM public.cts_batches WHERE 1=1 ");

        if (batchIdLike != null) sql.append("AND batch_id LIKE :batchId ");
        if (status      != null) sql.append("AND status = :status ");
        if (date        != null) sql.append("AND DATE(created_at) = :date ");

        sql.append("ORDER BY created_at DESC");

        try (Session session = HibernateUtil.getSession()) {

            NativeQuery<BatchEntity> query =
                    session.createNativeQuery(sql.toString(), BatchEntity.class);

            if (batchIdLike != null) query.setParameter("batchId", "%" + batchIdLike + "%");
            if (status      != null) query.setParameter("status",  status);
            if (date        != null) query.setParameter("date",    Date.valueOf(date));

            return query.list();

        } catch (Exception ex) {
            LOG.severe("getBatchesFiltered error: " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public int countSubmittedByBatch(String batchId) {

        String sql =
            "SELECT COUNT(*) FROM public.cts_cheques " +
            "WHERE  batch_id = :batchId " +
            "  AND  status IN (:verifiedStatus, :rejectedStatus)";

        try (Session session = HibernateUtil.getSession()) {

            Number count = (Number) session.createNativeQuery(sql)
                    .setParameter("batchId",        batchId)
                    .setParameter("verifiedStatus", ChequeStatus.VERIFIED.db())
                    .setParameter("rejectedStatus", ChequeStatus.REJECTED.db())
                    .uniqueResult();

            return count != null ? count.intValue() : 0;

        } catch (Exception ex) {
            LOG.severe("countSubmittedByBatch error: " + ex.getMessage());
            return 0;
        }
    }

    @Override
    public int countPendingByBatch(String batchId) {

        String sql =
            "SELECT COUNT(*) FROM public.cts_cheques " +
            "WHERE  batch_id = :batchId " +
            "  AND  status IN (:v1PendingStatus, :v2PendingStatus, " +
            "                  :readyStatus, :pendingStatus, :submittedStatus)";

        try (Session session = HibernateUtil.getSession()) {

            Number count = (Number) session.createNativeQuery(sql)
                    .setParameter("batchId",         batchId)
                    .setParameter("v1PendingStatus", ChequeStatus.V1_PENDING.db())
                    .setParameter("v2PendingStatus", ChequeStatus.V2_PENDING.db())
                    .setParameter("readyStatus",     ChequeStatus.READY.db())
                    .setParameter("pendingStatus",   ChequeStatus.PENDING.db())
                    .setParameter("submittedStatus", ChequeStatus.SUBMITTED.db())
                    .uniqueResult();

            return count != null ? count.intValue() : 0;

        } catch (Exception ex) {
            LOG.severe("countPendingByBatch error: " + ex.getMessage());
            return 0;
        }
    }
}