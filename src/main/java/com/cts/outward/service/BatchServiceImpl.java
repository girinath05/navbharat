/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : BatchServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Concrete implementation of {@link BatchService}.
 *
 *  Responsibilities:
 *    1. Generate sequential, collision-safe BATCH{NNNN} IDs by
 *       reading the current maximum sequence from the DB before
 *       each insert.
 *    2. Enforce business rules before status transitions:
 *         - No submission if cheques are still Pending
 *         - No submission if any cheque needs MICR Repair
 *    3. Route each cheque to the correct verification level on
 *       batch submission (V1 for amount ≤ ₹50,000; V2 for
 *       amount > ₹50,000).
 *    4. Finalize a batch automatically when the last cheque in
 *       it is actioned by a verifier.
 *    5. Delegate all persistence to BatchDAO and ChequeDAO —
 *       this class never touches Hibernate/SQL directly.
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS CLASS FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Composers]                 [This Class]             [DAOs]
 *  ──────────────────────────  ─────────────────────    ──────────────────────
 *  BatchChequeEntryComposer                             BatchDAO / BatchDAOImpl
 *  BatchDetailComposer    ───► BatchServiceImpl    ──►  ChequeDAO / ChequeDAOImpl
 *  MyBatchesComposer                                    HibernateUtil → Supabase
 *  VerificationOneComposer
 *  VerificationTwoComposer
 *
 * ──────────────────────────────────────────────────────────────
 *  BATCH ID GENERATION STRATEGY
 * ──────────────────────────────────────────────────────────────
 *  Format: "BATCH" + zero-padded 4-digit sequence → "BATCH0042"
 *
 *  On every createBatch() call:
 *    1. BatchDAO.loadMaxBatchSeq() → SELECT MAX(seq) from DB
 *    2. Result + 1 = next sequence number
 *    3. batchId = "BATCH" + String.format("%04d", nextSeq)
 *
 *  This approach is safe for single-node deployments (no concurrent
 *  inserts from multiple app servers). For multi-node scaling,
 *  replace with a Postgres sequence or UUID-based ID.
 *
 * ──────────────────────────────────────────────────────────────
 *  VERIFICATION ROUTING LOGIC (submitBatch)
 * ──────────────────────────────────────────────────────────────
 *  On submission, every cheque in the batch is individually
 *  routed to a verification level based on its amount:
 *
 *    Cheque amount ≤ ₹50,000  →  verLevel = "V1"
 *                                 status   = "V1_Pending"
 *
 *    Cheque amount > ₹50,000  →  verLevel = "V2"
 *                                 status   = "V2_Pending"
 *
 *  The ₹50,000 threshold is defined by HV_THRESHOLD (High Value).
 *  V2-routed cheques bypass the V1 verifier queue entirely.
 *
 * ──────────────────────────────────────────────────────────────
 *  BATCH FINALIZATION LOGIC (checkAndFinalizeBatch)
 * ──────────────────────────────────────────────────────────────
 *  Called after every individual cheque action (approve/reject)
 *  by V1 or V2 verifiers. Queries how many cheques in the batch
 *  are still in a "pending verification" state:
 *
 *    pendingCount > 0  →  do nothing (batch still in progress)
 *    pendingCount == 0 →  update batch status to "Verified"
 *
 *  This event-driven approach means the batch finalizes itself
 *  automatically when the last cheque is processed — no polling.
 *
 * ──────────────────────────────────────────────────────────────
 *  INSTANTIATION (manual DI — no Spring/CDI)
 * ──────────────────────────────────────────────────────────────
 *  new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl())
 *
 *  Both DAOs are injected via constructor — allows unit-testing
 *  with mock DAOs without changing any composer code.
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.cts.outward.dao.BatchDAO;
import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.exception.BatchSubmitException;

