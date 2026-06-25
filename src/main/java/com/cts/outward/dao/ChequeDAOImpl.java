/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeDAOImpl.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Hibernate-based implementation of ChequeDAO.
 *                Write methods use explicit transactions with
 *                rollback-on-exception. Read methods are
 *                sessionless (auto-close, no tx).
 *
 *                NOTE: All queries are native PostgreSQL SQL
 *                (session.createNativeQuery) — no HQL is used in
 *                this class. List-projection queries select only
 *                the columns needed for list views (BLOB columns
 *                front_image / rear_image are intentionally
 *                excluded) and are mapped into ChequeEntity via
 *                the existing 17-field projection constructor,
 *                with extra columns merged in via a second query
 *                exactly as before.
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.NativeQuery;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.util.HibernateUtil;

public class ChequeDAOImpl implements ChequeDAO {

	private static final Logger LOG = Logger.getLogger(ChequeDAOImpl.class.getName());

	// Only non-BLOB columns are selected in list views.
	// This avoids pulling front_image / rear_image (potentially MBs each)
	// on every batch/cheque list query — images are loaded on demand only.
	// Column order here must exactly match the 17-arg ChequeEntity projection constructor.
	private static final String PROJECTION_COLS =
			"id, batch_id, cheque_id, cheque_no, account_no, "
			+ "sort_code, amount, cheque_date, drawer_name, payee_name, "
			+ "iqa_status, ver_status, status, high_value, duplicate_flag, "
			+ "created_at, updated_at";

	/**
	 * Maps one Object[] row (column order = PROJECTION_COLS) to a ChequeEntity
	 * using the existing 17-field projection constructor.
<<<<<<< Updated upstream
	 * Used by all projection-based read methods.
=======
	 * toLong / toBigDecimal / toLocalDateTime helpers handle type variance
	 * between PostgreSQL JDBC driver and Hibernate return types.
>>>>>>> Stashed changes
	 */
	private static ChequeEntity mapProjectionRow(Object[] r) {
		return new ChequeEntity(
				toLong(r[0]),                 // id
				(String) r[1],                 // batch_id
				(String) r[2],                 // cheque_id
				(String) r[3],                 // cheque_no
				(String) r[4],                 // account_no
				(String) r[5],                 // sort_code
				toBigDecimal(r[6]),             // amount
				(String) r[7],                 // cheque_date
				(String) r[8],                 // drawer_name
				(String) r[9],                 // payee_name
				(String) r[10],                // iqa_status
				(String) r[11],                // ver_status
				(String) r[12],                // status
				r[13] != null && (Boolean) r[13], // high_value
				r[14] != null && (Boolean) r[14], // duplicate_flag
				toLocalDateTime(r[15]),        // created_at
				toLocalDateTime(r[16]));        // updated_at
	}

<<<<<<< Updated upstream
	/** Null-safe Long coercion — handles Long, Integer, BigInteger from JDBC. */
=======
	// ── Type-coercion helpers ──────────────────────────────────────────────
	// PostgreSQL JDBC may return numeric columns as Integer, Long, or BigDecimal
	// depending on the column type and Hibernate version. These helpers
	// handle all cases without casting exceptions.

>>>>>>> Stashed changes
	private static Long toLong(Object o) {
		if (o == null) return null;
		if (o instanceof Long) return (Long) o;
		return ((Number) o).longValue();
	}

	/** Null-safe BigDecimal coercion — handles BigDecimal, Double, String from JDBC. */
	private static BigDecimal toBigDecimal(Object o) {
		if (o == null) return null;
		if (o instanceof BigDecimal) return (BigDecimal) o;
		return new BigDecimal(o.toString());
	}

	/** Null-safe LocalDateTime coercion — handles LocalDateTime and java.sql.Timestamp from JDBC. */
	private static LocalDateTime toLocalDateTime(Object o) {
		if (o == null) return null;
		if (o instanceof LocalDateTime) return (LocalDateTime) o;
		// Hibernate with PostgreSQL may return java.sql.Timestamp for TIMESTAMPTZ columns
		return ((java.sql.Timestamp) o).toLocalDateTime();
	}

