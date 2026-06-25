/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeDAO.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : DAO interface for cheque-level persistence.
 *                Covers dashboard aggregates, cheque CRUD,
 *                MICR repair queries, high-value (HV) lookups,
 *                verification queue operations (V1/V2), and
 *                CXF generation selects. Implemented by
 *                ChequeDAOImpl using Hibernate sessions.
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cts.outward.entity.ChequeEntity;

public interface ChequeDAO {

	// ══════════════════════════════════════════════════════════
	// DASHBOARD
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads all data needed for the dashboard in one call:
	 * full cts_batches list + COUNT of cheques with ver_status = 'Pending'.
	 *
	 * Called by: DashboardComposer on page load
	 */
	DashboardData loadDashboardData();

	// ══════════════════════════════════════════════════════════
	// CHEQUE OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Inserts (persist) or updates (merge) a single ChequeEntity.
	 * Explicit transaction; rethrows on failure.
	 *
	 * Called by: BatchDetailComposer after individual cheque edit
	 */
	void saveCheque(ChequeEntity cheque);

	/**
	 * Bulk-inserts a list of ChequeEntities in two phases:
	 *   Phase 1 (sync)  — persists metadata (no BLOBs) in batches of 50; flush+clear each batch.
	 *   Phase 2 (async) — writes front_image / rear_image via JDBC batch UPDATE on IMAGE_EXEC thread pool.
	 * BLOBs are stripped before the sync transaction and restored afterward.
	 * If no images present, async phase is skipped entirely.
	 * Rethrows on sync failure (restores BLOBs before throw).
	 *
	 * Called by: ZipImportServiceImpl after parsing ZIP scan bundle
	 */
	void saveCheques(List<ChequeEntity> cheques);

	/**
	 * Returns the subset of chequeNos that already exist in cts_cheques.
	 * Used for duplicate detection during ZIP import — prevents re-inserting
	 * cheques already present from a prior upload.
	 * Returns empty set on null/empty input or DB error.
	 *
	 * Called by: ZipImportServiceImpl.filterDuplicates()
	 */
	Set<String> findExistingChequeNos(List<String> chequeNos);

	/**
	 * Returns subset of batchIds where ALL cheques have status = 'Ready'.
	 * Single GROUP BY + HAVING query — avoids N individual areAllChequesReady() calls.
	 * Used for batch-level "Save Batch" button eligibility in table view.
	 * Returns empty set on null/empty input or DB error.
	 *
	 * Called by: BatchServiceImpl.getReadyBatchIds() → MyBatchesComposer.renderPage()
	 */
	Set<String> loadReadyBatchIds(List<String> batchIds);

	/**
	 * Loads cheques for a batch using PROJECTION_COLS (no BLOBs).
	 * Two-query approach: projection SELECT + secondary SELECT for extra fields
	 * (transaction_code, amount_in_words, amount_words_mismatch, payee_account_no, base_no).
	 * Falls back to loadChequesForBatchFull() on projection failure.
	 *
	 * Called by: BatchServiceImpl.areAllChequesReady(), submitBatch() validation,
	 *            BatchDetailComposer cheque list render
	 */
	List<ChequeEntity> loadChequesForBatch(String batchId);

	/**
	 * Full SELECT * load for a batch — includes BLOBs. Slower than loadChequesForBatch().
	 * Used as fallback when projection query fails.
	 * Returns empty list on DB error.
	 *
	 * Called by: loadChequesForBatch() fallback path
	 */
	List<ChequeEntity> loadChequesForBatchFull(String batchId);

	/**
	 * session.get() full entity load by PK — includes front_image / rear_image BLOBs.
	 * The only method that returns image bytes; all other reads use PROJECTION_COLS.
	 * Returns null on not-found or DB error.
	 *
	 * Called by: ImageViewerComposer / batch-detail image display
	 */
	ChequeEntity loadChequeWithImages(Long chequeId);

	/**
	 * Updates status + ver_status + updated_at for a single cheque.
	 * Lighter than updateChequeFields() — only touches status columns.
	 * Silent on failure (logs but does not rethrow).
	 *
	 * Called by: MicrRepairComposer, BatchDetailComposer on status-only changes
	 */
	void updateChequeStatus(Long chequeId, String status, String verStatus);

