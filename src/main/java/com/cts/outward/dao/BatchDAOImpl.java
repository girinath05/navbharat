/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchDAOImpl.java
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Hibernate-based implementation of BatchDAO.
 *                All write operations open an explicit transaction,
 *                roll back on any exception, and re-throw as
 *                RuntimeException to allow service-layer handling.
 *                Read operations use auto-close sessions with no
 *                explicit transaction (read-only intent).
 *
 *                NOTE: All queries are native PostgreSQL SQL
 *                (session.createNativeQuery) — no HQL is used in
 *                this class. Hibernate entities/annotations are
 *                still used for object mapping (addEntity), but
 *                the query language itself is plain SQL against
 *                public.cts_batches / public.cts_cheques.
 *
 *                PAGINATION NOTES
 *                ─────────────────
 *                loadBatchesPage() pushes LIMIT/OFFSET to Postgres.
 *                All filter conditions are built dynamically with
 *                named parameters — never string-concatenated — to
 *                prevent SQL injection.
 *                countBatches() re-uses the same WHERE clause so
 *                total-page calculation is consistent.
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.NativeQuery;

import com.cts.outward.entity.BatchEntity;
import com.cts.util.HibernateUtil;

public class BatchDAOImpl implements BatchDAO {

    private static final Logger LOG = Logger.getLogger(BatchDAOImpl.class.getName());

    // ─────────────────────────────────────────────────────────────
    // SEQUENCE
    // ─────────────────────────────────────────────────────────────

