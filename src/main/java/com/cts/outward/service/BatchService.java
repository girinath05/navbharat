/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : BatchService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Date        : 24-06-2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Service interface defining all business operations on CTS
 *  batches. Acts as the contract between the ZK composer layer
 *  and the DAO/persistence layer — composers never call DAOs
 *  directly.
 *
 *  Single implementation: BatchServiceImpl.java
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS INTERFACE FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Composer Layer]           [Service Layer]         [DAO Layer]
 *  ─────────────────────      ─────────────────       ──────────────────────
 *  BatchChequeEntryComposer   ← BatchService (this)   BatchDAOImpl
 *  BatchDetailComposer          BatchServiceImpl  →   ChequeDAOImpl
 *  MyBatchesComposer                                  HibernateUtil
 *  VerificationOneComposer
 *  VerificationTwoComposer
 *
 * ──────────────────────────────────────────────────────────────
 *  BATCH LIFECYCLE — STATUS TRANSITION MAP
 * ──────────────────────────────────────────────────────────────
 *
 *  createBatch()
 *    → DB status: "Draft"
 *         ↓  (ZIP upload via ZipImportServiceImpl)
 *    → DB status: "VerificationInProgressAtMaker"
 *         ↓  submitBatch() / submitBatchForVerification()
 *    → DB status: "ReadyForVerification"
 *         ↓  V1 Verifier actions all cheques
 *    → DB status: "VerificationInProgress"  [via checkAndFinalizeBatch]
 *         ↓  V2 Verifier actions all cheques
 *    → DB status: "Verified"
 *         ↓  CXF file generated
 *    → DB status: "CxfGenerated"
 *         ↓  Dispatched to clearing house
 *    → DB status: "Dispatched"
 *
 *  discardBatch() — deletes batch + all child cheques at any status
 *
 * ──────────────────────────────────────────────────────────────
 *  METHOD GROUPINGS
 * ──────────────────────────────────────────────────────────────
 *  Lifecycle  : createBatch, discardBatch, submitBatch,
 *               submitBatchForVerification, checkAndFinalizeBatch
 *  Query      : getAllBatches, getBatchById, getBatchesPage,
 *               countBatches
 *  Eligibility: areAllChequesReady, getReadyBatchIds
 *  Utility    : nextBatchSeq, updateBatchStatus
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.exception.BatchSubmitException;

/**
 * Service contract for all CTS batch business operations.
 *
 * <p>
 * Composers depend on this interface — never on {@code BatchServiceImpl}
 * directly — allowing the implementation to be swapped or mocked in tests
 * without touching any UI code.
 *
 * <p>
 * Instantiation pattern used throughout this project (no DI framework):
 * 
 * <pre>
 * BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
 * </pre>
 *
 * @author Umesh M.
 * @see BatchServiceImpl
 * @see com.cts.outward.dao.BatchDAO
 */
public interface BatchService {

	// ══════════════════════════════════════════════════════════════════════
	// BATCH LIFECYCLE OPERATIONS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Creates a new empty Draft batch and persists it to the DB.
	 *
	 * <p>
	 * Called at the end of the Create Batch modal flow (Step 1 of the two-step scan
	 * workflow). The batch is saved with status {@code "Draft"} and no cheques. It
	 * becomes the target for the ZIP upload in Step 2.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchChequeEntryComposer.onCreateBatch()
	 *   → BatchService.createBatch(branchCode, expectedCheques, expectedAmount, createdBy)
	 *       → BatchServiceImpl.createBatch()
	 *           → BatchDAOImpl.loadMaxBatchSeq()   [SELECT MAX(seq) to generate next ID]
	 *           → BatchDAOImpl.saveBatch()          [INSERT, status = "Draft"]
	 *       → returns BatchEntity with generated batchId (e.g. "BATCH0042")
	 * </pre>
	 *
	 * @param branchCode      branch code from session (e.g. "MUM01")
	 * @param expectedCheques cheque count declared by Maker in Step 1 modal
	 * @param expectedAmount  control amount declared by Maker in Step 1 modal
	 * @param createdBy       logged-in user name from session
	 * @return persisted {@link BatchEntity} with generated batchId and "Draft"
	 *         status
	 */
	BatchEntity createBatch(String branchCode, int expectedCheques, BigDecimal expectedAmount, String createdBy);