	/**
	 * Deletes a single cheque row by PK.
	 * Explicit transaction; rethrows on failure.
	 *
	 * Called by: BatchDetailComposer "Delete Cheque" action
	 */
	void deleteCheque(Long chequeId);

	/**
	 * COUNT of cheques where ver_status = 'Pending' across all batches.
	 * Used for dashboard pending-cheques KPI tile.
	 * Returns 0 on DB error.
	 *
	 * Called by: loadDashboardData() / DashboardComposer
	 */
	long countPendingCheques();

	// ══════════════════════════════════════════════════════════
	// MICR REPAIR OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads cheques for a batch where iqa_status = 'Fail' AND status = 'MICR_Repair'.
	 * Projection-only (no BLOBs). Returns empty list on DB error.
	 *
	 * Called by: MicrRepairComposer on batch load
	 */
	List<ChequeEntity> loadIqaFailedCheques(String batchId);

	/**
	 * Multi-field UPDATE for MICR repair edits:
	 * sort_code, transaction_code, account_no, payee_account_no, base_no,
	 * amount, amount_in_words, amount_words_mismatch, cheque_date, payee_name, status.
	 * NOTE: ver_level intentionally excluded — projection loads verLevel=null;
	 * writing it here would clobber the ver_level set by submitBatch()/verifier flow.
	 * Rethrows on failure.
	 *
	 * Called by: MicrRepairComposer "Save" action
	 */
	void updateChequeFields(ChequeEntity cheque);

	/**
	 * COUNT of cheques where status = 'Sent_for_Verification' AND updated_at >= CURRENT_DATE.
	 * Used for dashboard "repaired today" KPI tile.
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer / MicrRepairComposer stats
	 */
	long countRepairedToday();

	// ══════════════════════════════════════════════════════════
	// HIGH VALUE (HV) OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads cheques for a batch where high_value = true.
	 * Projection-first; falls back to SELECT * on projection failure.
	 * Returns empty list on DB error.
	 *
	 * Called by: HvBatchDetailComposer / VerificationTwoComposer
	 */
	List<ChequeEntity> loadHvChequesForBatch(String batchId);

	/**
	 * COUNT of cheques where high_value = true AND ver_status = 'Pending'.
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer HV pending KPI tile
	 */
	long countHvPendingCheques();

	/**
	 * COUNT of HV cheques filtered by exact ver_status value.
	 * Generic — pass any ver_status string (e.g. 'V2_PENDING', 'VERIFIED').
	 * Returns 0 on DB error.
	 *
	 * Called by: DashboardComposer HV breakdown tiles
	 */
	long countHvByVerStatus(String verStatus);

	/**
	 * COUNT of HV cheques in a specific batch where ver_status = 'Pending'.
	 * Returns 0 on DB error.
	 *
	 * Called by: HvBatchDetailComposer pending-count indicator
	 */
	long countHvPendingForBatch(String batchId);

	/**
	 * SUM of amount for cheques where high_value = true AND ver_status = 'Pending'.
	 * Returns BigDecimal.ZERO on null result or DB error.
	 *
	 * Called by: DashboardComposer HV pending amount KPI tile
	 */
	BigDecimal sumHvPendingAmount();

	// ══════════════════════════════════════════════════════════
	// VERIFICATION OPERATIONS
	// Author : Anusha (V1) / Girinath (V2)
	// ══════════════════════════════════════════════════════════

<<<<<<< Updated upstream
	
	List<ChequeEntity> loadAllPendingV1ChequesAcrossAllBatches(String verLevel, String status);
	
	List<ChequeEntity> loadAllV1ChequesForBatch(String batchId);
=======
	/**
	 * Loads cheques for a verification queue filtered by ver_level + status.
	 * Projection-first; secondary SELECT merges ver_action, ver_by, ver_remarks.
	 * Ordered by batch_id ASC, id ASC (FIFO within batch).
	 * Returns empty list on DB error.
	 *
	 * Called by: VerificationOneComposer (verLevel='V1', status='V1_Pending')
	 *            VerificationTwoComposer (verLevel='V2', status='V2_Pending')
	 */
	List<ChequeEntity> loadChequesByVerLevel(String verLevel, String status);
>>>>>>> Stashed changes