    @Override
    public int loadMaxBatchSeq() {
        try (Session session = HibernateUtil.getSession()) {
            String result = session
                    .createNativeQuery(
                            "SELECT batch_id FROM cts_batches "
                            + "WHERE batch_id ~ '^BATCH[0-9]+$' "
                            + "ORDER BY CAST(SUBSTRING(batch_id FROM 6) AS INTEGER) DESC "
                            + "LIMIT 1",
                            String.class)
                    .uniqueResult();
            if (result == null) return 100;
            return Integer.parseInt(result.substring(5)) + 1;
        } catch (Exception ex) {
            LOG.warning("loadMaxBatchSeq: " + ex.getMessage());
            return 100;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────────────────────

    @Override
    public void saveBatch(BatchEntity batch) {
        if (batch == null) throw new IllegalArgumentException("saveBatch: batch must not be null");
        Transaction tx = null;
        try (Session session = HibernateUtil.getSession()) {
            tx = session.beginTransaction();
            session.merge(batch);
            tx.commit();
            LOG.info("Batch saved: " + batch.getBatchId());
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            LOG.severe("saveBatch error: " + ex.getMessage());
            throw new RuntimeException("Failed to save batch: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void deleteBatchAndCheques(String batchId) {
        if (batchId == null || batchId.trim().isEmpty())
            throw new IllegalArgumentException("deleteBatchAndCheques: batchId must not be null/empty");
        Transaction tx = null;
        try (Session session = HibernateUtil.getSession()) {
            tx = session.beginTransaction();
            int chequesDel = session
                    .createNativeMutationQuery("DELETE FROM cts_cheques WHERE batch_id = :batchId")
                    .setParameter("batchId", batchId).executeUpdate();
            int batchDel = session
                    .createNativeMutationQuery("DELETE FROM cts_batches WHERE batch_id = :batchId")
                    .setParameter("batchId", batchId).executeUpdate();
            tx.commit();
            LOG.info("Discarded batch " + batchId + ": " + batchDel + " batch row, " + chequesDel + " cheques removed.");
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            LOG.severe("deleteBatchAndCheques error: " + ex.getMessage());
            throw new RuntimeException("Failed to discard batch: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void updateBatchStatus(String batchId, String status) {
        if (batchId == null || status == null) {
            LOG.warning("updateBatchStatus: null batchId or status — skipped");
            return;
        }
        Transaction tx = null;
        try (Session session = HibernateUtil.getSession()) {
            tx = session.beginTransaction();
            session.createNativeMutationQuery(
                    "UPDATE cts_batches SET status = :status, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE batch_id = :batchId")
                    .setParameter("status", status)
                    .setParameter("batchId", batchId)
                    .executeUpdate();
            tx.commit();
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            LOG.severe("updateBatchStatus error: " + ex.getMessage());
        }
    }

    @Override
    public void updateBatch(BatchEntity batch) {
        if (batch == null || batch.getBatchId() == null)
            throw new IllegalArgumentException("updateBatch: batch/batchId must not be null");
        Transaction tx = null;
        try (Session session = HibernateUtil.getSession()) {
            tx = session.beginTransaction();
            session.createNativeMutationQuery(
                    "UPDATE cts_batches SET "
                    + "  branch_code     = :branchCode, "
                    + "  total_cheques   = :totalCheques, "
                    + "  total_amount    = :totalAmount, "
                    + "  control_amount  = :controlAmount, "
                    + "  batch_type      = :batchType, "
                    + "  status          = :status, "
                    + "  updated_at      = CURRENT_TIMESTAMP "
                    + "WHERE batch_id = :batchId")
                    .setParameter("branchCode",   batch.getBranchCode())
                    .setParameter("totalCheques", batch.getTotalCheques())
                    .setParameter("totalAmount",  batch.getTotalAmount())
                    .setParameter("controlAmount", batch.getControlAmount())
                    .setParameter("batchType",    batch.getBatchType())
                    .setParameter("status",       batch.getStatus() != null ? batch.getStatus() : "Pending")
                    .setParameter("batchId",      batch.getBatchId())
                    .executeUpdate();
            tx.commit();
            LOG.info("Batch updated: " + batch.getBatchId());
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            LOG.severe("updateBatch error: " + ex.getMessage());
            throw new RuntimeException("updateBatch failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void updateBatchActualCounts(String batchId, int actualCheques, BigDecimal actualAmount, String status) {
        if (batchId == null) throw new IllegalArgumentException("updateBatchActualCounts: batchId null");
        Transaction tx = null;
        try (Session session = HibernateUtil.getSession()) {
            tx = session.beginTransaction();
            session.createNativeMutationQuery(
                    "UPDATE cts_batches SET "
                    + "  total_cheques = :tc, "
                    + "  total_amount  = :ta, "
                    + "  status        = :st, "
                    + "  updated_at    = CURRENT_TIMESTAMP "
                    + "WHERE batch_id  = :bid")
                    .setParameter("tc",  actualCheques)
                    .setParameter("ta",  actualAmount)
                    .setParameter("st",  status)
                    .setParameter("bid", batchId)
                    .executeUpdate();
            tx.commit();
            LOG.info("Batch " + batchId + " updated: cheques=" + actualCheques + " amt=" + actualAmount);
        } catch (Exception ex) {
            if (tx != null) tx.rollback();
            LOG.severe("updateBatchActualCounts error: " + ex.getMessage());
            throw new RuntimeException("updateBatchActualCounts failed: " + ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // READ — LEGACY (used internally / by non-list callers)
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<BatchEntity> loadAllBatches() {
        try (Session session = HibernateUtil.getSession()) {
            return session
                    .createNativeQuery("SELECT * FROM cts_batches ORDER BY created_at DESC", BatchEntity.class)
                    .list();
        } catch (Exception ex) {
            LOG.severe("loadAllBatches error: " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BatchEntity findBatchById(String batchId) {
        if (batchId == null || batchId.trim().isEmpty()) return null;
        try (Session session = HibernateUtil.getSession()) {
            return session
                    .createNativeQuery("SELECT * FROM cts_batches WHERE batch_id = :batchId", BatchEntity.class)
                    .setParameter("batchId", batchId)
                    .uniqueResult();
        } catch (Exception ex) {
            LOG.severe("findBatchById error: " + ex.getMessage());
            return null;
        }
    }

    /** Delegates to findBatchById — was previously unimplemented (returned null). */
    @Override
    public BatchEntity getBatchById(String batchId) {
        return findBatchById(batchId);
    }

    @Override
    public List<BatchEntity> loadBatchesWithHvCheques() {
        try (Session session = HibernateUtil.getSession()) {
            return session.createNativeQuery(
                    "SELECT b.* FROM cts_batches b WHERE EXISTS ("
                    + "  SELECT 1 FROM cts_cheques c "
                    + "  WHERE c.batch_id = b.batch_id AND c.high_value = true"
                    + ") ORDER BY b.created_at DESC",
                    BatchEntity.class).list();
        } catch (Exception ex) {
            LOG.severe("loadBatchesWithHvCheques error: " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // READ — PAGINATED (use this for all UI list screens)
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a dynamic WHERE clause from the supplied filters and delegates
     * LIMIT/OFFSET to Postgres. All user-supplied values bound as named
     * parameters — never string-interpolated.
     *
     * Filter semantics
     * ────────────────
     *  searchQuery  — ILIKE match on batch_id OR branch_code
     *  statusFilter — exact equality on status column (DB value, not display label)
     *  fromDate     — created_at::date >= fromDate  (format: 'YYYY-MM-DD')
     *  toDate       — created_at::date <= toDate
     *
     * NULL or blank values for any filter = that filter omitted.
     */
    @Override
    public List<BatchEntity> loadBatchesPage(
            String searchQuery,
            String statusFilter,
            String fromDate,
            String toDate,
            int pageSize,
            int pageNumber) {

        if (pageSize <= 0) pageSize = 5;
        if (pageNumber <= 0) pageNumber = 1;

        try (Session session = HibernateUtil.getSession()) {
            FilterClause f = buildWhere(searchQuery, statusFilter, fromDate, toDate);
            String sql = "SELECT * FROM cts_batches"
                    + f.whereClause
                    + " ORDER BY created_at DESC"
                    + " LIMIT :lim OFFSET :off";

            @SuppressWarnings("unchecked")
            NativeQuery<BatchEntity> q = session.createNativeQuery(sql, BatchEntity.class);
            applyParams(q, f.params);
            q.setParameter("lim", pageSize);
            q.setParameter("off", (long)(pageNumber - 1) * pageSize);

            return q.list();
        } catch (Exception ex) {
            LOG.severe("loadBatchesPage error: " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public long countBatches(
            String searchQuery,
            String statusFilter,
            String fromDate,
            String toDate) {

        try (Session session = HibernateUtil.getSession()) {
            FilterClause f = buildWhere(searchQuery, statusFilter, fromDate, toDate);
            String sql = "SELECT COUNT(*) FROM cts_batches" + f.whereClause;

            @SuppressWarnings("unchecked")
            NativeQuery<Number> q = (NativeQuery<Number>) session.createNativeQuery(sql);
            applyParams(q, f.params);

            Number result = q.uniqueResult();
            return result != null ? result.longValue() : 0L;
        } catch (Exception ex) {
            LOG.severe("countBatches error: " + ex.getMessage());
            return 0L;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // INTERNAL HELPERS
    // ─────────────────────────────────────────────────────────────

    /** Immutable holder for a WHERE fragment and its bound parameters. */
    private static class FilterClause {
        final String whereClause;          // e.g. " WHERE batch_id ILIKE :sq AND status = :sf"
        final Map<String, Object> params;  // named params to bind

        FilterClause(String whereClause, Map<String, Object> params) {
            this.whereClause = whereClause;
            this.params      = params;
        }
    }

    private FilterClause buildWhere(
            String searchQuery,
            String statusFilter,
            String fromDate,
            String toDate) {

        List<String>        conditions = new ArrayList<>();
        Map<String, Object> params     = new HashMap<>();

        boolean hasSearch = searchQuery != null && !searchQuery.trim().isEmpty();
        if (hasSearch) {
            // ILIKE on both batch_id and branch_code — case-insensitive substring
            conditions.add("(batch_id ILIKE :sq OR branch_code ILIKE :sq)");
            params.put("sq", "%" + searchQuery.trim() + "%");
        }

        boolean hasStatus = statusFilter != null && !statusFilter.trim().isEmpty()
                && !"All Status".equalsIgnoreCase(statusFilter.trim());
        if (hasStatus) {
            conditions.add("status = :sf");
            params.put("sf", statusFilter.trim());
        }

        boolean hasFrom = fromDate != null && !fromDate.trim().isEmpty();
        if (hasFrom) {
            conditions.add("created_at::date >= :fd");
            params.put("fd", fromDate.trim());
        }

        boolean hasTo = toDate != null && !toDate.trim().isEmpty();
        if (hasTo) {
            conditions.add("created_at::date <= :td");
            params.put("td", toDate.trim());
        }

        String whereClause = conditions.isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", conditions);

        return new FilterClause(whereClause, params);
    }

    private void applyParams(NativeQuery<?> q, Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            q.setParameter(e.getKey(), e.getValue());
        }
    }
}