/**
 * Concrete implementation of {@link BatchService}.
 *
 * <p>
 * Enforces all batch business rules and delegates persistence to
 * {@link BatchDAO} and {@link ChequeDAO}. See class-level Javadoc for the ID
 * generation strategy, verification routing logic, and finalization behaviour.
 *
 * @author Umesh M.
 * @see BatchService
 * @see com.cts.outward.dao.BatchDAOImpl
 * @see com.cts.outward.dao.ChequeDAOImpl
 */
public class BatchServiceImpl implements BatchService {

	/** Logger for submission audit trail, routing decisions, and discard events. */
	private static final Logger LOG = Logger.getLogger(BatchServiceImpl.class.getName());

	/**
	 * High-Value cheque threshold: ₹50,000. Cheques above this amount are routed to
	 * V2 verification; all others to V1. Defined here as a constant so it can be
	 * changed in one place without touching the routing logic.
	 */
	private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("50000");

	// ══════════════════════════════════════════════════════════════════════
	// DEPENDENCIES (injected via constructor)
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * DAO for all {@code cts_batches} table operations. Injected at construction
	 * time; never re-created inside methods.
	 */
	private final BatchDAO batchDao;

	/**
	 * DAO for all {@code cts_cheques} table operations. Used here for: loading
	 * cheques to validate, routing updates on submit, counting pending cheques for
	 * batch finalization, and ready-batch checks.
	 */
	private final ChequeDAO chequeDao;