	// ══════════════════════════════════════════════════════════
	// DASHBOARD
	// ══════════════════════════════════════════════════════════

<<<<<<< Updated upstream
	/**
	 * Service method: loadDashboardData
	 *
	 * Two queries in one session:
	 *   1. SELECT * FROM cts_batches ORDER BY created_at DESC — full batch list
	 *   2. SELECT COUNT(*) WHERE ver_status = 'Pending' — pending cheques count
	 * Wraps both into DashboardData. Returns empty/zero defaults on any error.
	 *
	 * Called by: DashboardComposer on page load
	 */
=======
>>>>>>> Stashed changes
	@Override
	public DashboardData loadDashboardData() {
		try (Session session = HibernateUtil.getSession()) {
			// Full batch list ordered newest-first for the dashboard table
			List<BatchEntity> batches = session
					.createNativeQuery("SELECT * FROM cts_batches ORDER BY created_at DESC", BatchEntity.class)
					.list();
			// Single COUNT aggregation — no need to load every cheque entity
			Long pending = ((Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending'", Object.class)
					.uniqueResult()).longValue();
			return new DashboardData(batches, pending != null ? pending : 0L);
		} catch (Exception ex) {
			LOG.severe("loadDashboardData error: " + ex.getMessage());
			// Return empty dashboard rather than propagating — UI must handle gracefully
			return new DashboardData(Collections.emptyList(), 0L);
		}
	}

	// ══════════════════════════════════════════════════════════
	// CHEQUE OPERATIONS
	// ══════════════════════════════════════════════════════════

<<<<<<< Updated upstream
	/**
	 * Service method: saveCheque
	 *
	 * persist() for new entities (id=null), merge() for existing.
	 * Explicit transaction; rethrows on failure.
	 *
	 * Called by: BatchDetailComposer after individual cheque edit
	 */
=======
>>>>>>> Stashed changes
	@Override
	public void saveCheque(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			// persist() for new rows; merge() for updates (id already assigned by DB)
			if (cheque.getId() == null)
				session.persist(cheque);
			else
				session.merge(cheque);
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("saveCheque error: " + ex.getMessage());
			throw new RuntimeException("Failed to save cheque: " + ex.getMessage(), ex);
		}
	}

<<<<<<< Updated upstream
	/**
	 * Daemon thread pool for async BLOB writes — 2 threads, named "img-save".
	 * Keeps image save off the main request thread; daemon so it doesn't block JVM shutdown.
	 */
=======
	// Background thread pool for asynchronous BLOB writes.
	// Fixed at 2 threads — enough parallelism without overwhelming Supabase
	// connection limits. Daemon threads so they don't block JVM shutdown.
>>>>>>> Stashed changes
	private static final ExecutorService IMAGE_EXEC =
	        Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "img-save"); t.setDaemon(true); return t; });

	/**
	 * Service method: saveCheques
	 *
	 * Phase 1 (sync) — strip BLOBs, persist metadata in batches of 50 (flush+clear each batch).
	 *   - BLOBs stripped before tx open: avoids sending large BYTEA to Supabase in main tx.
	 *   - On failure: BLOBs restored to entities before rethrow.
	 *
	 * Phase 2 (async) — IMAGE_EXEC thread runs JDBC batch UPDATE for front_image/rear_image.
	 *   - Skipped entirely if no images present (hasImages check).
	 *   - Snapshots of ids/fronts/rears passed to lambda (immutable copies, thread-safe).
	 *   - Uses session.doWork() for raw JDBC PreparedStatement batch — bypasses Hibernate
	 *     for BYTEA efficiency.
	 *
	 * Called by: ZipImportServiceImpl after parsing ZIP scan bundle
	 */
	//SAVING IMG ON Supabase  
	// 6. SAVE CHEQUES TO DB
	@Override
	public void saveCheques(List<ChequeEntity> cheques) {
		// Guard: nothing to save
	    if (cheques == null || cheques.isEmpty())
	        return;

	    // Step 1: Strip image bytes before the metadata transaction.
	    // Reason: Supabase/PostgreSQL BYTEA writes are slow and can cause
	    // transaction timeouts when done inside the same tx as metadata.
	    // We save images separately (async) after the metadata tx commits.
	    List<byte[]> fronts = new ArrayList<>(cheques.size());
	    List<byte[]> rears  = new ArrayList<>(cheques.size());
	    for (ChequeEntity c : cheques) {
	        fronts.add(c.getFrontImage());
	        rears.add(c.getRearImage());
	        c.setFrontImage(null);
	        c.setRearImage(null);
	    }

	    // Step 2: Persist metadata (all non-BLOB columns) in one transaction.
	    // Flushed every 50 rows to keep first-level cache from growing unbounded.
	    Transaction tx = null;
	    try (Session session = HibernateUtil.getSession()) {
	        tx = session.beginTransaction();
	        int count = 0;
	        for (ChequeEntity c : cheques) {
	            session.persist(c);
	            count++;
	            if (count % 50 == 0) { session.flush(); session.clear(); }
	        }
	        session.flush();
	        tx.commit();
	        LOG.info("Saved " + cheques.size() + " cheques (metadata) to Supabase");
	    } catch (Exception ex) {
	        if (tx != null) tx.rollback();
	        // Restore image bytes on rollback so caller's list is not corrupted
	        for (int i = 0; i < cheques.size(); i++) {
	            cheques.get(i).setFrontImage(fronts.get(i));
	            cheques.get(i).setRearImage(rears.get(i));
	        }
	        LOG.severe("saveCheques error: " + ex.getMessage());
	        throw new RuntimeException("Failed to save cheques: " + ex.getMessage(), ex);
	    }

	    // Step 3: Restore image bytes on the caller's objects (metadata tx succeeded).
	    // Caller may still need the bytes in memory (e.g. for display after save).
	    for (int i = 0; i < cheques.size(); i++) {
	        cheques.get(i).setFrontImage(fronts.get(i));
	        cheques.get(i).setRearImage(rears.get(i));
	    }

	    // Step 4: Skip async image write if none of the cheques had image data
	    boolean hasImages = false;
	    for (byte[] b : fronts) { if (b != null) { hasImages = true; break; } }
	    if (!hasImages) for (byte[] b : rears) { if (b != null) { hasImages = true; break; } }
	    if (!hasImages) return;

	    // Step 5: Submit async JDBC batch UPDATE for BLOB columns.
	    // Uses snapshots of IDs and byte arrays captured at this point
	    // (lambda must not reference mutable caller state).
	    final List<Long>   snapIds    = new ArrayList<>(cheques.size());
	    final List<byte[]> snapFronts = new ArrayList<>(fronts);
	    final List<byte[]> snapRears  = new ArrayList<>(rears);
	    for (ChequeEntity c : cheques) snapIds.add(c.getId());

	    IMAGE_EXEC.submit(() -> {
	        try (Session imgSession = HibernateUtil.getSession()) {
	            imgSession.doWork((Connection conn) -> {
	                String sql = "UPDATE cts_cheques SET front_image = ?, rear_image = ? WHERE id = ?";
	                try (PreparedStatement ps = conn.prepareStatement(sql)) {
	                    for (int i = 0; i < snapIds.size(); i++) {
	                        Long rowId = snapIds.get(i);
	                        if (rowId == null) continue; // ID not assigned — skip (should not happen)
	                        byte[] front = snapFronts.get(i);
	                        byte[] rear  = snapRears.get(i);
	                        if (front == null && rear == null) continue; // no images for this cheque
	                        if (front != null) ps.setBytes(1, front); else ps.setNull(1, java.sql.Types.BINARY);
	                        if (rear  != null) ps.setBytes(2, rear);  else ps.setNull(2, java.sql.Types.BINARY);
	                        ps.setLong(3, rowId);
	                        ps.addBatch();
	                    }
	                    ps.executeBatch();
	                }
	            });
	            LOG.info("Async image save complete for " + snapIds.size() + " cheques");
	        } catch (Exception ex) {
	            LOG.severe("Async image save failed: " + ex.getMessage());
	        }
	    });
	}

	/**
	 * Service method: findExistingChequeNos
	 *
	 * SELECT cheque_no FROM cts_cheques WHERE cheque_no IN :nos
	 * IN-clause batch query — one round trip for the whole list.
	 * Returns HashSet for O(1) contains() in duplicate-filter loop.
	 * Returns empty set on null/empty input or DB error.
	 *
	 * Called by: ZipImportServiceImpl.filterDuplicates()
	 */
	@Override
	public Set<String> findExistingChequeNos(List<String> chequeNos) {
		// Guard: avoid sending empty IN list — PostgreSQL rejects it
		if (chequeNos == null || chequeNos.isEmpty())
			return Collections.emptySet();
		try (Session session = HibernateUtil.getSession()) {
			@SuppressWarnings("unchecked")
			List<String> found = session
					.createNativeQuery("SELECT cheque_no FROM cts_cheques WHERE cheque_no IN :nos", String.class)
					.setParameter("nos", chequeNos)
					.getResultList();
			return new HashSet<>(found);
		} catch (Exception ex) {
			LOG.severe("findExistingChequeNos error: " + ex.getMessage());
			return Collections.emptySet();
		}
	}

	/**
	 * Service method: loadReadyBatchIds
	 *
	 * GROUP BY batch_id HAVING COUNT(*) > 0 AND COUNT(*) = COUNT(CASE WHEN status='Ready' THEN 1 END)
	 * Single query replaces N areAllChequesReady() calls for table rendering.
	 * HAVING clause ensures: batch non-empty AND every cheque is Ready.
	 * Returns HashSet for O(1) contains() in renderPage() loop.
	 * Returns empty set on null/empty input or DB error.
	 *
	 * Called by: BatchServiceImpl.getReadyBatchIds() → MyBatchesComposer.renderPage()
	 */
	@Override
	public Set<String> loadReadyBatchIds(List<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty())
			return Collections.emptySet();
		try (Session session = HibernateUtil.getSession()) {
			@SuppressWarnings("unchecked")
			// HAVING clause: a batch is "ready" only if ALL its cheques have status='Ready'.
			// COUNT(*) = total rows; COUNT(CASE WHEN status='Ready' THEN 1 END) = ready rows.
			// If both counts match, every cheque is ready.
			List<String> ready = session.createNativeQuery(
					"SELECT batch_id FROM cts_cheques WHERE batch_id IN :ids "
					+ "GROUP BY batch_id "
					+ "HAVING COUNT(*) > 0 AND COUNT(*) = COUNT(CASE WHEN status = 'Ready' THEN 1 END)",
					String.class)
					.setParameter("ids", batchIds)
					.getResultList();
			return new HashSet<>(ready);
		} catch (Exception ex) {
			LOG.severe("loadReadyBatchIds error: " + ex.getMessage());
			return Collections.emptySet();
		}
	}

	/**
	 * Service method: loadChequesForBatch
	 *
	 * Two-query projection pattern:
	 *   Query 1: PROJECTION_COLS SELECT → mapProjectionRow() → list of ChequeEntity (no BLOBs)
	 *   Query 2: SELECT id + extra fields (transaction_code, amount_in_words,
	 *            amount_words_mismatch, payee_account_no, base_no) WHERE id IN :ids
	 *            → merged into entities via HashMap<Long, Object[]>
	 * Falls back to loadChequesForBatchFull() if projection query throws.
	 *
	 * Called by: BatchServiceImpl (areAllChequesReady, submitBatch validation),
	 *            BatchDetailComposer cheque list render
	 */
	@Override
	public List<ChequeEntity> loadChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			// Query 1: Load lightweight projection (no BLOBs)
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (!results.isEmpty()) {
				// Query 2: Fetch extra fields excluded from PROJECTION_COLS in a second pass.
				// Merged by id — avoids loading these columns in every list query but still
				// provides them for the cheque detail / entry popup.
				List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
				List<Object[]> extraRows = session.createNativeQuery(
						"SELECT id, transaction_code, amount_in_words, amount_words_mismatch, payee_account_no, base_no "
								+ "FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();
				Map<Long, Object[]> extraMap = new HashMap<>();
				for (Object[] row : extraRows)
					extraMap.put(toLong(row[0]), row);
				results.forEach(c -> {
					Object[] row = extraMap.get(c.getId());
					if (row != null) {
						c.setTransactionCode(row[1] != null ? row[1].toString() : null);
						c.setAmountInWords(row[2] != null ? row[2].toString() : null);
						c.setAmountWordsMismatch(row[3] != null && (Boolean) row[3]);
						c.setPayeeAccountNo(row[4] != null ? row[4].toString() : null);
						c.setBaseNo(row[5] != null ? row[5].toString() : null);
					}
				});
			}
			return results;
		} catch (Exception ex) {
			// Projection constructor signature may drift if entity changes — fall back to full load
			LOG.warning("loadChequesForBatch projection failed, falling back: " + ex.getMessage());
			return loadChequesForBatchFull(batchId);
		}
	}

	/**
	 * Service method: loadChequesForBatchFull
	 *
	 * SELECT * — full entity including BLOBs. Slower than loadChequesForBatch().
	 * Only called as fallback when projection query fails.
	 * Returns empty list on DB error.
	 *
	 * Called by: loadChequesForBatch() fallback path
	 */
	@Override
	public List<ChequeEntity> loadChequesForBatchFull(String batchId) {
		// SELECT * loads all columns including BLOBs — use only when needed
		// or as the fallback from loadChequesForBatch()
		try (Session session = HibernateUtil.getSession()) {
			NativeQuery<ChequeEntity> q = session.createNativeQuery(
					"SELECT * FROM cts_cheques WHERE batch_id = :batchId ORDER BY id", ChequeEntity.class);
			q.setParameter("batchId", batchId);
			return q.list();
		} catch (Exception ex) {
			LOG.severe("loadChequesForBatchFull error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	/**
	 * Service method: loadChequeWithImages
	 *
	 * session.get() by PK — full Hibernate entity load including BYTEA columns.
	 * The ONLY method that returns front_image/rear_image bytes.
	 * All other reads use PROJECTION_COLS to avoid loading BLOBs unnecessarily.
	 * Returns null on not-found or DB error.
	 *
	 * Called by: ImageViewerComposer / batch-detail image display
	 */
	// ── 3. Load image bytes from Supabase via Hibernate ──────────────────
	//
	// loadChequeWithImages() uses session.get() which loads the full entity
	// including the BYTEA columns (front_image, rear_image).
	// The 17-field projection constructor intentionally excludes these.
	//
	@Override
	public ChequeEntity loadChequeWithImages(Long chequeId) {
		// session.get() = full entity load by PK — includes front_image and rear_image BYTEA
		try (Session session = HibernateUtil.getSession()) {
			return session.get(ChequeEntity.class, chequeId);
		} catch (Exception ex) {
			LOG.severe("loadChequeWithImages error: " + ex.getMessage());
			return null;
		}
	}

	/**
	 * Service method: updateChequeStatus
	 *
	 * Targeted UPDATE: status + ver_status + updated_at only.
	 * Lighter than updateChequeFields() — does not touch MICR or amount columns.
	 * Silent on failure (logs but does not rethrow).
	 *
	 * Called by: MicrRepairComposer, BatchDetailComposer on status-only changes
	 */
	@Override
	public void updateChequeStatus(Long chequeId, String status, String verStatus) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			// Updates status + ver_status only — ver_level, ver_action, ver_by are NOT touched here.
			// Those fields are managed exclusively by applyVerifierAction() and referToVerificationTwo().
			session.createNativeMutationQuery("UPDATE cts_cheques SET status = :status, ver_status = :verStatus, "
					+ "updated_at = CURRENT_TIMESTAMP WHERE id = :id")
					.setParameter("status", status)
					.setParameter("verStatus", verStatus)
					.setParameter("id", chequeId).executeUpdate();
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateChequeStatus error: " + ex.getMessage());
		}
	}

	/**
	 * Service method: deleteCheque
	 *
	 * DELETE FROM cts_cheques WHERE id = :id — hard delete, no soft-delete flag.
	 * Explicit transaction; rethrows on failure.
	 * Logs cheque id on success for audit trail.
	 *
	 * Called by: BatchDetailComposer "Delete Cheque" action
	 */
	@Override
	public void deleteCheque(Long chequeId) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			// Hard delete — no soft-delete flag on cts_cheques.
			// Caller (service) must validate batch is in Draft state before calling.
			session.createNativeMutationQuery("DELETE FROM cts_cheques WHERE id = :id")
					.setParameter("id", chequeId).executeUpdate();
			tx.commit();
			LOG.info("Cheque deleted: id=" + chequeId);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("deleteCheque error: " + ex.getMessage());
			throw new RuntimeException("deleteCheque failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Service method: countPendingCheques
	 *
	 * SELECT COUNT(*) WHERE ver_status = 'Pending' — global count across all batches.
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer pending-cheques KPI tile
	 */
	@Override
	public long countPendingCheques() {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending'", Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingCheques error: " + ex.getMessage());
			return 0;
		}
	}

	// ══════════════════════════════════════════════════════════
	// MICR REPAIR OPERATIONS
	// ══════════════════════════════════════════════════════════

<<<<<<< Updated upstream
	/**
	 * Service method: loadIqaFailedCheques
	 *
	 * Projection SELECT WHERE iqa_status = 'Fail' AND status = 'MICR_Repair' for a batch.
	 * No BLOBs — MICR repair screen needs field values, not images.
	 * Returns empty list on DB error.
	 *
	 * Called by: MicrRepairComposer on batch load
	 */
=======
>>>>>>> Stashed changes
	@Override
	public List<ChequeEntity> loadIqaFailedCheques(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			// Filter: IQA failed AND awaiting MICR correction — only these show in the repair grid
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId "
							+ "AND iqa_status = 'Fail' AND status = 'MICR_Repair' ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId).getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));
			return results;
		} catch (Exception ex) {
			LOG.severe("loadIqaFailedCheques error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	/**
	 * Service method: updateChequeFields
	 *
	 * Multi-field MICR repair UPDATE:
	 *   sort_code, transaction_code, account_no, payee_account_no, base_no,
	 *   amount, amount_in_words, amount_words_mismatch, cheque_date, payee_name, status.
	 * NOTE: ver_level intentionally excluded — projection loads verLevel=null;
	 *   writing it here would clobber ver_level set by submitBatch()/verifier flow.
	 * Rethrows on failure.
	 *
	 * Called by: MicrRepairComposer "Save" action
	 */
	@Override
	public void updateChequeFields(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET "
					+ "  sort_code             = :sortCode, "
					+ "  transaction_code      = :txCode, "
					+ "  account_no            = :accountNo, "
					+ "  payee_account_no      = :payeeAccountNo, "
					+ "  base_no               = :baseNo, "
					+ "  amount                = :amount, "
					+ "  amount_in_words       = :amountInWords, "
					+ "  amount_words_mismatch = :mismatch, "
					+ "  cheque_date           = :chequeDate, "
					+ "  payee_name            = :payeeName, "
					+ "  status                = :status, "
					// FIX: ver_level removed — projection loads verLevel=null; writing it
					// here would clobber ver_level set by submitBatch()/verifier flow.
					+ "  updated_at            = CURRENT_TIMESTAMP "
					+ "WHERE id = :id")
					.setParameter("sortCode",       cheque.getSortCode())
					.setParameter("txCode",         cheque.getTransactionCode())
					.setParameter("accountNo",      cheque.getAccountNo())
					.setParameter("payeeAccountNo", cheque.getPayeeAccountNo())
					.setParameter("baseNo",         cheque.getBaseNo())
					.setParameter("amount",         cheque.getAmount())
					.setParameter("amountInWords",  cheque.getAmountInWords())
					.setParameter("mismatch",       cheque.isAmountWordsMismatch())
					.setParameter("chequeDate",     cheque.getChequeDate())
					.setParameter("payeeName",      cheque.getPayeeName())
					.setParameter("status",         cheque.getStatus())
					.setParameter("id",             cheque.getId())
					.executeUpdate();
			tx.commit();
			LOG.info("MICR fields updated for cheque id=" + cheque.getId());
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateChequeFields error: " + ex.getMessage());
			throw new RuntimeException("Failed to update cheque fields: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Service method: countRepairedToday
	 *
	 * SELECT COUNT(*) WHERE status = 'Sent_for_Verification' AND updated_at >= CURRENT_DATE.
	 * "Today" is determined server-side by Postgres CURRENT_DATE — no Java date math.
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer / MicrRepairComposer "repaired today" stats tile
	 */
	@Override
	public long countRepairedToday() {
		try (Session session = HibernateUtil.getSession()) {
			// status='Sent_for_Verification' = cheque repaired and forwarded to verifier queue
			// updated_at >= CURRENT_DATE = only today's repairs count
			Number result = (Number) session
					.createNativeQuery(
							"SELECT COUNT(*) FROM cts_cheques "
									+ "WHERE status = 'Sent_for_Verification' AND updated_at >= CURRENT_DATE",
							Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countRepairedToday error: " + ex.getMessage());
			return 0L;
		}
	}

	// ══════════════════════════════════════════════════════════
	// HIGH VALUE (HV) OPERATIONS
	// ══════════════════════════════════════════════════════════

<<<<<<< Updated upstream
	/**
	 * Service method: loadHvChequesForBatch
	 *
	 * Projection SELECT WHERE batch_id = :batchId AND high_value = true.
	 * Falls back to SELECT * if projection throws (two-level try/catch).
	 * Returns empty list on both levels failing.
	 *
	 * Called by: HvBatchDetailComposer / VerificationTwoComposer
	 */
=======
>>>>>>> Stashed changes
	@Override
	public List<ChequeEntity> loadHvChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId AND high_value = true ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId).getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));
			return results;
		} catch (Exception ex) {
			// Projection failure — fallback to full entity load including BLOBs
			LOG.warning("loadHvChequesForBatch projection failed, fallback: " + ex.getMessage());
			try (Session session = HibernateUtil.getSession()) {
				NativeQuery<ChequeEntity> q = session.createNativeQuery(
						"SELECT * FROM cts_cheques WHERE batch_id = :batchId AND high_value = true ORDER BY id",
						ChequeEntity.class);
				q.setParameter("batchId", batchId);
				return q.list();
			} catch (Exception ex2) {
				LOG.severe("loadHvChequesForBatch fallback error: " + ex2.getMessage());
				return Collections.emptyList();
			}
		}
	}

	/**
	 * Service method: countHvPendingCheques
	 *
	 * SELECT COUNT(*) WHERE high_value = true AND ver_status = 'Pending'.
	 * Global count across all batches.
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer HV pending KPI tile
	 */
	@Override
	public long countHvPendingCheques() {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques WHERE high_value = true AND ver_status = 'Pending'", Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvPendingCheques error: " + ex.getMessage());
			return 0L;
		}
	}

	/**
	 * Service method: countHvByVerStatus
	 *
	 * SELECT COUNT(*) WHERE high_value = true AND ver_status = :vs.
	 * Generic — caller passes any ver_status string ('V2_PENDING', 'VERIFIED', etc.).
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer HV breakdown tiles
	 */
	@Override
	public long countHvByVerStatus(String verStatus) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE high_value = true AND ver_status = :vs", Object.class)
					.setParameter("vs", verStatus).uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvByVerStatus error: " + ex.getMessage());
			return 0L;
		}
	}

	/**
	 * Service method: countHvPendingForBatch
	 *
	 * SELECT COUNT(*) WHERE batch_id = :batchId AND high_value = true AND ver_status = 'Pending'.
	 * Batch-scoped version of countHvPendingCheques().
	 * Returns 0 on DB error.
	 *
	 * Called by: HvBatchDetailComposer pending-count indicator
	 */
	@Override
	public long countHvPendingForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE batch_id = :batchId "
							+ "AND high_value = true AND ver_status = 'Pending'", Object.class)
					.setParameter("batchId", batchId).uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvPendingForBatch error: " + ex.getMessage());
			return 0L;
		}
	}

	/**
	 * Service method: sumHvPendingAmount
	 *
	 * SELECT SUM(amount) WHERE high_value = true AND ver_status = 'Pending'.
	 * toBigDecimal() handles both NUMERIC and DOUBLE PRECISION from Postgres.
	 * Returns BigDecimal.ZERO on null result (no matching rows) or DB error.
	 *
	 * Called by: DashboardComposer HV pending amount KPI tile
	 */
	@Override
	public BigDecimal sumHvPendingAmount() {
		try (Session session = HibernateUtil.getSession()) {
			Object result = session.createNativeQuery(
					"SELECT SUM(amount) FROM cts_cheques WHERE high_value = true AND ver_status = 'Pending'", Object.class)
					.uniqueResult();
			// SUM on empty set returns null from PostgreSQL — return ZERO instead of null
			return result != null ? toBigDecimal(result) : BigDecimal.ZERO;
		} catch (Exception ex) {
			LOG.severe("sumHvPendingAmount error: " + ex.getMessage());
			return BigDecimal.ZERO;
		}
	}

