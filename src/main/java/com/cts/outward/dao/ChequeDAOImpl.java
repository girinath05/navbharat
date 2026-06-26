/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — DAO Layer
 *  File        : ChequeDAOImpl.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Hibernate-based implementation of {@link ChequeDAO}.
 *  All SQL is native PostgreSQL — no HQL used anywhere in
 *  this class.
 *
 *  Two families of methods:
 *    • Read methods   — sessionless (try-with-resources, no tx).
 *                       Return safe defaults (empty list / 0L)
 *                       on exception so callers never NPE.
 *    • Write methods  — explicit Transaction + rollback-on-exception.
 *                       Throw RuntimeException on failure so the
 *                       composer can show an error message.
 *
 * ──────────────────────────────────────────────────────────────
 *  PROJECTION STRATEGY
 * ──────────────────────────────────────────────────────────────
 *  List-view queries (loadChequesForBatch, loadHvCheques, …)
 *  select only PROJECTION_COLS — 17 columns — and intentionally
 *  exclude the BYTEA columns (front_image / rear_image) and the
 *  wider text columns (amount_in_words, payee_account_no, …).
 *
 *  Reasons:
 *    1. Avoids streaming large BLOBs for every list row.
 *    2. Matches the existing 17-field ChequeEntity projection
 *       constructor exactly, so no new constructor needed.
 *    3. A second lightweight query merges the extra text columns
 *       back in (transaction_code, amount_in_words, etc.)
 *       using an IN-list — one round trip for all rows.
 *
 *  Image bytes are loaded on demand via loadChequeWithImages()
 *  which does a session.get() (full entity load).
 *
 * ──────────────────────────────────────────────────────────────
 *  ASYNC IMAGE SAVE
 * ──────────────────────────────────────────────────────────────
 *  saveCheques() saves metadata synchronously, then hands off
 *  BLOB writes to IMAGE_EXEC (2-thread pool, daemon threads).
 *  This prevents large BLOB payloads from blocking the HTTP
 *  request thread on batch import. The async path uses a raw
 *  JDBC PreparedStatement batch rather than Hibernate entities
 *  to avoid loading the full entity graph.
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS CLASS FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Service Layer]                [This Class]            [DB]
 *  ──────────────────────────     ────────────────────    ───────────────────
 *  ChequeServiceImpl          ──► ChequeDAOImpl       ──► cts_cheques table
 *  BatchServiceImpl (submit)  ──► (updateVerRouting)  ──► cts_batches table
 *  VerificationOneComposer    ──► (applyVerifierAction,    (via HibernateUtil)
 *  VerificationTwoComposer         referToVerificationTwo)
 *
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;           // Used for amount columns (PostgreSQL NUMERIC → BigDecimal)
import java.sql.Connection;            // Raw JDBC connection for async BLOB batch update
import java.sql.PreparedStatement;     // JDBC PreparedStatement for the async image batch
import java.time.LocalDateTime;        // Maps PostgreSQL TIMESTAMP columns
import java.util.ArrayList;            // Mutable list for building result sets
import java.util.Collections;          // Collections.emptyList() / emptySet() / emptyMap() — safe defaults
import java.util.HashMap;              // extraMap / tcMap / verMap — id-keyed lookup maps
import java.util.HashSet;              // Return type for Set<String> methods
import java.util.List;                 // Primary collection type for result sets
import java.util.Map;                  // Return type for batch-count map methods
import java.util.Set;                  // Return type for batch-id / cheque-no existence checks
import java.util.concurrent.ExecutorService;  // Manages the async image-save thread pool
import java.util.concurrent.Executors;        // Factory for IMAGE_EXEC fixed-thread pool
import java.util.logging.Logger;       // JUL logger — consistent with rest of codebase

import org.hibernate.Session;          // Hibernate session — opened per method via try-with-resources
import org.hibernate.Transaction;      // Explicit transaction — used by all write methods
import org.hibernate.query.NativeQuery; // Typed native SQL query — used by full-load fallback paths

import com.cts.outward.entity.BatchEntity;  // Mapped to cts_batches; used in loadDashboardData
import com.cts.outward.entity.ChequeEntity; // Primary entity — mapped to cts_cheques
import com.cts.util.HibernateUtil;     // Factory for Hibernate sessions; wraps SessionFactory

public class ChequeDAOImpl implements ChequeDAO {

	// JUL logger — named after this class for log filtering.
	// Used throughout for INFO (successful writes) and SEVERE (errors).
	private static final Logger LOG = Logger.getLogger(ChequeDAOImpl.class.getName());

	// ──────────────────────────────────────────────────────────────────────
	// PROJECTION COLUMN LIST
	// ──────────────────────────────────────────────────────────────────────

	// Exact 17-column list used by all list-view SELECT queries.
	// Column order must match the ChequeEntity 17-arg projection constructor
	// parameter order exactly — any mismatch causes a ClassCastException at runtime.
	//
	// Intentionally excludes:
	//   • front_image / rear_image  — large BYTEA; loaded only by loadChequeWithImages()
	//   • amount_in_words           — loaded by the second "extra" query in loadChequesForBatch()
	//   • payee_account_no          — same as above
	//   • transaction_code          — same as above
	//   • base_no                   — same as above
	//   • ver_level, ver_action, … — loaded by a third "ver" query only where needed
	private static final String PROJECTION_COLS =
			"id, batch_id, cheque_id, cheque_no, account_no, "
			+ "sort_code, amount, cheque_date, drawer_name, payee_name, "
			+ "iqa_status, ver_status, status, high_value, duplicate_flag, "
			+ "created_at, updated_at";

	// ──────────────────────────────────────────────────────────────────────
	// PROJECTION ROW MAPPER
	// ──────────────────────────────────────────────────────────────────────