	/**
	 * COUNT of cheques in a batch with status IN ('V1_PENDING', 'V2_PENDING').
	 * Used by BatchServiceImpl.checkAndFinalizeBatch() — when count = 0, batch → 'Verified'.
	 * Returns -1L on DB error (caller treats negative as "do not finalize").
	 *
	 * Called by: BatchServiceImpl.checkAndFinalizeBatch() after each verifier action
	 */
	long countPendingVerificationForBatch(String batchId);
	
	/**
	 * Returns processed V1 cheque counts for ALL given batch IDs in one query.
	 * Processed means verifier performed Accept / Reject / Refer on that cheque.
	 * Replaces the per-batch countV1ProcessedForBatch() loop — fixes N+1 problem.
	 *
	 * @param batchIds set of batch IDs to count for
	 * @return map of batchId to processed count; missing entry means zero
	 */
	
	Map<String, Long> countV1ProcessedForBatches(Set<String> batchIds);
	
	long countV1ProcessedForBatch(String batchId);
	
	/**
	 * Returns distinct batch IDs that have at least one V1 cheque
	 * that was already actioned (VERIFIED / REJECTED / V2_PENDING).
	 * Used to include fully-verified V1 batches in the history list
	 * without pulling in HV-only or V2-only batches.
	 */
	Set<String> loadBatchIdsWithV1ProcessedCheques();

	/**
	 * Persists verifier action on a single cheque.
	 * Updates: status, ver_status, ver_level, ver_action, ver_by, ver_remarks, updated_at.
	 * Called by V1 composer (Anusha) and V2 composer (Girinath).
	 * Rethrows on failure.
	 *
	 * @param chequeId   cheque PK
	 * @param status     new cheque status  : 'VERIFIED' | 'REJECTED' | 'V2_PENDING'
	 * @param verLevel   routing bucket     : 'V1' | 'V2'
	 * @param verAction  action taken       : 'ACCEPTED' | 'REJECTED' | 'REFERRED'
	 * @param verBy      username of actor
	 * @param verRemarks reason (mandatory for REJECTED / REFERRED)
	 */
	void applyVerifierAction(Long chequeId, String status, String verLevel,
			String verAction, String verBy, String verRemarks);

	/**
	 * V1 → V2 escalation. Distinct from applyVerifierAction() because a Refer
	 * must flip two additional fields applyVerifierAction() never touches:
	 *   - ver_level   'V1' → 'V2'
	 *   - is_referred false → true  (permanent flag; V2 must NOT reset this)
	 * Sets status + ver_status = 'V2_PENDING', ver_action = 'Refer'.
	 * Rethrows on failure.
	 *
	 * @param chequeId   cheque PK
	 * @param verBy      V1 username performing the refer
	 * @param verRemarks reason (mandatory in UI)
	 *
	 * Called by: VerificationOneComposer "Refer to V2" action
	 */
	void referToVerificationTwo(Long chequeId, String verBy, String verRemarks);

	// ══════════════════════════════════════════════════════════
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads verified cheques eligible for CXF file generation:
	 * status = 'VERIFIED' AND (ver_status IS NULL OR ver_status != 'CXF_Generated').
	 * Projection + secondary SELECT for transaction_code.
	 * Returns empty list on DB error.
	 *
	 * Called by: CxfGenerationComposer on generate action
	 */
	List<ChequeEntity> loadAcceptedInstrumentsForCxf();

	/**
	 * Sets routing fields after submitBatch() assigns V1/V2 per cheque:
	 * Updates status, ver_level, ver_status, updated_at.
	 * Called once per cheque during batch submission routing loop.
	 * Rethrows on failure.
	 *
	 * Called by: BatchServiceImpl.submitBatch() routing loop
	 */
	void updateVerRouting(Long chequeId, String status, String verLevel, String verStatus);

<<<<<<< Updated upstream
	
=======
	/**
	 * COUNT of V1 cheques in a batch that have been actioned:
	 * ver_level = 'V1' AND status IN ('VERIFIED', 'REJECTED', 'V2_PENDING').
	 * Used to determine V1 progress for a batch.
	 * Returns 0 on DB error.
	 *
	 * Called by: VerificationOneComposer batch progress indicator
	 */
	long countV1ProcessedForBatch(String batchId);
>>>>>>> Stashed changes
}