	/**
	 * Permanently deletes a batch and all its child cheque records from the DB.
	 *
	 * <p>
	 * Called in two scenarios:
	 * <ol>
	 * <li>Scan modal closed without uploading a ZIP (empty Draft batch
	 * cleanup)</li>
	 * <li>Maker clicks "Discard" on the mismatch dialog</li>
	 * </ol>
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchChequeEntryComposer.discardPendingBatch()  OR  onMismatchDiscard()
	 *   → BatchService.discardBatch(batchId)
	 *       → BatchServiceImpl.discardBatch()
	 *           → BatchDAOImpl.deleteBatchAndCheques(batchId)
	 *               [DELETE FROM cts_cheques WHERE batch_id = ?]
	 *               [DELETE FROM cts_batches WHERE batch_id = ?]
	 * </pre>
	 *
	 * @param batchId the batchId to permanently delete (e.g. "BATCH0042")
	 */
	void discardBatch(String batchId);

	/**
	 * Submits a batch from Maker review to the V1 verification queue.
	 *
	 * <p>
	 * Transitions batch status from {@code "VerificationInProgressAtMaker"} to
	 * {@code "ReadyForVerification"}. Validates that the batch has at least one
	 * cheque before allowing the transition.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * MyBatchesComposer.submitBatch(batchId)
	 *   → BatchService.submitBatch(batchId)
	 *       → BatchServiceImpl.submitBatch()
	 *           → validates cheque count > 0
	 *           → BatchDAOImpl.updateStatus(batchId, "ReadyForVerification")
	 * </pre>
	 *
	 * @param batchId the batchId to submit
	 * @throws BatchSubmitException if the batch has no cheques or fails validation
	 */
	void submitBatch(String batchId) throws BatchSubmitException;

	/**
	 * Alternative submit entry point — same behaviour as
	 * {@link #submitBatch(String)}.
	 *
	 * <p>
	 * Called from {@code BatchDetailComposer} after the Maker completes all cheque
	 * edits from within the detail screen (as opposed to the batch list screen).
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchDetailComposer.onSubmitBatch()
	 *   → BatchService.submitBatchForVerification(batchId)
	 *       → BatchServiceImpl.submitBatchForVerification()
	 *           → same validation + status update as submitBatch()
	 * </pre>
	 *
	 * @param batchId the batchId to submit for verification
	 * @throws BatchSubmitException if validation fails
	 */
	void submitBatchForVerification(String batchId) throws BatchSubmitException;

	/**
	 * Checks whether all cheques in the batch have been actioned by the current
	 * verifier, and if so, advances the batch to the next workflow status.
	 *
	 * <p>
	 * Called after every individual cheque approval/rejection by V1 or V2
	 * verifiers. When the last cheque is actioned, the batch status advances:
	 * <ul>
	 * <li>V1 completes all → "VerificationInProgress" → "Verified"</li>
	 * <li>V2 completes all → "Verified" → "CxfGenerated"</li>
	 * </ul>
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * VerificationOneComposer.onApproveCheque()  OR  onRejectCheque()
	 *   → BatchService.checkAndFinalizeBatch(batchId)
	 *       → BatchServiceImpl.checkAndFinalizeBatch()
	 *           → ChequeDAOImpl.countPendingInBatch(batchId)
	 *           → if 0 pending → BatchDAOImpl.updateStatus(batchId, nextStatus)
	 * </pre>
	 *
	 * @param batchId the batch to check for completion
	 */
	void checkAndFinalizeBatch(String batchId);