	/**
	 * Maps one {@code Object[]} row (column order = {@link #PROJECTION_COLS})
	 * into a {@link ChequeEntity} using the 17-field projection constructor.
	 *
	 * <p>Helper methods {@link #toLong}, {@link #toBigDecimal}, and
	 * {@link #toLocalDateTime} normalise JDBC driver type variance:
	 * PostgreSQL may return {@code Integer} where Hibernate expects {@code Long},
	 * or {@code Timestamp} where Java expects {@code LocalDateTime}.
	 *
	 * @param r  one Object[] row from a native query result list
	 * @return   populated ChequeEntity (no images, no extra text fields)
	 */
	private static ChequeEntity mapProjectionRow(Object[] r) {
		return new ChequeEntity(
				toLong(r[0]),                      // r[0]  id            — BIGSERIAL primary key
				(String) r[1],                     // r[1]  batch_id      — FK to cts_batches
				(String) r[2],                     // r[2]  cheque_id     — CTS system identifier
				(String) r[3],                     // r[3]  cheque_no     — physical cheque number on leaf
				(String) r[4],                     // r[4]  account_no    — drawer (payer) account
				(String) r[5],                     // r[5]  sort_code     — MICR bank/branch routing code
				toBigDecimal(r[6]),                // r[6]  amount        — NUMERIC(15,2) cheque value
				(String) r[7],                     // r[7]  cheque_date   — date printed on cheque leaf
				(String) r[8],                     // r[8]  drawer_name   — account holder name (payer)
				(String) r[9],                     // r[9]  payee_name    — beneficiary name
				(String) r[10],                    // r[10] iqa_status    — image quality: Pass/Fail
				(String) r[11],                    // r[11] ver_status    — verification pipeline status
				(String) r[12],                    // r[12] status        — overall cheque lifecycle status
				r[13] != null && (Boolean) r[13],  // r[13] high_value    — true if amount >= HV threshold
				r[14] != null && (Boolean) r[14],  // r[14] duplicate_flag— true if cheque_no seen before
				toLocalDateTime(r[15]),            // r[15] created_at    — row insert timestamp
				toLocalDateTime(r[16]));           // r[16] updated_at    — last update timestamp
	}

	/**
	 * Safely converts an Object returned by the JDBC driver to {@code Long}.
	 * PostgreSQL may return {@code Integer}, {@code BigInteger}, or {@code Long}
	 * depending on the column type and driver version.
	 *
	 * @param o raw JDBC column value
	 * @return Long value, or null if input is null
	 */
	private static Long toLong(Object o) {
		if (o == null) return null;
		if (o instanceof Long) return (Long) o;  // fast path — most common case
		return ((Number) o).longValue();          // handles Integer, Short, BigInteger, etc.
	}

	/**
	 * Safely converts an Object returned by the JDBC driver to {@code BigDecimal}.
	 * PostgreSQL NUMERIC columns may come back as {@code BigDecimal} directly,
	 * or as {@code Double} / {@code String} in some edge cases.
	 *
	 * @param o raw JDBC column value
	 * @return BigDecimal value, or null if input is null
	 */
	private static BigDecimal toBigDecimal(Object o) {
		if (o == null) return null;
		if (o instanceof BigDecimal) return (BigDecimal) o;  // fast path
		return new BigDecimal(o.toString());                  // toString() works for all Number subtypes
	}

	/**
	 * Safely converts an Object returned by the JDBC driver to {@code LocalDateTime}.
	 * PostgreSQL TIMESTAMP columns arrive as {@code java.sql.Timestamp} via Hibernate
	 * native queries (not as LocalDateTime, even when the entity field is mapped that way).
	 *
	 * @param o raw JDBC column value
	 * @return LocalDateTime, or null if input is null
	 */
	private static LocalDateTime toLocalDateTime(Object o) {
		if (o == null) return null;
		if (o instanceof LocalDateTime) return (LocalDateTime) o;  // future-safe fast path
		return ((java.sql.Timestamp) o).toLocalDateTime();         // standard conversion path
	}

