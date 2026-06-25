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
import java.util.Set;
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

    /**
     * Service method: loadMaxBatchSeq
     *
     * Reads highest numeric sequence from batch_id column (format BATCH{NNNN}).
     * Regex filter ^BATCH[0-9]+$ excludes any manually-inserted non-standard IDs.
     * SUBSTRING(batch_id FROM 6) strips "BATCH" prefix → cast to INT → ORDER DESC.
     * Returns 100 as floor if table empty or on any exception.
     *
     * Called by: BatchServiceImpl.createBatch() → BatchServiceImpl.nextBatchSeq()
     */
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

    /**
     * Service method: saveBatch
     *
     * INSERT or UPDATE via session.merge() — Hibernate decides based on entity state.
     * Explicit transaction: begin → merge → commit; rollback + rethrow on any failure.
     * Logs batch_id on success for audit trail.
     *
     * Called by: BatchServiceImpl.createBatch()
     */
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

    /**
     * Service method: deleteBatchAndCheques
     *
     * Two-step delete in one transaction: cts_cheques first (FK child), then cts_batches.
     * Both deletes must succeed — if either fails, full rollback.
     * Logs row counts for discard audit.
     *
     * Called by: BatchServiceImpl.discardBatch()
     */
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

    /**
     * Service method: updateBatchStatus
     *
     * Targeted single-column UPDATE: sets status + updated_at = CURRENT_TIMESTAMP.
     * Silently returns (with warning log) if either arg is null — safe for speculative calls.
     * Used by all lifecycle transitions: Draft→ReadyForVerification, →Verified, etc.
     *
     * Called by: BatchServiceImpl.updateBatchStatus(), submitBatch(), checkAndFinalizeBatch()
     */
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

    /**
     * Service method: updateBatch
     *
     * Full multi-field UPDATE: branch_code, total_cheques, total_amount,
     * control_amount, batch_type, status, updated_at in one query.
     * Defaults status to "Pending" if batch.getStatus() is null.
     * Rethrows on failure — callers must handle.
     *
     * Called by: composers or service when multiple fields change together
     */
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

    /**
     * Service method: updateBatchActualCounts
     *
     * Targeted UPDATE for post-ZIP-import reconciliation: sets total_cheques,
     * total_amount, status, updated_at in one query.
     * Lighter than full updateBatch() — only touches count/amount/status columns.
     * Rethrows on failure.
     *
     * Called by: ZipImportServiceImpl after parsing ZIP scan bundle
     */
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
    // READ — LEGACY
    // ─────────────────────────────────────────────────────────────

    /**
     * Service method: loadAllBatches
     *
     * SELECT * FROM cts_batches ORDER BY created_at DESC — no filters, no pagination.
     * Full table load into memory. Acceptable only for BatchChequeEntryComposer
     * which does its own in-memory filtering on a small dataset.
     * For UI list screens use loadBatchesPage() instead.
     *
     * Called by: BatchServiceImpl.getAllBatches() → BatchChequeEntryComposer
     */
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

    /**
     * Service method: findBatchById
     *
     * SELECT * FROM cts_batches WHERE batch_id = :batchId — single row lookup.
     * Returns null if not found or batchId is null/blank.
     * uniqueResult() throws if >1 row matched (impossible; batch_id is PK).
     *
     * Called by: getBatchById() and BatchServiceImpl.getBatchById()
     */
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

    /**
     * Service method: getBatchById
     *
     * Alias — delegates entirely to findBatchById().
     * Exists to satisfy BatchDAO interface; was previously unimplemented (returned null).
     *
     * Called by: BatchServiceImpl.getBatchById() → BatchDetailComposer
     */
    @Override
    public BatchEntity getBatchById(String batchId) {
        return findBatchById(batchId);
    }

    /**
     * Service method: loadBatchesWithHvCheques
     *
     * Returns all batches containing at least one high_value cheque.
     * EXISTS subquery on cts_cheques.high_value = true — avoids JOIN duplication.
     * Ordered by created_at DESC.
     *
     * Called by: reporting / HV-specific batch list screens
     */
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
    // READ — PAGINATED
    // ─────────────────────────────────────────────────────────────

    /**
     * Service method: loadBatchesPage
     *
     * Builds dynamic WHERE clause via buildWhere() then appends ORDER BY + LIMIT/OFFSET.
     * All user-supplied filter values bound as named params — never string-interpolated.
     * pageSize/pageNumber both floor-clamped to 1 if ≤ 0.
     * Returns empty list (never null) on DB error.
     *
     * Called by: BatchServiceImpl.getBatchesPage() → MyBatchesComposer.loadPage()
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

    /**
     * Service method: countBatches
     *
     * SELECT COUNT(*) reusing same WHERE clause from buildWhere() as loadBatchesPage().
     * Must be called with identical filter args to produce consistent "Page X of Y" math.
     * Returns 0 on DB error — callers treat 0 as "no results, 1 page".
     *
     * Called by: BatchServiceImpl.countBatches() → MyBatchesComposer.loadPage()
     */
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

    /**
     * Builds a WHERE clause string + param map from nullable filter inputs.
     * Each active filter appends one condition; inactive filters (null/blank) are skipped.
     * "All Status" string also treated as no-filter for statusFilter.
     * Conditions joined with AND; empty conditions → whereClause = "" (no WHERE).
     *
     * Used by: loadBatchesPage(), countBatches()
     */
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

    /**
     * Binds all entries from params map onto a NativeQuery using setParameter().
     * Called after buildWhere() to apply the dynamic param set in one pass.
     * Works for both SELECT (loadBatchesPage/countBatches) call sites.
     */
    private void applyParams(NativeQuery<?> q, Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            q.setParameter(e.getKey(), e.getValue());
        }
    }
    
    
 // ══════════════════════════════════════════════════════════════════════
  	// ANUSHA — Verification I (V1) Methods
  	// ══════════════════════════════════════════════════════════════════════

  	// Fetches exactly the batch rows whose batch_id is in the given set.
  	// Called by getVerifiableBatchSummaries() after the service has already
  	// identified which batch IDs have V1 cheques — no status filter needed here.
  	@Override
  	public List<BatchEntity> loadBatchesByIds(Set<String> batchIds) {
  		if (batchIds == null || batchIds.isEmpty()) {
  			LOG.warning("loadBatchesByIds: empty or null batchIds — returning empty list");
  			return Collections.emptyList();
  		}
  		try (Session session = HibernateUtil.getSession()) {
  			return session.createNativeQuery(
  					"SELECT * FROM cts_batches "
  					+ "WHERE batch_id IN :ids "
  					+ "ORDER BY created_at DESC",
  					BatchEntity.class)
  					.setParameter("ids", new ArrayList<>(batchIds))
  					.list();
  		} catch (Exception ex) {
  			LOG.severe("loadBatchesByIds error: " + ex.getMessage());
  			return Collections.emptyList();
  		}
  	}
}