	// ══════════════════════════════════════════════════════════════════════
	// QUERY OPERATIONS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns all batches from the DB with no filtering or pagination.
	 *
	 * <p>
	 * <b>Legacy method</b> — loads the full batch table into memory. Retained for
	 * use in {@code BatchChequeEntryComposer} which maintains its own in-memory
	 * list for client-side filtering. For all new UI code, prefer
	 * {@link #getBatchesPage(String, String, String, String, int, int)} which
	 * pushes filtering to the DB.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchChequeEntryComposer.loadBatchesFromService()
	 *   → BatchService.getAllBatches()
	 *       → BatchServiceImpl.getAllBatches()
	 *           → BatchDAOImpl.findAll()   [SELECT * FROM cts_batches ORDER BY created_at DESC]
	 * </pre>
	 *
	 * @return all BatchEntity rows ordered by created_at DESC; empty list if none
	 */
	List<BatchEntity> getAllBatches();

	/**
	 * Fetches a single batch by its primary key batchId.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchDetailComposer.loadBatchFromSession()
	 *   → BatchService.getBatchById(batchId)
	 *       → BatchServiceImpl.getBatchById()
	 *           → BatchDAOImpl.findById(batchId)
	 *               [SELECT * FROM cts_batches WHERE batch_id = ?]
	 * </pre>
	 *
	 * @param batchId the batchId primary key (e.g. "BATCH0042")
	 * @return the matching {@link BatchEntity}, or null if not found
	 */
	BatchEntity getBatchById(String batchId);

	/**
	 * Returns one page of batches matching the given filters, with all filtering
	 * and pagination pushed to Postgres via LIMIT/OFFSET.
	 *
	 * <p>
	 * Pair this with {@link #countBatches(String, String, String, String)} (same
	 * filter params) to get the total row count for pagination controls. Together
	 * they replace the legacy {@link #getAllBatches()} + in-memory filtering
	 * pattern.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * MyBatchesComposer.loadPage()
	 *   → BatchService.getBatchesPage(search, status, from, to, size, page)
	 *       → BatchServiceImpl.getBatchesPage()
	 *           → BatchDAOImpl.findPage(...)
	 *               [SELECT ... WHERE ... ORDER BY created_at DESC LIMIT ? OFFSET ?]
	 * </pre>
	 *
	 * @param searchQuery  substring match on batch_id / branch_code; null = no
	 *                     filter
	 * @param statusFilter exact DB status value; null or blank = no filter
	 * @param fromDate     lower bound on created_at in "YYYY-MM-DD" format; null =
	 *                     none
	 * @param toDate       upper bound on created_at in "YYYY-MM-DD" format; null =
	 *                     none
	 * @param pageSize     number of rows per page (typically 5)
	 * @param pageNumber   1-based page index
	 * @return page slice of matching {@link BatchEntity} rows; empty list if none
	 */
	List<BatchEntity> getBatchesPage(String searchQuery, String statusFilter, String fromDate, String toDate,
			int pageSize, int pageNumber);

	/**
	 * Returns the total number of batches matching the given filters.
	 *
	 * <p>
	 * Must be called with the same filter parameters as
	 * {@link #getBatchesPage(String, String, String, String, int, int)} to keep the
	 * pagination "Page X of Y" label accurate.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * MyBatchesComposer.loadPage()
	 *   → BatchService.countBatches(search, status, from, to)
	 *       → BatchServiceImpl.countBatches()
	 *           → BatchDAOImpl.countFiltered(...)
	 *               [SELECT COUNT(*) FROM cts_batches WHERE ...]
	 * </pre>
	 *
	 * @param searchQuery  substring match on batch_id / branch_code; null = no
	 *                     filter
	 * @param statusFilter exact DB status value; null or blank = no filter
	 * @param fromDate     lower bound on created_at in "YYYY-MM-DD" format; null =
	 *                     none
	 * @param toDate       upper bound on created_at in "YYYY-MM-DD" format; null =
	 *                     none
	 * @return total count of matching rows
	 */
	long countBatches(String searchQuery, String statusFilter, String fromDate, String toDate);

	// ══════════════════════════════════════════════════════════════════════
	// ELIGIBILITY CHECKS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns true if every cheque in the batch has ver_status = 'Verified' (i.e.
	 * the batch is ready to be submitted to the verification queue).
	 *
	 * <p>
	 * <b>Note:</b> For rendering multiple rows in a table, prefer
	 * {@link #getReadyBatchIds(List)} which batches the check into a single
	 * IN-clause query instead of one query per row.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchDetailComposer.refreshSubmitButtonState()
	 *   → BatchService.areAllChequesReady(batchId)
	 *       → BatchServiceImpl.areAllChequesReady()
	 *           → ChequeDAOImpl.countUnverifiedInBatch(batchId)
	 *               [SELECT COUNT(*) FROM cts_cheques WHERE batch_id = ? AND ver_status != 'Verified']
	 *       → returns count == 0
	 * </pre>
	 *
	 * @param batchId the batchId to check
	 * @return true if all cheques are verified; false if any are still pending
	 */
	boolean areAllChequesReady(String batchId);

	/**
	 * Returns the subset of the given batchIds where all cheques are verified and
	 * the batch is eligible for submission.
	 *
	 * <p>
	 * Designed for table rendering: replaces N calls to
	 * {@link #areAllChequesReady(String)} with a single batched DB query. Pass in
	 * all batchIds visible on the current page; receive back only those that
	 * qualify for the "Save Batch" action button.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * MyBatchesComposer.renderPage(pageSlice)
	 *   → BatchService.getReadyBatchIds(onPageBatchIds)
	 *       → BatchServiceImpl.getReadyBatchIds()
	 *           → BatchDAOImpl.findReadyBatchIds(batchIds)
	 *               [SELECT DISTINCT batch_id FROM cts_cheques
	 *                WHERE batch_id IN (?) GROUP BY batch_id
	 *                HAVING COUNT(*) = COUNT(CASE WHEN ver_status='Verified' THEN 1 END)]
	 * </pre>
	 *
	 * @param batchIds list of batchIds to check (typically the current page, ≤ 5
	 *                 IDs)
	 * @return set of batchIds from the input where all cheques are verified
	 */
	Set<String> getReadyBatchIds(List<String> batchIds);

	// ══════════════════════════════════════════════════════════════════════
	// UTILITY OPERATIONS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns the next available batch sequence number for batchId generation.
	 *
	 * <p>
	 * Batch IDs follow the pattern "BATCH{N}" where N is a zero-padded integer
	 * (e.g. "BATCH0042"). This method reads the current maximum sequence from the
	 * DB so the next ID is always unique even across concurrent sessions.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchServiceImpl.createBatch()
	 *   → BatchService.nextBatchSeq()
	 *       → BatchDAOImpl.loadMaxBatchSeq()
	 *           [SELECT MAX(CAST(SUBSTRING(batch_id FROM 6) AS INT)) FROM cts_batches]
	 *       → returns maxSeq + 1
	 * </pre>
	 *
	 * @return next integer sequence number (max existing + 1, or 1 if table empty)
	 */
	int nextBatchSeq();

	/**
	 * Directly updates the status column of a batch row in the DB.
	 *
	 * <p>
	 * Low-level status setter used by {@link #checkAndFinalizeBatch(String)} and
	 * other internal service methods. Callers should prefer the higher-level
	 * lifecycle methods ({@link #submitBatch}, {@link #discardBatch}) which include
	 * business-rule validation before calling this.
	 *
	 * <h3>Call chain</h3>
	 * 
	 * <pre>
	 * BatchServiceImpl.checkAndFinalizeBatch()  OR  submitBatch()
	 *   → BatchService.updateBatchStatus(batchId, newStatus)
	 *       → BatchServiceImpl.updateBatchStatus()
	 *           → BatchDAOImpl.updateStatus(batchId, newStatus)
	 *               [UPDATE cts_batches SET status = ? WHERE batch_id = ?]
	 * </pre>
	 *
	 * @param batchId   the batchId whose status to update
	 * @param newStatus the new DB status string (use
	 *                  {@link com.cts.outward.enums.BatchStatus} constants)
	 */
	void updateBatchStatus(String batchId, String newStatus);
}