<<<<<<< Updated upstream
<<<<<<< Updated upstream
	

	
=======
	// ══════════════════════════════════════════════════════════
	// VERIFICATION OPERATIONS
	// Author : Anusha (V1) / Girinath (V2)
	// ══════════════════════════════════════════════════════════

	/**
	 * Service method: loadChequesByVerLevel
	 *
	 * Two-query projection pattern for verification queues:
	 *   Query 1: PROJECTION_COLS WHERE ver_level = :verLevel AND status = :status
	 *            ORDER BY batch_id ASC, id ASC (FIFO within batch)
	 *   Query 2: SELECT id, ver_action, ver_by, ver_remarks WHERE id IN :ids
	 *            → merged into entities via HashMap<Long, Object[]>
	 * Returns empty list on DB error.
	 *
	 * Called by: VerificationOneComposer (verLevel='V1', status='V1_Pending')
	 *            VerificationTwoComposer (verLevel='V2', status='V2_Pending')
	 */
	@Override
	public List<ChequeEntity> loadChequesByVerLevel(String verLevel, String status) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques"
					+ " WHERE ver_level = :verLevel AND status = :status"
					+ " ORDER BY batch_id ASC, id ASC",
					Object[].class)
					.setParameter("verLevel", verLevel)
					.setParameter("status", status)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (!results.isEmpty()) {
				List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
				List<Object[]> verRows = session.createNativeQuery(
						"SELECT id, ver_action, ver_by, ver_remarks FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();
				Map<Long, Object[]> verMap = new HashMap<>();
				for (Object[] row : verRows)
					verMap.put(toLong(row[0]), row);
				results.forEach(c -> {
					Object[] row = verMap.get(c.getId());
					if (row != null) {
						c.setVerAction(row[1] != null ? row[1].toString() : null);
						c.setVerBy(row[2] != null ? row[2].toString() : null);
						c.setVerRemarks(row[3] != null ? row[3].toString() : null);
					}
				});
			}
			return results;
		} catch (Exception ex) {
			LOG.severe("loadChequesByVerLevel error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	/**
	 * Service method: countPendingVerificationForBatch
	 *
	 * SELECT COUNT(*) WHERE batch_id = :batchId AND status IN ('V1_PENDING', 'V2_PENDING').
	 * Used by BatchServiceImpl.checkAndFinalizeBatch() — count=0 triggers batch→'Verified'.
	 * Returns -1L on DB error so caller can distinguish "zero pending" from "query failed"
	 * and skip finalization on uncertainty (fail-safe).
	 *
	 * Called by: BatchServiceImpl.checkAndFinalizeBatch() after each verifier action
	 */
	@Override
	public long countPendingVerificationForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques"
					+ " WHERE batch_id = :batchId"
					+ " AND status IN ('V1_PENDING', 'V2_PENDING')",
					Object.class)
					.setParameter("batchId", batchId)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingVerificationForBatch error: " + ex.getMessage());
			return -1L;
		}
	}

	/**
	 * Service method: applyVerifierAction
	 *
	 * UPDATE: status, ver_status, ver_level, ver_action, ver_by, ver_remarks, updated_at.
	 * Note: ver_status mirrors status in this UPDATE (both set to same value).
	 * Used for standard V1/V2 decisions: ACCEPTED, REJECTED.
	 * Does NOT set is_referred — use referToVerificationTwo() for V1→V2 escalation.
	 * Rethrows on failure.
	 *
	 * Called by: VerificationOneComposer (Anusha), VerificationTwoComposer (Girinath)
	 */
	@Override
	public void applyVerifierAction(Long chequeId, String status, String verLevel,
			String verAction, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"
					+ "  ver_status  = :status,"
					+ "  ver_level   = :verLevel,"
					+ "  ver_action  = :verAction,"
					+ "  ver_by      = :verBy,"
					+ "  ver_remarks = :verRemarks,"
					+ "  updated_at  = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",     status)
					.setParameter("verLevel",   verLevel)
					.setParameter("verAction",  verAction)
					.setParameter("verBy",      verBy)
					.setParameter("verRemarks", verRemarks)
					.setParameter("id",         chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("applyVerifierAction: cheque=" + chequeId + " action=" + verAction + " by=" + verBy);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("applyVerifierAction error: " + ex.getMessage());
			throw new RuntimeException("Failed to apply verifier action: " + ex.getMessage(), ex);
		}
	}

	// ══════════════════════════════════════════════════════════
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════

	/**
	 * Service method: loadAcceptedInstrumentsForCxf
	 *
	 * Projection SELECT WHERE status = 'VERIFIED'
	 *   AND (ver_status IS NULL OR ver_status != 'CXF_Generated')
	 * Excludes already-generated instruments — idempotent if called twice.
	 * Secondary SELECT merges transaction_code via HashMap.
	 * Returns empty list on DB error.
	 *
	 * Called by: CxfGenerationComposer on generate action
	 */