	// ══════════════════════════════════════════════════════════════════════
	// CONSTRUCTOR
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Constructs a BatchServiceImpl with the given DAO dependencies.
	 *
	 * <p>
	 * Standard usage:
	 * 
	 * <pre>
	 * BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
	 * </pre>
	 *
	 * @param batchDao  DAO for batch persistence operations
	 * @param chequeDao DAO for cheque persistence and query operations
	 */
	public BatchServiceImpl(BatchDAO batchDao, ChequeDAO chequeDao) {
		this.batchDao = batchDao;
		this.chequeDao = chequeDao;
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH LIFECYCLE — CREATE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Creates a new empty Draft batch, generates a sequential batchId, and persists
	 * it to the DB.
	 *
	 * <h3>batchId generation</h3> Reads the current maximum batch sequence number
	 * from the DB, increments it by 1, and formats it as "BATCH{NNNN}" (e.g.
	 * "BATCH0042"). The sequence is DB-driven (not an in-memory counter) so it
	 * survives server restarts and remains correct after discards.
	 *
	 * <h3>Initial field values</h3>
	 * <ul>
	 * <li>status = "Draft"</li>
	 * <li>totalCheques = expectedCheques (Maker's declared count; will be updated
	 * to actual count after ZIP upload in ZipImportServiceImpl)</li>
	 * <li>totalAmount = null (set after ZIP import)</li>
	 * <li>createdAt = set by DB default (NOW())</li>
	 * </ul>
	 *
	 * <h3>Called by</h3> {@code BatchChequeEntryComposer.onCreateBatch()} — Step 1
	 * of two-step scan workflow, after Maker fills in the Create Batch modal.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchChequeEntryComposer.onCreateBatch()
	 *   → BatchServiceImpl.createBatch(branchCode, expectedCheques, expectedAmount, createdBy)
	 *       → batchDao.loadMaxBatchSeq()     [SELECT MAX(seq) → e.g. 41]
	 *       → batchId = "BATCH0042"
	 *       → new BatchEntity(batchId, branchCode, expectedCheques, "Draft", ...)
	 *       → batchDao.saveBatch(batch)      [INSERT INTO cts_batches]
	 *       → returns persisted BatchEntity
	 * </pre>
	 *
	 * @param branchCode      branch code from session (e.g. "MUM01"); required,
	 *                        throws if blank
	 * @param expectedCheques Maker-declared cheque count from Step 1 modal
	 * @param expectedAmount  Maker-declared control amount from Step 1 modal
	 * @param createdBy       logged-in user name from session
	 * @return fully populated {@link BatchEntity} with generated batchId and
	 *         "Draft" status
	 * @throws IllegalArgumentException if branchCode is null or blank
	 */
	@Override
	public BatchEntity createBatch(String branchCode, int expectedCheques, BigDecimal expectedAmount,
			String createdBy) {
		if (branchCode == null || branchCode.trim().isEmpty()) {
			throw new IllegalArgumentException("createBatch: branchCode required");
		}

		// Generate next sequential batchId — DB-driven to survive restarts/discards
		String generatedBatchId = "BATCH" + String.format("%04d", batchDao.loadMaxBatchSeq());

		BatchEntity newBatch = new BatchEntity();
		newBatch.setBatchId(generatedBatchId);
		newBatch.setBranchCode(branchCode.toUpperCase());
		newBatch.setExpectedCheques(expectedCheques);
		newBatch.setExpectedAmount(expectedAmount);
		newBatch.setTotalCheques(expectedCheques); // reflects declared count until ZIP upload
		newBatch.setStatus(BatchStatus.DRAFT.db());
		newBatch.setCreatedBy(createdBy);

		batchDao.saveBatch(newBatch);
		return newBatch;
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH LIFECYCLE — DISCARD
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Permanently deletes a batch and all its child cheque records from the DB.
	 *
	 * <p>
	 * Silently no-ops if batchId is null or blank — safe to call speculatively when
	 * a pending batch may or may not exist (e.g. scan modal closed without a ZIP
	 * being uploaded).
	 *
	 * <h3>Called by</h3>
	 * <ul>
	 * <li>{@code BatchChequeEntryComposer.discardPendingBatch()} — scan modal
	 * closed before ZIP upload (empty Draft cleanup)</li>
	 * <li>{@code BatchChequeEntryComposer.onMismatchDiscard()} — Maker rejects the
	 * mismatched import</li>
	 * <li>{@code BatchChequeEntryComposer.onScanZipUpload()} — all-duplicates case,
	 * discards the empty Draft created in Step 1</li>
	 * </ul>
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchChequeEntryComposer.discardPendingBatch()
	 *   → BatchServiceImpl.discardBatch(batchId)
	 *       → batchDao.deleteBatchAndCheques(batchId)
	 *           [DELETE FROM cts_cheques WHERE batch_id = ?]
	 *           [DELETE FROM cts_batches WHERE batch_id = ?]
	 * </pre>
	 *
	 * @param batchId the batchId to permanently delete; null/blank → no-op with
	 *                warning
	 */
	@Override
	public void discardBatch(String batchId) {
		if (batchId == null || batchId.trim().isEmpty()) {
			LOG.warning("discardBatch: null/empty batchId — skipped");
			return;
		}
		batchDao.deleteBatchAndCheques(batchId);
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH LIFECYCLE — SUBMIT
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Validates and submits a batch to the verification queue.
	 *
	 * <p>
	 * This is the most complex method in this class. It performs three phases:
	 *
	 * <h3>Phase 1 — Pre-submit validation</h3>
	 * <ul>
	 * <li>Batch must have at least one cheque</li>
	 * <li>No cheques may still be in "Pending" status</li>
	 * <li>No cheques may be in "MICR_Repair" status</li>
	 * </ul>
	 * Any validation failure throws {@link BatchSubmitException} with a
	 * human-readable message displayed to the Maker in the UI.
	 *
	 * <h3>Phase 2 — Verification routing</h3> Each cheque is individually routed
	 * based on its amount:
	 * <ul>
	 * <li>Amount ≤ {@link #HIGH_VALUE_THRESHOLD} (₹50,000): routed to V1 (verLevel
	 * = "V1", status = "V1_Pending")</li>
	 * <li>Amount > ₹50,000: routed to V2 (verLevel = "V2", status =
	 * "V2_Pending")</li>
	 * </ul>
	 * Routing fields (status, verLevel, verStatus) are persisted via
	 * {@code ChequeDAO.updateVerRouting()} for each cheque.
	 *
	 * <h3>Phase 3 — Batch status transition</h3> After all cheques are routed, the
	 * batch status is updated: {@code "VerificationInProgressAtMaker"} →
	 * {@code "ReadyForVerification"}
	 *
	 * <h3>Called by</h3>
	 * <ul>
	 * <li>{@code MyBatchesComposer.submitBatch()} — "Save Batch" button in the
	 * batch list table</li>
	 * <li>{@link #submitBatchForVerification(String)} — delegates here</li>
	 * </ul>
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * MyBatchesComposer.submitBatch(batchId)
	 *   → BatchServiceImpl.submitBatch(batchId)
	 *       → chequeDao.loadChequesForBatch(batchId)     [load all cheques]
	 *       → validate: not empty, none Pending, none MICR_Repair
	 *       → for each cheque:
	 *           → determine verLevel (V1 or V2) by amount vs HV_THRESHOLD
	 *           → set status, verLevel, verStatus
	 *           → chequeDao.updateVerRouting(id, status, verLevel, verStatus)
	 *       → batchDao.updateBatchStatus(batchId, "ReadyForVerification")
	 * </pre>
	 *
	 * @param batchId the batchId to validate and submit
	 * @throws BatchSubmitException if the batch is empty, has pending cheques, or
	 *                              has cheques needing MICR repair
	 */
	@Override
	public void submitBatch(String batchId) throws BatchSubmitException {
		if (batchId == null || batchId.trim().isEmpty()) {
			throw new BatchSubmitException("Batch ID is required.");
		}

		// ── Phase 1: Load cheques and validate pre-conditions ────────────────
		List<ChequeEntity> batchCheques = chequeDao.loadChequesForBatch(batchId);

		if (batchCheques == null || batchCheques.isEmpty()) {
			throw new BatchSubmitException("Batch has no cheques.");
		}

		long pendingChequeCount = batchCheques.stream()
				.filter(cheque -> !ChequeStatus.READY.db().equalsIgnoreCase(cheque.getStatus())).count();

		long micrRepairChequeCount = batchCheques.stream()
				.filter(cheque -> "MICR_Repair".equalsIgnoreCase(cheque.getStatus())).count();

		if (pendingChequeCount > 0) {
			throw new BatchSubmitException(pendingChequeCount + " cheque(s) still Pending.");
		}
		if (micrRepairChequeCount > 0) {
			throw new BatchSubmitException(micrRepairChequeCount + " cheque(s) need MICR Repair.");
		}

		// ── Phase 2: Route each cheque to V1 or V2 based on amount ──────────
		for (ChequeEntity cheque : batchCheques) {
			if (cheque.getAmount() != null && cheque.getAmount().compareTo(HIGH_VALUE_THRESHOLD) > 0) {
				// High-value cheque: must pass through V2 (senior verifier)
				cheque.setVerLevel("V2");
				cheque.setStatus(ChequeStatus.V2_PENDING.db());
				cheque.setVerStatus(ChequeStatus.V2_PENDING.db());
			} else {
				// Standard cheque: routed to V1 (junior verifier)
				cheque.setVerLevel("V1");
				cheque.setStatus(ChequeStatus.V1_PENDING.db());
				cheque.setVerStatus(ChequeStatus.V1_PENDING.db());
			}

			// Clear any previous verification decision fields from prior submissions
			cheque.setVerAction(null);
			cheque.setVerBy(null);
			cheque.setVerRemarks(null);
			cheque.setUpdatedAt(LocalDateTime.now());

			// Persist routing decision — single UPDATE per cheque
			chequeDao.updateVerRouting(cheque.getId(), cheque.getStatus(), cheque.getVerLevel(), cheque.getVerStatus());

			LOG.info("Routed cheque " + cheque.getId() + " amt=" + cheque.getAmount() + " → " + cheque.getVerLevel());
		}

		// ── Phase 3: Advance batch status to ReadyForVerification ───────────
		batchDao.updateBatchStatus(batchId, BatchStatus.READY_FOR_VERIFICATION.db());
		LOG.info("Batch " + batchId + " submitted → " + BatchStatus.READY_FOR_VERIFICATION.db());
	}

	/**
	 * Alternative submit entry point — delegates entirely to
	 * {@link #submitBatch(String)}.
	 *
	 * <p>
	 * Exists as a named alias so {@code BatchDetailComposer} can call a method with
	 * domain-intent-matching name without duplicating submit logic.
	 *
	 * <h3>Called by</h3> {@code BatchDetailComposer.onSubmitBatch()} — "Submit
	 * Batch" button on the batch detail screen.
	 *
	 * @param batchId the batchId to submit
	 * @throws BatchSubmitException see {@link #submitBatch(String)}
	 */
	@Override
	public void submitBatchForVerification(String batchId) throws BatchSubmitException {
		submitBatch(batchId);
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH LIFECYCLE — AUTO-FINALIZATION
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Checks whether all cheques in the batch have been actioned by the current
	 * verifier, and if so, advances the batch status to "Verified".
	 *
	 * <p>
	 * This implements event-driven batch finalization: rather than polling or using
	 * a scheduled job, every individual cheque action (approve/reject) in a
	 * verification composer triggers this method. When the count of still-pending
	 * cheques reaches zero, the batch finalizes automatically.
	 *
	 * <h3>DB query behaviour</h3>
	 * {@code ChequeDAO.countPendingVerificationForBatch()} counts cheques whose
	 * verStatus is still in an active pending state (V1_Pending or V2_Pending).
	 * Returns -1 on DB error (treated as "do not finalize" to avoid incorrect
	 * status advances).
	 *
	 * <h3>Called by</h3>
	 * <ul>
	 * <li>{@code VerificationOneComposer.onApproveCheque()} — after V1
	 * approves</li>
	 * <li>{@code VerificationOneComposer.onRejectCheque()} — after V1 rejects</li>
	 * <li>{@code VerificationTwoComposer.onApproveCheque()} — after V2
	 * approves</li>
	 * <li>{@code VerificationTwoComposer.onRejectCheque()} — after V2 rejects</li>
	 * </ul>
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * VerificationOneComposer.onApproveCheque()
	 *   → BatchServiceImpl.checkAndFinalizeBatch(batchId)
	 *       → chequeDao.countPendingVerificationForBatch(batchId)
	 *           [SELECT COUNT(*) WHERE batch_id=? AND ver_status IN ('V1_Pending','V2_Pending')]
	 *       → if count == 0:
	 *           → batchDao.updateBatchStatus(batchId, "Verified")
	 * </pre>
	 *
	 * @param batchId the batchId to check; null → no-op with warning
	 */
	@Override
	public void checkAndFinalizeBatch(String batchId) {
		if (batchId == null) {
			LOG.warning("checkAndFinalizeBatch: null batchId — skipped");
			return;
		}

		long pendingVerificationCount = chequeDao.countPendingVerificationForBatch(batchId);

		if (pendingVerificationCount < 0) {
			// Negative return signals a DB error in the DAO — do not advance status
			LOG.severe("checkAndFinalizeBatch: DB error for batch " + batchId + ", skip finalize.");
			return;
		}

		if (pendingVerificationCount == 0) {
			// All cheques have been actioned — advance batch to Verified
			batchDao.updateBatchStatus(batchId, BatchStatus.VERIFIED.db());
			LOG.info("Batch " + batchId + " → " + BatchStatus.VERIFIED.db());
		}
		// pendingCount > 0: more cheques still awaiting action — nothing to do yet
	}

	// ══════════════════════════════════════════════════════════════════════
	// QUERY OPERATIONS — SINGLE BATCH
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Fetches a single batch by its primary key batchId.
	 *
	 * <h3>Called by</h3> {@code BatchDetailComposer.loadBatchFromSession()} — loads
	 * the batch selected from the My Batches or Scan Module list.
	 *
	 * @param batchId the batchId primary key; null/blank → returns null
	 * @return matching {@link BatchEntity}, or null if not found
	 */
	@Override
	public BatchEntity getBatchById(String batchId) {
		if (batchId == null || batchId.trim().isEmpty())
			return null;
		return batchDao.findBatchById(batchId);
	}

	/**
	 * Returns all batches from the DB with no filtering.
	 *
	 * <p>
	 * <b>Legacy — loads full table into memory.</b> Only used by
	 * {@code BatchChequeEntryComposer} which manages its own in-memory list for
	 * client-side filtering. For paginated UI lists, use
	 * {@link #getBatchesPage(String, String, String, String, int, int)}.
	 *
	 * <h3>Called by</h3> {@code BatchChequeEntryComposer.loadBatchesFromService()}
	 *
	 * @return all {@link BatchEntity} rows ordered by created_at DESC
	 */
	@Override
	public List<BatchEntity> getAllBatches() {
		return batchDao.loadAllBatches();
	}

	// ══════════════════════════════════════════════════════════════════════
	// QUERY OPERATIONS — PAGINATED
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Fetches one page of batches matching the given filters, with all filtering
	 * and pagination pushed to Postgres.
	 *
	 * <p>
	 * Wraps {@code BatchDAO.loadBatchesPage()} in a try/catch and returns an empty
	 * list on DB error (callers render an empty table rather than crashing). All
	 * filter parameters are nullable — null means "no filter".
	 *
	 * <h3>Called by</h3> {@code MyBatchesComposer.loadPage()} — step 2 of the
	 * 3-query render cycle.
	 *
	 * @param searchQuery  ILIKE %term% on batch_id / branch_code; null = any
	 * @param statusFilter exact match on DB status column; null/blank = any
	 * @param fromDate     lower bound on created_at "YYYY-MM-DD"; null = none
	 * @param toDate       upper bound on created_at "YYYY-MM-DD"; null = none
	 * @param pageSize     number of rows per page
	 * @param pageNumber   1-based page index
	 * @return page slice of matching {@link BatchEntity} rows; empty list on error
	 */
	@Override
	public List<BatchEntity> getBatchesPage(String searchQuery, String statusFilter, String fromDate, String toDate,
			int pageSize, int pageNumber) {
		try {
			return batchDao.loadBatchesPage(searchQuery, statusFilter, fromDate, toDate, pageSize, pageNumber);
		} catch (Exception queryException) {
			LOG.severe("getBatchesPage error: " + queryException.getMessage());
			return Collections.emptyList();
		}
	}

	/**
	 * Returns the total row count matching the given filters.
	 *
	 * <p>
	 * Must be called with the same filter parameters as
	 * {@link #getBatchesPage(String, String, String, String, int, int)} to produce
	 * accurate "Page X of Y" pagination labels.
	 *
	 * <h3>Called by</h3> {@code MyBatchesComposer.loadPage()} — step 1 of the
	 * 3-query render cycle.
	 *
	 * @param searchQuery  ILIKE %term% on batch_id / branch_code; null = any
	 * @param statusFilter exact match on DB status column; null/blank = any
	 * @param fromDate     lower bound on created_at "YYYY-MM-DD"; null = none
	 * @param toDate       upper bound on created_at "YYYY-MM-DD"; null = none
	 * @return total count of matching rows; 0 on DB error
	 */
	@Override
	public long countBatches(String searchQuery, String statusFilter, String fromDate, String toDate) {
		try {
			return batchDao.countBatches(searchQuery, statusFilter, fromDate, toDate);
		} catch (Exception queryException) {
			LOG.severe("countBatches error: " + queryException.getMessage());
			return 0L;
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// ELIGIBILITY CHECKS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns true if every cheque in the batch has status = "Ready" (i.e., the
	 * Maker has completed all required edits and the batch qualifies for submission
	 * to the verification queue).
	 *
	 * <p>
	 * <b>Note:</b> This method loads all cheques for the batch into memory and
	 * checks them in Java. For checking multiple batches at once (table rendering),
	 * use {@link #getReadyBatchIds(List)} instead.
	 *
	 * <h3>Called by</h3> {@code BatchDetailComposer.refreshSubmitButtonState()} —
	 * enables or disables the "Submit Batch" button after each cheque edit is
	 * saved.
	 *
	 * @param batchId the batchId to check; null → returns false
	 * @return true if all cheques are Ready; false if any are Pending or empty
	 */
	@Override
	public boolean areAllChequesReady(String batchId) {
		if (batchId == null)
			return false;
		List<ChequeEntity> batchCheques = chequeDao.loadChequesForBatch(batchId);
		if (batchCheques == null || batchCheques.isEmpty())
			return false;
		return batchCheques.stream().allMatch(cheque -> ChequeStatus.READY.db().equalsIgnoreCase(cheque.getStatus()));
	}

	/**
	 * Returns the subset of the given batchIds where all cheques are Ready.
	 *
	 * <p>
	 * Designed for table rendering: replaces N individual
	 * {@link #areAllChequesReady(String)} calls with one batched DB query using an
	 * IN clause. Pass the IDs of all batches visible on the current page (≤ 5);
	 * receive back only those eligible for the "Save Batch" button.
	 *
	 * <h3>Called by</h3> {@code MyBatchesComposer.renderPage()} — step 3 of the
	 * 3-query render cycle, called once after fetching the page slice.
	 *
	 * @param batchIds list of batchIds to check (typically ≤ PAGE_SIZE = 5)
	 * @return set of batchIds from input where all cheques are Ready; empty set if
	 *         input is null/empty or on DB error
	 */
	@Override
	public Set<String> getReadyBatchIds(List<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty())
			return Collections.emptySet();
		return chequeDao.loadReadyBatchIds(batchIds);
	}

	// ══════════════════════════════════════════════════════════════════════
	// UTILITY OPERATIONS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns the next available batch sequence number for batchId generation.
	 *
	 * <p>
	 * Delegates to {@code BatchDAO.loadMaxBatchSeq()} which reads {@code MAX(seq)}
	 * from the DB, so the result is always accurate after discards, server
	 * restarts, or concurrent inserts by the same user.
	 *
	 * <h3>Called by</h3> {@link #createBatch(String, int, BigDecimal, String)} —
	 * called once per batch creation to generate the batchId.
	 *
	 * @return next integer sequence number (current max + 1, or 1 if table empty)
	 */
	@Override
	public int nextBatchSeq() {
		return batchDao.loadMaxBatchSeq();
	}

	/**
	 * Directly updates the status column of a batch row.
	 *
	 * <p>
	 * Low-level setter — validates that neither argument is null before delegating
	 * to the DAO. Higher-level lifecycle methods ({@link #submitBatch},
	 * {@link #discardBatch}, {@link #checkAndFinalizeBatch}) perform business-rule
	 * checks before calling this.
	 *
	 * <h3>Called by</h3> {@code BatchDetailComposer} and verification composers
	 * when direct status overrides are needed outside the normal lifecycle flow.
	 *
	 * @param batchId   the batchId whose status to update; null → no-op with
	 *                  warning
	 * @param newStatus the new DB status string (use {@link BatchStatus} enum
	 *                  values)
	 */
	@Override
	public void updateBatchStatus(String batchId, String newStatus) {
		if (batchId == null || newStatus == null) {
			LOG.warning("updateBatchStatus: null arg — skipped");
			return;
		}
		batchDao.updateBatchStatus(batchId, newStatus);
		LOG.info("Batch " + batchId + " status → " + newStatus);
	}
}