	// ══════════════════════════════════════════════════════════════════════
	// DASHBOARD
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads all batches and the global pending-cheque count in two queries,
	 * packages them in a {@link DashboardData} record for the Scan Module dashboard.
	 *
	 * <h3>Called by</h3>
	 * {@code BatchChequeEntryComposer.refreshStats()} on page load and after every
	 * batch import / discard.
	 *
	 * <h3>Query 1</h3>
	 * {@code SELECT * FROM cts_batches ORDER BY created_at DESC}
	 * — full entity load; batches are small (one row per scan session).
	 *
	 * <h3>Query 2</h3>
	 * {@code SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending'}
	 * — scalar aggregate; drives the "Pending" stat card number.
	 *
	 * <h3>Failure</h3>
	 * Returns {@code DashboardData(emptyList, 0L)} on any exception
	 * so the dashboard renders safely without data.
	 */
	@Override
	public DashboardData loadDashboardData() {
		try (Session session = HibernateUtil.getSession()) {
			// Query 1: all batches, newest first — used to populate the batch table
			List<BatchEntity> batches = session
					.createNativeQuery("SELECT * FROM cts_batches ORDER BY created_at DESC", BatchEntity.class)
					.list();

			// Query 2: count of cheques still in Pending ver_status — drives "Pending Cheques" stat card
			Long pending = ((Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending'", Object.class)
					.uniqueResult()).longValue();

			// Return both results packaged; callers read them via DashboardData.batches() / .pendingCount()
			return new DashboardData(batches, pending != null ? pending : 0L);
		} catch (Exception ex) {
			LOG.severe("loadDashboardData error: " + ex.getMessage());
			return new DashboardData(Collections.emptyList(), 0L); // safe fallback — dashboard shows zeros
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// CHEQUE CRUD
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Saves or updates a single cheque entity.
	 * Uses {@code session.persist()} for new entities (null id) and
	 * {@code session.merge()} for existing ones (non-null id).
	 *
	 * <h3>Called by</h3>
	 * Scan import pipeline — one cheque at a time when processing a single file.
	 * Bulk import uses {@link #saveCheques(List)} instead.
	 *
	 * @param cheque the cheque to insert or update
	 * @throws RuntimeException if the DB write fails (tx is rolled back)
	 */
	@Override
	public void saveCheque(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			if (cheque.getId() == null)
				session.persist(cheque);  // INSERT — new row; Hibernate generates the id via BIGSERIAL
			else
				session.merge(cheque);    // UPDATE — merge detached entity back into session
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback(); // undo the partial INSERT/UPDATE before surfacing the error
			LOG.severe("saveCheque error: " + ex.getMessage());
			throw new RuntimeException("Failed to save cheque: " + ex.getMessage(), ex);
		}
	}

	// ──────────────────────────────────────────────────────────────────────
	// ASYNC IMAGE SAVE — background thread pool for BLOB writes
	// ──────────────────────────────────────────────────────────────────────

	// 2-thread fixed pool; daemon=true so threads don't block JVM shutdown.
	// Created once at class load — shared across all saveCheques() calls.
	// Named "img-save" for visibility in thread dumps / profilers.
	private static final ExecutorService IMAGE_EXEC =
	        Executors.newFixedThreadPool(2, r -> {
	        	Thread t = new Thread(r, "img-save"); // descriptive thread name
	        	t.setDaemon(true);                    // daemon: dies with JVM, no pending-work guarantee
	        	return t;
	        });

	/**
	 * Bulk-saves a list of cheques in two phases:
	 *
	 * <h3>Phase 1 — synchronous metadata save</h3>
	 * Front and rear image bytes are stripped from every entity before the
	 * Hibernate session opens (stored in parallel lists {@code fronts} / {@code rears}).
	 * The stripped entities are then persisted in batches of 50 (flush+clear every 50
	 * to prevent the first-level cache from growing unboundedly).
	 * Images are restored to entity objects after the commit so callers still hold
	 * the bytes in memory.
	 *
	 * <h3>Phase 2 — async BLOB write</h3>
	 * After the metadata commit, image bytes are handed off to {@link #IMAGE_EXEC}
	 * via a raw JDBC {@code PreparedStatement} batch UPDATE. This prevents the large
	 * BYTEA payloads from blocking the HTTP thread that triggered the batch import.
	 * The async task skips rows where both front and rear are null.
	 *
	 * <h3>Why strip images before Hibernate persist?</h3>
	 * Hibernate maps the BYTEA columns as {@code @Lob} / {@code byte[]}. Writing
	 * large BLOBs through Hibernate causes memory pressure and very slow commits
	 * for large batches. The raw JDBC path in Phase 2 is faster and avoids loading
	 * the full entity graph.
	 *
	 * <h3>Called by</h3>
	 * {@code CtsZipParserImpl.importZip()} — {@code BatchServiceImpl.createBatch()}
	 * after parsing all cheques from the uploaded ZIP.
	 *
	 * @param cheques list of fully-populated ChequeEntity objects (may include image bytes)
	 * @throws RuntimeException if the synchronous metadata save fails (async failure is logged only)
	 */
	@Override
	public void saveCheques(List<ChequeEntity> cheques) {
	    if (cheques == null || cheques.isEmpty())
	        return; // nothing to do — return early to avoid opening a session

	    // ── Phase 1 prep: detach image bytes from entities ──────────────
	    // Store images in parallel index-aligned lists so we can restore them later.
	    List<byte[]> fronts = new ArrayList<>(cheques.size());
	    List<byte[]> rears  = new ArrayList<>(cheques.size());
	    for (ChequeEntity c : cheques) {
	        fronts.add(c.getFrontImage()); // preserve front image; index i matches cheques.get(i)
	        rears.add(c.getRearImage());   // preserve rear image
	        c.setFrontImage(null);         // null out before Hibernate persist — avoids BLOB in tx
	        c.setRearImage(null);          // same for rear
	    }

	    // ── Phase 1: synchronous metadata persist ────────────────────────
	    Transaction tx = null;
	    try (Session session = HibernateUtil.getSession()) {
	        tx = session.beginTransaction();
	        int count = 0;
	        for (ChequeEntity c : cheques) {
	            session.persist(c); // INSERT cheque row (no images)
	            count++;
	            // Flush and clear the first-level cache every 50 rows.
	            // Prevents OutOfMemoryError for large batches (e.g. 500+ cheques).
	            if (count % 50 == 0) { session.flush(); session.clear(); }
	        }
	        session.flush(); // flush any remaining entities not caught by the mod-50 check
	        tx.commit();
	        LOG.info("Saved " + cheques.size() + " cheques (metadata) to Supabase");
	    } catch (Exception ex) {
	        if (tx != null) tx.rollback(); // roll back the entire batch on any failure
	        // Restore images before throwing — caller still holds references and may retry
	        for (int i = 0; i < cheques.size(); i++) {
	            cheques.get(i).setFrontImage(fronts.get(i));
	            cheques.get(i).setRearImage(rears.get(i));
	        }
	        LOG.severe("saveCheques error: " + ex.getMessage());
	        throw new RuntimeException("Failed to save cheques: " + ex.getMessage(), ex);
	    }

	    // Restore images in-memory after successful commit
	    // (caller may still need them for display or further processing)
	    for (int i = 0; i < cheques.size(); i++) {
	        cheques.get(i).setFrontImage(fronts.get(i));
	        cheques.get(i).setRearImage(rears.get(i));
	    }

	    // ── Phase 2: async BLOB write — skip if no images present ────────
	    boolean hasImages = false;
	    for (byte[] b : fronts) { if (b != null) { hasImages = true; break; } }
	    if (!hasImages) for (byte[] b : rears) { if (b != null) { hasImages = true; break; } }
	    if (!hasImages) return; // all images null — nothing to write asynchronously

	    // Snapshot lists and IDs before submitting to background thread.
	    // The caller may mutate the original lists after this method returns.
	    final List<Long>   snapIds    = new ArrayList<>(cheques.size());
	    final List<byte[]> snapFronts = new ArrayList<>(fronts); // defensive copy
	    final List<byte[]> snapRears  = new ArrayList<>(rears);  // defensive copy
	    for (ChequeEntity c : cheques) snapIds.add(c.getId()); // ids now populated by Hibernate after persist

	    IMAGE_EXEC.submit(() -> {
	        try (Session imgSession = HibernateUtil.getSession()) {
	            // doWork gives us a raw JDBC Connection inside the Hibernate session.
	            // We use a PreparedStatement batch for maximum BLOB write throughput.
	            imgSession.doWork((Connection conn) -> {
	                String sql = "UPDATE cts_cheques SET front_image = ?, rear_image = ? WHERE id = ?";
	                try (PreparedStatement ps = conn.prepareStatement(sql)) {
	                    for (int i = 0; i < snapIds.size(); i++) {
	                        Long rowId = snapIds.get(i);
	                        if (rowId == null) continue; // id was null — persist failed? skip safely

	                        byte[] front = snapFronts.get(i);
	                        byte[] rear  = snapRears.get(i);
	                        if (front == null && rear == null) continue; // no images for this row — skip

	                        // Set image bytes or SQL NULL if not present for one side
	                        if (front != null) ps.setBytes(1, front); else ps.setNull(1, java.sql.Types.BINARY);
	                        if (rear  != null) ps.setBytes(2, rear);  else ps.setNull(2, java.sql.Types.BINARY);
	                        ps.setLong(3, rowId); // WHERE id = ?
	                        ps.addBatch();        // accumulate in JDBC batch — not sent yet
	                    }
	                    ps.executeBatch(); // single round-trip to DB for all image UPDATEs
	                }
	            });
	            LOG.info("Async image save complete for " + snapIds.size() + " cheques");
	        } catch (Exception ex) {
	            // Failure is non-fatal — metadata already committed.
	            // Operator can re-upload if images are missing.
	            LOG.severe("Async image save failed: " + ex.getMessage());
	        }
	    });
	}

	/**
	 * Returns the set of cheque numbers (from the given list) that already
	 * exist in {@code cts_cheques} — used by the import pipeline to detect
	 * duplicate cheque leaves before inserting.
	 *
	 * <h3>Called by</h3>
	 * {@code CtsZipParserImpl.importZip()} — filters out already-seen cheque numbers
	 * before calling {@link #saveCheques(List)}.
	 *
	 * @param chequeNos list of cheque numbers to check; may be empty
	 * @return set of numbers that are already in DB; empty set on error or empty input
	 */
	@Override
	public Set<String> findExistingChequeNos(List<String> chequeNos) {
		if (chequeNos == null || chequeNos.isEmpty())
			return Collections.emptySet(); // nothing to check — avoid pointless DB round-trip
		try (Session session = HibernateUtil.getSession()) {
			@SuppressWarnings("unchecked")
			// IN-list query — one round-trip regardless of list size
			List<String> found = session
					.createNativeQuery("SELECT cheque_no FROM cts_cheques WHERE cheque_no IN :nos", String.class)
					.setParameter("nos", chequeNos)
					.getResultList();
			return new HashSet<>(found); // HashSet for O(1) contains() in duplicate-check loop
		} catch (Exception ex) {
			LOG.severe("findExistingChequeNos error: " + ex.getMessage());
			return Collections.emptySet(); // fail open — caller proceeds; duplicates caught by DB unique constraint
		}
	}

	/**
	 * Returns the set of batch IDs (from the given list) where ALL cheques
	 * have status = 'Ready'. Used by the Scan Module to decide which batches
	 * can be submitted for verification.
	 *
	 * <h3>SQL logic</h3>
	 * {@code HAVING COUNT(*) > 0 AND COUNT(*) = COUNT(CASE WHEN status = 'Ready' THEN 1 END)}
	 * — only batches where every cheque row is Ready qualify.
	 * A batch with zero cheques is excluded by the {@code COUNT(*) > 0} guard.
	 *
	 * <h3>Called by</h3>
	 * {@code BatchChequeEntryComposer.refreshStats()} — drives the "Ready to Submit"
	 * count on the dashboard and enables the Submit button.
	 *
	 * @param batchIds candidate batch IDs to check; may be empty
	 * @return set of batch IDs where all cheques are Ready; empty set on error
	 */
	@Override
	public Set<String> loadReadyBatchIds(List<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty())
			return Collections.emptySet();
		try (Session session = HibernateUtil.getSession()) {
			@SuppressWarnings("unchecked")
			List<String> ready = session.createNativeQuery(
					"SELECT batch_id FROM cts_cheques WHERE batch_id IN :ids "
					+ "GROUP BY batch_id "
					// All-Ready check: total count must equal count of Ready cheques
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

	// ══════════════════════════════════════════════════════════════════════
	// CHEQUE LIST LOADING
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads all cheques for a batch using a two-query projection strategy
	 * to avoid fetching BLOB columns in the list view.
	 *
	 * <h3>Query 1 — PROJECTION_COLS (17 columns)</h3>
	 * Fetches the core columns for every cheque in the batch ordered by id.
	 * Each row is mapped to a ChequeEntity via {@link #mapProjectionRow(Object[])}.
	 *
	 * <h3>Query 2 — extra text columns</h3>
	 * Fetches {@code transaction_code, amount_in_words, amount_words_mismatch,
	 * payee_account_no, base_no} for the same IDs in one IN-list query.
	 * Results are merged into the already-built entity list via an id-keyed map —
	 * no N+1 queries, no extra entity load.
	 *
	 * <h3>Fallback</h3>
	 * If either query fails, falls back to {@link #loadChequesForBatchFull(String)}
	 * which does {@code SELECT *} (includes BLOBs). Slower but correct.
	 *
	 * <h3>Called by</h3>
	 * {@code ChequeServiceImpl.getChequesForBatch()} ->
	 * {@code BatchDetailComposer.loadChequesForBatch()} (initial load + after save/delete).
	 *
	 * @param batchId the parent batch ID
	 * @return ordered list of ChequeEntity (no image bytes); empty list if none
	 */
	@Override
	public List<ChequeEntity> loadChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			// Query 1: 17-column projection — fast, no BLOBs
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId)
					.getResultList();

			// Map each raw row to a ChequeEntity using the 17-field constructor
			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (!results.isEmpty()) {
				// Collect all cheque IDs for the IN-list second query
				List<Long> ids = results.stream().map(ChequeEntity::getId).toList();

				// Query 2: fetch extra text columns for the same cheques — one round-trip
				List<Object[]> extraRows = session.createNativeQuery(
						"SELECT id, transaction_code, amount_in_words, amount_words_mismatch, payee_account_no, base_no "
								+ "FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();

				// Build id -> extra-row map for O(1) merge below
				Map<Long, Object[]> extraMap = new HashMap<>();
				for (Object[] row : extraRows)
					extraMap.put(toLong(row[0]), row);

				// Merge extra fields into each entity — avoids a second loop with index tracking
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
			// Projection failed (schema mismatch, constructor arity error, etc.)
			// Fall back to full SELECT * — slower but guaranteed to work
			LOG.warning("loadChequesForBatch projection failed, falling back: " + ex.getMessage());
			return loadChequesForBatchFull(batchId);
		}
	}

	/**
	 * Full-load fallback: loads all cheques for a batch via {@code SELECT *}.
	 * Includes BYTEA columns (front_image / rear_image) — use only when
	 * the projection query fails or when images are explicitly needed alongside
	 * all other fields.
	 *
	 * <p>Normal path is {@link #loadChequesForBatch(String)}.
	 * This method is called automatically when that query throws.
	 *
	 * @param batchId the parent batch ID
	 * @return list of fully-mapped ChequeEntity; empty list on error
	 */
	@Override
	public List<ChequeEntity> loadChequesForBatchFull(String batchId) {
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

	// ──────────────────────────────────────────────────────────────────────
	// IMAGE LOAD
	// ──────────────────────────────────────────────────────────────────────

	/**
	 * Loads a single cheque with its full image bytes via {@code session.get()}.
	 *
	 * <p>The 17-field projection constructor used by all list queries intentionally
	 * excludes {@code front_image} and {@code rear_image}. This method provides the
	 * only path to retrieve those BYTEA columns — called on demand when the
	 * {@code ChequeImageServlet} needs to stream an image to the browser.
	 *
	 * <h3>Called by</h3>
	 * {@code ChequeImageServlet.doGet()} — triggered when the Maker or Verifier
	 * clicks a cheque thumbnail in the detail/verification popup.
	 *
	 * @param chequeId primary key of the cheque
	 * @return fully-mapped ChequeEntity including image bytes; null on error or not found
	 */
	@Override
	public ChequeEntity loadChequeWithImages(Long chequeId) {
		try (Session session = HibernateUtil.getSession()) {
			// session.get() loads the full entity including @Lob BYTEA columns
			return session.get(ChequeEntity.class, chequeId);
		} catch (Exception ex) {
			LOG.severe("loadChequeWithImages error: " + ex.getMessage());
			return null; // caller (servlet) handles null -> 404
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// CHEQUE STATUS UPDATES
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Updates {@code status} and {@code ver_status} columns for a single cheque.
	 *
	 * <h3>Called by</h3>
	 * {@code BatchServiceImpl.submitBatch()} — flips all cheques in a batch
	 * from {@code Pending} to the appropriate verification routing status
	 * before handing off to the verifier queue.
	 *
	 * @param chequeId  primary key of the cheque to update
	 * @param status    new value for the {@code status} column
	 * @param verStatus new value for the {@code ver_status} column
	 */
	@Override
	public void updateChequeStatus(Long chequeId, String status, String verStatus) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET status = :status, ver_status = :verStatus, "
					+ "updated_at = CURRENT_TIMESTAMP WHERE id = :id")
					.setParameter("status", status)
					.setParameter("verStatus", verStatus)
					.setParameter("id", chequeId).executeUpdate();
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateChequeStatus error: " + ex.getMessage());
			// No rethrow — caller (submitBatch) continues for remaining cheques
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// CHEQUE DELETE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Deletes a single cheque row by primary key.
	 * Simple DELETE — does NOT update parent batch control totals.
	 * For atomic delete + batch decrement, use {@link #deleteAndDecrementBatch(long)}.
	 *
	 * <h3>Called by</h3>
	 * Internal use only — superseded by {@link #deleteAndDecrementBatch(long)}
	 * for the Maker delete action. Kept in interface for possible admin/repair use.
	 *
	 * @param chequeId primary key of the cheque to delete
	 * @throws RuntimeException if the delete fails
	 */
	@Override
	public void deleteCheque(Long chequeId) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
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
	 * Atomically deletes a cheque and decrements the parent batch's control totals
	 * within a single Hibernate transaction.
	 *
	 * <h3>Why two operations in one transaction</h3>
	 * Keeping the cheque delete and the batch total decrement in the same commit
	 * ensures the batch's {@code total_cheques} and {@code control_amount} columns
	 * are never out of sync with the actual cheque count — even if the JVM crashes
	 * between the two writes.
	 *
	 * <h3>Step 1 — load the entity</h3>
	 * {@code session.get()} loads the cheque to retrieve {@code batchId} and
	 * {@code amount} before deleting. Both are needed for the batch UPDATE.
	 * Returns early (rollback) if the cheque is not found — idempotent.
	 *
	 * <h3>Step 2 — delete the cheque</h3>
	 * {@code session.remove()} issues {@code DELETE FROM cts_cheques WHERE id=?}.
	 *
	 * <h3>Step 3 — decrement batch totals</h3>
	 * Native UPDATE on {@code cts_batches} using {@code GREATEST(0, ...)} guards
	 * to prevent negative values in case of data inconsistency.
	 *
	 * <h3>Called by</h3>
	 * {@code ChequeServiceImpl.deleteCheque()} -> {@code BatchDetailComposer.onDeleteCheque()}.
	 *
	 * @param chequeId primary key of the cheque to delete
	 * @throws RuntimeException wrapping the underlying exception if the transaction fails
	 */
	@Override
	public void deleteAndDecrementBatch(long chequeId) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();

			// Step 1: Load cheque to get batchId + amount — needed for the batch decrement below.
			// session.get() returns null if the row doesn't exist (not an exception).
			ChequeEntity cheque = session.get(ChequeEntity.class, chequeId);
			if (cheque == null) {
				tx.rollback(); // nothing to delete — close the empty transaction cleanly
				return;
			}

			String parentBatchId = cheque.getBatchId(); // FK to cts_batches.batch_id
			// Default to ZERO if amount is null — GREATEST(0, 0 - 0) = 0, safe no-op
			BigDecimal chequeAmount = cheque.getAmount() != null
					? cheque.getAmount() : BigDecimal.ZERO;

			// Step 2: Delete the cheque row — session tracks this in the tx
			session.remove(cheque);

			// Step 3: Decrement batch control totals in the same transaction.
			// Native SQL used to avoid loading the full BatchEntity just to change two columns.
			// GREATEST(0, ...) prevents negative values on data inconsistency (safety net only).
			session.createNativeMutationQuery(
					"UPDATE cts_batches " +
					"SET total_cheques  = GREATEST(0, total_cheques - 1), " +          // decrement count
					"    control_amount = GREATEST(0, control_amount - :chequeAmount) " // decrement amount
					+ "WHERE batch_id = :parentBatchId")
					.setParameter("chequeAmount", chequeAmount)
					.setParameter("parentBatchId", parentBatchId)
					.executeUpdate();

			// Commit both the DELETE and the UPDATE atomically
			tx.commit();
			LOG.info("deleteAndDecrementBatch: cheque=" + chequeId + " batch=" + parentBatchId);
		} catch (Exception ex) {
			if (tx != null) tx.rollback(); // undo both operations
			LOG.severe("deleteAndDecrementBatch error: " + ex.getMessage());
			throw new RuntimeException("Failed to delete cheque #" + chequeId + ": " + ex.getMessage(), ex);
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// COUNT / AGGREGATE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns the count of cheques with {@code ver_status = 'Pending'} across
	 * all batches. Drives the "Pending Cheques" stat card on the Scan Module dashboard.
	 *
	 * <h3>Called by</h3>
	 * {@code ChequeServiceImpl.countPending()} -> {@code BatchChequeEntryComposer.refreshStats()}.
	 *
	 * @return total pending cheque count; 0 on DB error
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
			return 0; // safe default — dashboard shows 0 rather than crashing
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// MICR REPAIR OPERATIONS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads cheques in a batch that failed IQA and are awaiting MICR repair.
	 * Filters: {@code iqa_status = 'Fail'} AND {@code status = 'MICR_Repair'}.
	 * Results are ordered by id (insertion order).
	 *
	 * <h3>Called by</h3>
	 * {@code MicrRepairComposer} (inward clearing) to populate the repair queue.
	 *
	 * @param batchId the batch to query
	 * @return list of IQA-failed cheques awaiting repair; empty list on error
	 */
	@Override
	public List<ChequeEntity> loadIqaFailedCheques(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
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
	 * Persists Maker-edited MICR field values for a single cheque.
	 *
	 * <h3>Why NOT a full entity save</h3>
	 * The projection query in {@link #loadChequesForBatch} intentionally does NOT
	 * load {@code ver_level} — so the entity's verLevel field is null at edit time.
	 * A full {@code session.merge()} would write that null back to DB, clobbering
	 * the {@code ver_level} set by {@code BatchServiceImpl.submitBatch()}.
	 * The targeted UPDATE below only touches Maker-editable columns — verLevel and
	 * verStatus are deliberately excluded.
	 *
	 * <h3>Columns written</h3>
	 * sort_code, transaction_code, account_no, payee_account_no, base_no,
	 * amount, amount_in_words, amount_words_mismatch, cheque_date, payee_name,
	 * status, updated_at.
	 *
	 * <h3>Called by</h3>
	 * {@code ChequeServiceImpl.saveChequeFields()} ->
	 * {@code BatchDetailComposer.onSaveChequeFields()}.
	 *
	 * @param cheque ChequeEntity with Maker edits applied; must have a valid non-null id
	 * @throws RuntimeException if the update fails
	 */
	@Override
	public void updateChequeFields(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET "
					+ "  sort_code             = :sortCode, "       // MICR city/bank/branch routing
					+ "  transaction_code      = :txCode, "         // MICR transaction type code
					+ "  account_no            = :accountNo, "      // drawer (payer) account number
					+ "  payee_account_no      = :payeeAccountNo, " // receiver account number
					+ "  base_no               = :baseNo, "         // MICR base/serial segment
					+ "  amount                = :amount, "         // cheque face value
					+ "  amount_in_words       = :amountInWords, "  // written amount for mismatch check
					+ "  amount_words_mismatch = :mismatch, "       // true if digits != words
					+ "  cheque_date           = :chequeDate, "     // date on cheque leaf
					+ "  payee_name            = :payeeName, "      // beneficiary name
					+ "  status                = :status, "         // lifecycle status (e.g. Ready)
					// ver_level intentionally excluded — would null-out the verifier routing
					+ "  updated_at            = CURRENT_TIMESTAMP " // audit trail
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
	 * Returns the count of cheques repaired today.
	 * "Repaired" is defined as {@code status = 'Sent_for_Verification'}
	 * with {@code updated_at >= CURRENT_DATE} (midnight today).
	 *
	 * <h3>Called by</h3>
	 * MICR Repair dashboard stat card — "Repaired Today" counter.
	 *
	 * @return count of cheques repaired since midnight; 0 on error
	 */
	@Override
	public long countRepairedToday() {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session
					.createNativeQuery(
							"SELECT COUNT(*) FROM cts_cheques "
									// updated_at >= CURRENT_DATE matches anything from 00:00:00 today
									+ "WHERE status = 'Sent_for_Verification' AND updated_at >= CURRENT_DATE",
							Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countRepairedToday error: " + ex.getMessage());
			return 0L;
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// HIGH VALUE (HV) OPERATIONS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads cheques in a batch where {@code high_value = true}.
	 * Used by the HV verification queue to show only high-value instruments.
	 *
	 * <p>Falls back to a full {@code SELECT *} query if the projection query fails.
	 *
	 * @param batchId the batch to query
	 * @return list of HV cheques; empty list on error
	 */
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
			// Projection failed — fall back to full entity load (includes BLOBs, slower)
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
	 * Returns the count of high-value cheques with {@code ver_status = 'Pending'}.
	 * Drives the "HV Pending" stat card on the HV verification dashboard.
	 *
	 * @return HV pending count; 0 on error
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
	 * Returns the count of high-value cheques with a specific {@code ver_status}.
	 * Used to populate individual status buckets (Verified, Rejected, etc.)
	 * on the HV verification dashboard.
	 *
	 * @param verStatus the ver_status value to count (e.g. "VERIFIED", "REJECTED")
	 * @return count; 0 on error
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
	 * Returns the count of high-value pending cheques for a specific batch.
	 * Used to show per-batch HV queue depth in the verifier batch list.
	 *
	 * @param batchId the batch to count for
	 * @return HV pending count for that batch; 0 on error
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
	 * Returns the total amount of high-value cheques still in Pending status.
	 * Drives the "HV Pending Amount" stat card on the HV verification dashboard.
	 *
	 * @return sum as BigDecimal; BigDecimal.ZERO if no rows or on error
	 */
	@Override
	public BigDecimal sumHvPendingAmount() {
		try (Session session = HibernateUtil.getSession()) {
			// SUM returns null if no matching rows — toBigDecimal handles null via the null-guard below
			Object result = session.createNativeQuery(
					"SELECT SUM(amount) FROM cts_cheques WHERE high_value = true AND ver_status = 'Pending'", Object.class)
					.uniqueResult();
			return result != null ? toBigDecimal(result) : BigDecimal.ZERO;
		} catch (Exception ex) {
			LOG.severe("sumHvPendingAmount error: " + ex.getMessage());
			return BigDecimal.ZERO;
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads all verified cheques that have not yet had a CXF file generated.
	 *
	 * <h3>Filter logic</h3>
	 * {@code status = 'VERIFIED'} AND {@code ver_status} is either NULL or not
	 * equal to 'CXF_Generated'. This ensures already-exported cheques are excluded
	 * even if a CXF run is re-triggered.
	 *
	 * <h3>Ordering</h3>
	 * {@code batch_id ASC, id ASC} — groups cheques by batch for CXF file
	 * grouping logic in the CXF generation service.
	 *
	 * <h3>Extra column merge</h3>
	 * A second IN-list query fetches {@code transaction_code} (needed for the CXF
	 * file format) and merges it via a tcMap. Pattern is identical to the extra-
	 * column merge in {@link #loadChequesForBatch(String)}.
	 *
	 * <h3>Called by</h3>
	 * {@code CxfGenerationServiceImpl.generateCxfFiles()}.
	 *
	 * @return list of verified cheques ready for CXF export; empty list on error
	 */
	@Override
	public List<ChequeEntity> loadAcceptedInstrumentsForCxf() {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE status = 'VERIFIED' "
							// Exclude already-exported: ver_status IS NULL means never exported,
							// <> 'CXF_Generated' means not yet marked as exported
							+ "  AND (ver_status IS NULL OR ver_status <> 'CXF_Generated') "
							+ "ORDER BY batch_id ASC, id ASC",
					Object[].class)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (results.isEmpty())
				return results; // short-circuit — avoid the second query for empty result

			// Fetch transaction_code for CXF file format — needed by the generation service
			List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
			List<Object[]> tcRows = session
					.createNativeQuery("SELECT id, transaction_code FROM cts_cheques WHERE id IN :ids", Object[].class)
					.setParameter("ids", ids).getResultList();

			// Build id -> transaction_code map for O(1) merge
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

	/**
	 * Updates {@code status}, {@code ver_level}, and {@code ver_status} for a
	 * single cheque in one targeted UPDATE. Used after CXF generation to mark
	 * cheques as exported, and by the verifier routing logic to advance cheques
	 * through the pipeline.
	 *
	 * <h3>Called by</h3>
	 * {@code CxfGenerationServiceImpl} — marks cheque as {@code CXF_Generated}.
	 * {@code BatchServiceImpl.submitBatch()} — routes cheques to V1 / HV queues.
	 *
	 * @param chequeId  primary key of the cheque
	 * @param status    new {@code status} value
	 * @param verLevel  new {@code ver_level} value (e.g. "V1", "V2", "HV")
	 * @param verStatus new {@code ver_status} value
	 * @throws RuntimeException if the update fails
	 */
	@Override
	public void updateVerRouting(Long chequeId, String status, String verLevel, String verStatus) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status     = :status,"    // overall cheque lifecycle state
					+ "  ver_level  = :verLevel,"  // routing bucket: V1 / V2 / HV
					+ "  ver_status = :verStatus," // verification pipeline state
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

	// ══════════════════════════════════════════════════════════════════════
	// VERIFICATION I (V1) — ANUSHA
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads cheques across ALL batches matching a specific {@code ver_level}
	 * and {@code status} — used to build the Phase 1 verifier batch summary list.
	 *
	 * <h3>Why cross-batch</h3>
	 * The V1 verifier sees all pending batches at once, not just one batch.
	 * The caller ({@code VerificationOneServiceImpl.getVerifiableBatchSummaries()})
	 * groups the returned cheques by {@code batchId} to build per-batch summaries.
	 *
	 * <h3>Extra columns merged</h3>
	 * A second IN-list query fetches {@code ver_action, ver_by, ver_remarks}
	 * via a verMap — same two-query pattern as {@link #loadChequesForBatch(String)}.
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.getVerifiableBatchSummaries()} with
	 * args {@code ("V1", "V1_PENDING")} to get all queued V1 cheques.
	 *
	 * @param verLevel the verification routing bucket (e.g. "V1")
	 * @param status   the cheque status to filter on (e.g. "V1_PENDING")
	 * @return list of matching ChequeEntity across all batches; empty list on error
	 */
	@Override
	public List<ChequeEntity> loadAllPendingV1ChequesAcrossAllBatches(String verLevel, String status) {
		try (Session session = HibernateUtil.getSession()) {
			// Primary query — all V1_PENDING cheques sorted for consistent batch grouping
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

				// Second query: fetch verifier audit fields — ver_action / ver_by / ver_remarks
				// These are not in PROJECTION_COLS; needed for the Phase 1 batch summary display
				List<Object[]> verRows = session.createNativeQuery(
						"SELECT id, ver_action, ver_by, ver_remarks FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();

				// Build id -> ver-row map for O(1) merge
				Map<Long, Object[]> verMap = new HashMap<>();
				for (Object[] row : verRows)
					verMap.put(toLong(row[0]), row);

				// Merge verifier fields into each entity
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
	 * Loads ALL V1 cheques for a specific batch — regardless of status
	 * (V1_PENDING, VERIFIED, REJECTED) — to populate the verifier popup.
	 *
	 * <h3>Why a single 22-column query instead of two</h3>
	 * The verifier popup needs all fields including {@code transaction_code,
	 * amount_in_words, amount_words_mismatch, payee_account_no, base_no}
	 * to render the cheque detail form. A single 22-column SELECT avoids
	 * the second round-trip needed by the list-view two-query strategy.
	 *
	 * <h3>Column mapping</h3>
	 * r[0]-r[16]  -> {@link #mapProjectionRow(Object[])} (17-field constructor)
	 * r[17]       -> transaction_code
	 * r[18]       -> amount_in_words
	 * r[19]       -> amount_words_mismatch
	 * r[20]       -> payee_account_no
	 * r[21]       -> base_no
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.getAllV1ChequesForBatch()} when the
	 * verifier opens a batch in Phase 2 (cheque-level action).
	 *
	 * @param batchId the batch to load V1 cheques for
	 * @return list of V1 cheques with all fields populated; empty list on error
	 */
	@Override
	public List<ChequeEntity> loadAllV1ChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT id, batch_id, cheque_id, cheque_no, account_no, "
					+ "sort_code, amount, cheque_date, drawer_name, payee_name, "
					+ "iqa_status, ver_status, status, high_value, duplicate_flag, "
					+ "created_at, updated_at, "            // r[0]-r[16] — PROJECTION_COLS order
					+ "transaction_code, amount_in_words, amount_words_mismatch, "  // r[17]-r[19]
					+ "payee_account_no, base_no "          // r[20]-r[21]
					+ "FROM cts_cheques "
					+ "WHERE batch_id = :batchId AND ver_level = 'V1' " // only V1-routed cheques
					+ "ORDER BY id ASC",
					Object[].class)
					.setParameter("batchId", batchId)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows) {
				ChequeEntity c = mapProjectionRow(r);   // maps r[0]-r[16] via 17-field constructor
				// Manually set the extra columns beyond the 17-field constructor
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

	/**
	 * Counts cheques in a batch that still need a verifier action —
	 * i.e. those with {@code status IN ('V1_PENDING', 'V2_PENDING')}.
	 *
	 * <h3>Purpose</h3>
	 * Called after every verifier action (Accept / Reject / Refer) to determine
	 * whether the batch is fully processed. If count reaches 0, the batch
	 * advances to {@code VERIFIED} status.
	 *
	 * <h3>Special return value</h3>
	 * Returns {@code -1L} on DB error (not 0L) so the caller can distinguish
	 * "zero remaining" (safe to finalize) from "query failed" (do NOT finalize).
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.checkAndFinalizeBatch()} after every
	 * verifier action on a V1 cheque.
	 *
	 * @param batchId the batch to check
	 * @return count of pending verification cheques; -1 on DB error
	 */
	@Override
	public long countPendingVerificationForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques"
					+ " WHERE batch_id = :batchId"
					+ " AND status IN ('V1_PENDING', 'V2_PENDING')", // both V1 and V2 pending count
					Object.class)
					.setParameter("batchId", batchId)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingVerificationForBatch error: " + ex.getMessage());
			return -1L; // -1 signals "error" — caller must NOT finalize batch on unknown count
		}
	}

	/**
	 * Persists a verifier's Accept or Reject action on a single cheque.
	 * Updates {@code status, ver_status, ver_level, ver_action, ver_by, ver_remarks}
	 * and {@code updated_at} in one native UPDATE.
	 *
	 * <h3>Note on ver_status</h3>
	 * {@code ver_status} is set to the same value as {@code status} here.
	 * This keeps both columns in sync; the system uses {@code status} as the
	 * primary routing field and {@code ver_status} as the display/filter field.
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.validateAndAcceptCheque()} (Accept path)
	 * and {@code VerificationOneServiceImpl.rejectCheque()} (Reject path).
	 *
	 * @param chequeId   primary key of the cheque
	 * @param status     new status (e.g. "VERIFIED", "REJECTED")
	 * @param verLevel   routing level (e.g. "V1", "V2")
	 * @param verAction  action label (e.g. "ACCEPTED", "REJECTED")
	 * @param verBy      username of the verifier performing the action
	 * @param verRemarks justification (mandatory for REJECTED / REFERRED in UI)
	 * @throws RuntimeException if the update fails
	 */
	@Override
	public void applyVerifierAction(Long chequeId, String status, String verLevel,
			String verAction, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"     // cheque lifecycle status (VERIFIED / REJECTED)
					+ "  ver_status  = :status,"     // kept in sync with status for display filters
					+ "  ver_level   = :verLevel,"   // routing bucket that actioned this cheque
					+ "  ver_action  = :verAction,"  // audit: what the verifier did
					+ "  ver_by      = :verBy,"      // audit: who did it
					+ "  ver_remarks = :verRemarks," // audit: why (mandatory for reject)
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

	/**
	 * Escalates a cheque from V1 to V2 verification.
	 *
	 * <h3>What changes</h3>
	 * <ul>
	 *   <li>{@code ver_level}   V1 -> V2 (routing bucket change)</li>
	 *   <li>{@code is_referred} false -> true (permanent flag; V2 must NOT reset this)</li>
	 *   <li>{@code status} and {@code ver_status} -> "V2_PENDING"</li>
	 *   <li>{@code ver_action} -> "Refer"</li>
	 *   <li>{@code ver_by} / {@code ver_remarks} -> V1 verifier identity + reason</li>
	 * </ul>
	 *
	 * <h3>Why separate from applyVerifierAction</h3>
	 * {@code applyVerifierAction} never sets {@code is_referred} — that flag is
	 * exclusive to the Refer path and must be permanent (V2 reads it to know the
	 * instrument was flagged by V1). Combining them would require an extra boolean
	 * parameter and conditional SQL — a separate method is cleaner.
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.referCheque()} when the V1 verifier
	 * chooses "Refer to V2" in the verification popup.
	 *
	 * @param chequeId   primary key of the cheque to escalate
	 * @param verBy      username of the V1 verifier performing the refer
	 * @param verRemarks reason for referral (mandatory in UI)
	 * @throws RuntimeException if the update fails
	 */
	@Override
	public void referToVerificationTwo(Long chequeId, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"      // V2_PENDING
					+ "  ver_status  = :status,"      // kept in sync
					+ "  ver_level   = :verLevel,"    // V1 -> V2
					+ "  is_referred = true,"         // permanent escalation flag — V2 reads this
					+ "  ver_action  = :verAction,"   // "Refer"
					+ "  ver_by      = :verBy,"       // V1 verifier username
					+ "  ver_remarks = :verRemarks,"  // reason for referral
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

	/**
	 * Counts V1-actioned cheques (VERIFIED + REJECTED + V2_PENDING) for a
	 * single batch. Used to show a "processed / total" counter per batch row
	 * in the Phase 1 batch summary list.
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.getVerifiableBatchSummaries()} — one call
	 * per batch in the list. Superseded by {@link #countV1ProcessedForBatches(Set)}
	 * for bulk use (eliminates N+1).
	 *
	 * @param batchId the batch to count for
	 * @return count of V1-processed cheques; 0 on error
	 */
	@Override
	public long countV1ProcessedForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques"
					+ " WHERE batch_id = :batchId"
					+ "   AND ver_level = 'V1'"
					// Actioned = verifier made a decision: Accept -> VERIFIED, Reject -> REJECTED,
					// Refer -> V2_PENDING. V1_PENDING = not yet actioned.
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
	 * Returns distinct batch IDs that have at least one V1-actioned cheque
	 * (VERIFIED / REJECTED / V2_PENDING at ver_level = 'V1').
	 *
	 * <h3>Why this is needed</h3>
	 * {@link #loadAllPendingV1ChequesAcrossAllBatches} only returns
	 * {@code V1_PENDING} cheques — fully-verified batches (all cheques done)
	 * would be invisible without this query. This set is unioned with the
	 * pending-batch set to include history rows in the Phase 1 list.
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.getVerifiableBatchSummaries()}.
	 *
	 * @return set of batch IDs with at least one V1-processed cheque; empty set on error
	 */
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

	/**
	 * Returns V1-processed cheque counts for ALL given batch IDs in a single
	 * GROUP BY query — eliminates the N+1 loop of calling
	 * {@link #countV1ProcessedForBatch(String)} once per batch.
	 *
	 * <h3>Return value</h3>
	 * A map of {@code batchId -> processedCount}. Batches with zero processed
	 * cheques are absent from the map (not present with value 0L) — caller
	 * should use {@code result.getOrDefault(batchId, 0L)}.
	 *
	 * <h3>Called by</h3>
	 * {@code VerificationOneServiceImpl.getVerifiableBatchSummaries()} to
	 * populate the "Processed" column for every batch in the Phase 1 list
	 * at once.
	 *
	 * @param batchIds set of batch IDs to count for; empty set returns empty map
	 * @return map of batchId -> V1-processed count; empty map on error or empty input
	 */
	@Override
	public Map<String, Long> countV1ProcessedForBatches(Set<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty())
			return Collections.emptyMap(); // nothing to count — avoid DB round-trip
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT batch_id, COUNT(*) "
					+ "FROM cts_cheques "
					+ "WHERE batch_id  IN :ids "
					+ "  AND ver_level = 'V1' "
					+ "  AND status    IN ('VERIFIED', 'REJECTED', 'V2_PENDING') "
					+ "GROUP BY batch_id", // one row per batch — aggregate eliminates N+1
					Object[].class)
					.setParameter("ids", new ArrayList<>(batchIds)) // Set -> List for IN-list binding
					.getResultList();

			// Build batchId -> count map from the grouped result
			Map<String, Long> result = new HashMap<>();
			for (Object[] row : rows) {
				String batchId = (String) row[0];              // GROUP BY column
				Long   count   = ((Number) row[1]).longValue(); // COUNT(*) aggregate
				result.put(batchId, count);
			}
			return result;
		} catch (Exception ex) {
			LOG.severe("countV1ProcessedForBatches error: " + ex.getMessage());
			return Collections.emptyMap();
		}
	}
}