>>>>>>> Stashed changes
=======
	// ══════════════════════════════════════════════════════════
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════

>>>>>>> Stashed changes
	@Override
	public List<ChequeEntity> loadAcceptedInstrumentsForCxf() {
		try (Session session = HibernateUtil.getSession()) {
			// Only VERIFIED cheques not yet marked CXF_Generated — avoids re-including
			// instruments in a second CXF run if the operator generates again.
			// Ordered by batch_id + id to keep cheques grouped by batch in the CXF XML.
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE status = 'VERIFIED' "
							+ "  AND (ver_status IS NULL OR ver_status <> 'CXF_Generated') "
							+ "ORDER BY batch_id ASC, id ASC",
					Object[].class)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (results.isEmpty())
				return results;

			// Second query: fetch transaction_code not in PROJECTION_COLS.
			// CXF XML needs transaction_code for each instrument element.
			List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
			List<Object[]> tcRows = session
					.createNativeQuery("SELECT id, transaction_code FROM cts_cheques WHERE id IN :ids", Object[].class)
					.setParameter("ids", ids).getResultList();

			Map<Long, String> tcMap = new HashMap<>();
			for (Object[] row : tcRows)
				tcMap.put(toLong(row[0]), (String) row[1]);
			results.forEach(c -> c.setTransactionCode(tcMap.get(c.getId())));

			LOG.info("loadAcceptedInstrumentsForCxf: " + results.size() + " instruments ready.");
			return results;
		} catch (Exception ex) {
			LOG.severe("loadAcceptedInstrumentsForCxf error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

<<<<<<< Updated upstream
<<<<<<< Updated upstream
	
=======
>>>>>>> Stashed changes
	@Override
=======
	/**
	 * Service method: referToVerificationTwo
	 *
	 * V1 → V2 escalation UPDATE — differs from applyVerifierAction() in two fields:
	 *   - ver_level   hardcoded 'V2' (not passed — always V2 on refer)
	 *   - is_referred hardcoded true (permanent flag; V2 must NOT reset this)
	 * status + ver_status both set to 'V2_PENDING', ver_action = 'Refer'.
	 * Rethrows on failure.
	 *
	 * Called by: VerificationOneComposer "Refer to V2" action (Anusha)
	 */
	@Override
	public void referToVerificationTwo(Long chequeId, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"
					+ "  ver_status  = :status,"
					+ "  ver_level   = :verLevel,"
					+ "  is_referred = true,"
					+ "  ver_action  = :verAction,"
					+ "  ver_by      = :verBy,"
					+ "  ver_remarks = :verRemarks,"
					+ "  updated_at  = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",     "V2_PENDING")
					.setParameter("verLevel",   "V2")
					.setParameter("verAction",  "Refer")
					.setParameter("verBy",      verBy)
					.setParameter("verRemarks", verRemarks)
					.setParameter("id",         chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("referToVerificationTwo: cheque=" + chequeId + " by=" + verBy);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("referToVerificationTwo error: " + ex.getMessage());
			throw new RuntimeException("Failed to refer cheque to V2: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Service method: countV1ProcessedForBatch
	 *
	 * SELECT COUNT(*) WHERE batch_id = :batchId AND ver_level = 'V1'
	 *   AND status IN ('VERIFIED', 'REJECTED', 'V2_PENDING').
	 * "Processed" = any terminal V1 decision including refer-to-V2.
	 * Returns 0 on DB error.
	 *
	 * Called by: VerificationOneComposer batch progress indicator
	 */
	@Override
	public long countV1ProcessedForBatch(String batchId) {
	    try (Session session = HibernateUtil.getSession()) {
	        Number result = (Number) session.createNativeQuery(
	                "SELECT COUNT(*) FROM cts_cheques"
	                + " WHERE batch_id = :batchId"
	                + "   AND ver_level = 'V1'"
	                + "   AND status IN ('VERIFIED', 'REJECTED', 'V2_PENDING')",
	                Object.class)
	                .setParameter("batchId", batchId)
	                .uniqueResult();
	        return result != null ? result.longValue() : 0L;
	    } catch (Exception ex) {
	        LOG.severe("countV1ProcessedForBatch error: " + ex.getMessage());
	        return 0L;
	    }
	}

	/**
	 * Service method: updateVerRouting
	 *
	 * Routing UPDATE on batch submission: sets status, ver_level, ver_status, updated_at.
	 * Called once per cheque inside BatchServiceImpl.submitBatch() routing loop.
	 * Does NOT touch ver_action/ver_by/ver_remarks — those are set by verifiers, not submit.
	 * Rethrows on failure.
	 *
	 * Called by: BatchServiceImpl.submitBatch() → per-cheque V1/V2 routing loop
	 */
	@Override
>>>>>>> Stashed changes
	public void updateVerRouting(Long chequeId, String status, String verLevel, String verStatus) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			// Updates status + ver_level + ver_status together.
			// Different from updateChequeStatus() which intentionally skips ver_level —
			// CXF routing transitions DO need to update ver_level (e.g. V1 → V2 escalation).
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status     = :status,"
					+ "  ver_level  = :verLevel,"
					+ "  ver_status = :verStatus,"
					+ "  updated_at = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",    status)
					.setParameter("verLevel",  verLevel)
					.setParameter("verStatus", verStatus)
					.setParameter("id",        chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("updateVerRouting: cheque=" + chequeId + " status=" + status + " verLevel=" + verLevel);
		} catch (Exception ex) {
			if (tx != null) tx.rollback();
			LOG.severe("updateVerRouting error: " + ex.getMessage());
			throw new RuntimeException("updateVerRouting failed: " + ex.getMessage(), ex);
		}
	}

	// ══════════════════════════════════════════════════════════
	// VERIFICATION OPERATIONS — V1 (Author: Anusha)
	// ══════════════════════════════════════════════════════════

	// Loads cheques across ALL batches filtered by ver_level and status.
	// Called by getVerifiableBatchSummaries() to get all V1_PENDING cheques
	// and group them by batch ID to build the Phase 1 batch summary list.
	@Override
	public List<ChequeEntity> loadAllPendingV1ChequesAcrossAllBatches(String verLevel, String status) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques"
					+ " WHERE ver_level = :verLevel AND status = :status"
					+ " ORDER BY batch_id ASC, id ASC",
					Object[].class)
					.setParameter("verLevel", verLevel)
					.setParameter("status", status)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (!results.isEmpty()) {
				// Second query: fetch ver_action, ver_by, ver_remarks needed by
				// the V1 verification popup (not in PROJECTION_COLS).
				List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
				List<Object[]> verRows = session.createNativeQuery(
						"SELECT id, ver_action, ver_by, ver_remarks FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();
				Map<Long, Object[]> verMap = new HashMap<>();
				for (Object[] row : verRows)
					verMap.put(toLong(row[0]), row);
				results.forEach(c -> {
					Object[] row = verMap.get(c.getId());
					if (row != null) {
						c.setVerAction(row[1] != null ? row[1].toString() : null);
						c.setVerBy(row[2] != null ? row[2].toString() : null);
						c.setVerRemarks(row[3] != null ? row[3].toString() : null);
					}
				});
			}
			return results;
		} catch (Exception ex) {
			LOG.severe("loadChequesByVerLevel error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	// Loads ALL V1 cheques (V1_PENDING, VERIFIED, REJECTED) for ONE specific batch.
	// Single query fetches 22 columns including extra fields (transactionCode,
	// amountInWords, payeeAccountNo, baseNo) needed to populate the verification popup.
	// Called by getAllV1ChequesForBatch() when verifier opens a batch in Phase 2.
	@Override
	public List<ChequeEntity> loadAllV1ChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			// Inline select (not PROJECTION_COLS) because this query needs 22 columns
			// including extra fields — cannot use the 17-column constant here.
			List<Object[]> rows = session.createNativeQuery(
					"SELECT id, batch_id, cheque_id, cheque_no, account_no, "
					+ "sort_code, amount, cheque_date, drawer_name, payee_name, "
					+ "iqa_status, ver_status, status, high_value, duplicate_flag, "
					+ "created_at, updated_at, "
					+ "transaction_code, amount_in_words, amount_words_mismatch, "
					+ "payee_account_no, base_no "
					+ "FROM cts_cheques "
					+ "WHERE batch_id = :batchId AND ver_level = 'V1' "
					+ "ORDER BY id ASC",
					Object[].class)
					.setParameter("batchId", batchId)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows) {
				ChequeEntity c = mapProjectionRow(r);    // maps r[0]–r[16]
				// r[17]–r[21] are the extra fields beyond PROJECTION_COLS
				c.setTransactionCode(r[17] != null ? r[17].toString() : null);
				c.setAmountInWords(r[18] != null ? r[18].toString() : null);
				c.setAmountWordsMismatch(r[19] != null && (Boolean) r[19]);
				c.setPayeeAccountNo(r[20] != null ? r[20].toString() : null);
				c.setBaseNo(r[21] != null ? r[21].toString() : null);
				results.add(c);
			}
			return results;
		} catch (Exception ex) {
			LOG.severe("loadV1ChequesForBatch error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	// Counts cheques in a batch still awaiting action (V1_PENDING or V2_PENDING).
	// Called by checkAndFinalizeBatch() after every verifier action to decide
	// whether all cheques are done and the batch can advance to VERIFIED.
	// Returns -1L on error so caller can distinguish "zero pending" from "query failed".
	@Override
	public long countPendingVerificationForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques"
					+ " WHERE batch_id = :batchId"
					+ " AND status IN ('V1_PENDING', 'V2_PENDING')",
					Object.class)
					.setParameter("batchId", batchId)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingVerificationForBatch error: " + ex.getMessage());
			return -1L; // -1 signals DB error to caller — distinct from "zero pending"
		}
	}

	// Saves verifier action (Accept/Reject) on a cheque — updates status, ver_level,
	// ver_action, ver_by, ver_remarks in one UPDATE.
	// Called by validateAndAcceptCheque() and rejectCheque() in the service.
	// NOTE: ver_status mirrors status intentionally — both columns track the current state.
	@Override
	public void applyVerifierAction(Long chequeId, String status, String verLevel,
			String verAction, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"
					+ "  ver_status  = :status,"   // ver_status mirrors status on verifier action
					+ "  ver_level   = :verLevel,"
					+ "  ver_action  = :verAction,"
					+ "  ver_by      = :verBy,"
					+ "  ver_remarks = :verRemarks,"
					+ "  updated_at  = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",     status)
					.setParameter("verLevel",   verLevel)
					.setParameter("verAction",  verAction)
					.setParameter("verBy",      verBy)
					.setParameter("verRemarks", verRemarks)
					.setParameter("id",         chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("applyVerifierAction: cheque=" + chequeId + " action=" + verAction + " by=" + verBy);
		} catch (Exception ex) {
			if (tx != null) tx.rollback();
			LOG.severe("applyVerifierAction error: " + ex.getMessage());
			throw new RuntimeException("Failed to apply verifier action: " + ex.getMessage(), ex);
		}
	}

	// Escalates a cheque from V1 to V2 — flips ver_level to V2, sets is_referred=true,
	// and status/ver_status to V2_PENDING. Called by referCheque() in the service.
	// is_referred is a permanent flag — V2 must NEVER reset it back to false.
	@Override
	public void referToVerificationTwo(Long chequeId, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			// Hardcoded constants: only valid state for a V1→V2 refer is V2_PENDING/V2/Refer.
			// Parameters are NOT used for status/verLevel/verAction here — they are fixed by design.
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"
					+ "  ver_status  = :status,"
					+ "  ver_level   = :verLevel,"
					+ "  is_referred = true,"        // permanent flag — V2 must not reset this
					+ "  ver_action  = :verAction,"
					+ "  ver_by      = :verBy,"
					+ "  ver_remarks = :verRemarks,"
					+ "  updated_at  = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",     "V2_PENDING")
					.setParameter("verLevel",   "V2")
					.setParameter("verAction",  "Refer")
					.setParameter("verBy",      verBy)
					.setParameter("verRemarks", verRemarks)
					.setParameter("id",         chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("referToVerificationTwo: cheque=" + chequeId + " by=" + verBy);
		} catch (Exception ex) {
			if (tx != null) tx.rollback();
			LOG.severe("referToVerificationTwo error: " + ex.getMessage());
			throw new RuntimeException("Failed to refer cheque to V2: " + ex.getMessage(), ex);
		}
	}

	// Counts V1-actioned cheques (VERIFIED + REJECTED + V2_PENDING) for a single batch.
	// Called by getVerifiableBatchSummaries() to show processed count per batch row.
	// For multiple batches at once use countV1ProcessedForBatches() — avoids N+1 queries.
	@Override
	public long countV1ProcessedForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques"
					+ " WHERE batch_id = :batchId"
					+ "   AND ver_level = 'V1'"
					+ "   AND status IN ('VERIFIED', 'REJECTED', 'V2_PENDING')",
					Object.class)
					.setParameter("batchId", batchId)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countV1ProcessedForBatch error: " + ex.getMessage());
			return 0L;
		}
	}

	// Returns distinct batch IDs that have at least one V1 cheque already actioned.
	// Called by getVerifiableBatchSummaries() to include fully-verified batches
	// in the Phase 1 history list without pulling in HV-only or V2-only batches.
	@Override
	public Set<String> loadBatchIdsWithV1ProcessedCheques() {
		try (Session session = HibernateUtil.getSession()) {
			List<String> rows = session.createNativeQuery(
					"SELECT DISTINCT batch_id "
					+ "FROM cts_cheques "
					+ "WHERE ver_level = 'V1' "
					+ "  AND status IN ('VERIFIED', 'REJECTED', 'V2_PENDING')",
					String.class)
					.getResultList();
			return new HashSet<>(rows);
		} catch (Exception ex) {
			LOG.severe("loadBatchIdsWithV1ProcessedCheques error: " + ex.getMessage());
			return Collections.emptySet();
		}
	}

	// Returns V1 processed counts for ALL given batch IDs in one query — no N+1.
	// Called by getVerifiableBatchSummaries() to populate the processed count
	// column for every batch in the Phase 1 list at once.
	@Override
	public Map<String, Long> countV1ProcessedForBatches(Set<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty())
			return Collections.emptyMap();
		try (Session session = HibernateUtil.getSession()) {
			// GROUP BY batch_id gives one count row per batch — single round-trip to DB
			List<Object[]> rows = session.createNativeQuery(
					"SELECT batch_id, COUNT(*) "
					+ "FROM cts_cheques "
					+ "WHERE batch_id  IN :ids "
					+ "  AND ver_level = 'V1' "
					+ "  AND status    IN ('VERIFIED', 'REJECTED', 'V2_PENDING') "
					+ "GROUP BY batch_id",
					Object[].class)
					.setParameter("ids", new ArrayList<>(batchIds))
					.getResultList();

			Map<String, Long> result = new HashMap<>();
			for (Object[] row : rows) {
				String batchId = (String) row[0];
				Long   count   = ((Number) row[1]).longValue();
				result.put(batchId, count);
			}
			// Batches not in the map have zero processed cheques — caller handles missing entries
			return result;
		} catch (Exception ex) {
			LOG.severe("countV1ProcessedForBatches error: " + ex.getMessage());
			return Collections.emptyMap();
		}
	}
		
		
}