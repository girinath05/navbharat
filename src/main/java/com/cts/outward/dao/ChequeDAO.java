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
	 * Loads all summary counts and totals needed to populate the
	 * outward clearing dashboard in a single query pass.
	 *
	 * Returns a {@link DashboardData} value object containing
	 * pending cheque counts, batch totals, and amount summaries.
	 *
	 * @return DashboardData containing all dashboard metrics; never null
	 */
	DashboardData loadDashboardData();

	// ══════════════════════════════════════════════════════════
	// CHEQUE OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Persists a single cheque record to the cts_cheques table.
	 * Used during individual cheque entry (manual entry flow).
	 *
	 * @param cheque the ChequeEntity to persist; must not be null
	 */
	void saveCheque(ChequeEntity cheque);

	/**
	 * Persists a list of cheque records to the cts_cheques table
	 * in a single batch insert.
	 * Used after ZIP parsing — saves all cheques from one ZIP at once.
	 *
	 * @param cheques list of ChequeEntity objects to persist; must not be null or empty
	 */
	void saveCheques(List<ChequeEntity> cheques);

	/**
	 * Returns the subset of the given cheque numbers that already
	 * exist in the cts_cheques table.
	 *
	 * Used before batch insert to detect and skip duplicate cheques,
	 * preventing unique-constraint violations on cheque_no.
	 *
	 * @param chequeNos list of cheque numbers to check for duplicates
	 * @return set of cheque numbers that already exist in DB; empty set if none found
	 */
	Set<String> findExistingChequeNos(List<String> chequeNos);

	/**
	 * Given a list of batch IDs, returns only those whose batch status
	 * is 'Ready' (i.e., submitted and eligible for processing).
	 *
	 * Used to filter batch selection lists to only show actionable batches.
	 *
	 * @param batchIds list of batch IDs to check
	 * @return set of batch IDs whose status is Ready; empty set if none qualify
	 */
	Set<String> loadReadyBatchIds(List<String> batchIds);

	/**
	 * Loads a summary list of cheques belonging to the given batch.
	 * Returns lightweight cheque data — excludes image blobs.
	 *
	 * Used to render the cheque list in the batch detail view.
	 *
	 * @param batchId the batch ID to load cheques for
	 * @return list of ChequeEntity (no images); empty list if batch has no cheques
	 */
	List<ChequeEntity> loadChequesForBatch(String batchId);

	/**
	 * Loads the full cheque records for a batch including all fields.
	 * Unlike {@link #loadChequesForBatch}, this includes image data
	 * (front scan, rear scan) needed for verification or MICR repair screens.
	 *
	 * @param batchId the batch ID to load full cheque records for
	 * @return list of fully-populated ChequeEntity objects; empty list if none found
	 */
	List<ChequeEntity> loadChequesForBatchFull(String batchId);

	/**
	 * Loads a single cheque record along with its associated image data
	 * (front scan, rear scan) by cheque primary key.
	 *
	 * Used in MICR repair and verification detail screens where the
	 * full cheque image must be displayed alongside cheque data.
	 *
	 * @param chequeId the primary key of the cheque to load
	 * @return ChequeEntity with images populated; null if not found
	 */
	ChequeEntity loadChequeWithImages(Long chequeId);

	/**
	 * Updates the status and verification status of a single cheque.
	 *
	 * Called when a cheque progresses through the clearing workflow —
	 * e.g., from PENDING to V1_PENDING after batch submit.
	 *
	 * @param chequeId  primary key of the cheque to update
	 * @param status    new cheque status value (use ChequeStatus.db())
	 * @param verStatus new verification status value
	 */
	void updateChequeStatus(Long chequeId, String status, String verStatus);

	/**
	 * Deletes a single cheque record from cts_cheques by primary key.
	 * Does NOT adjust the parent batch totals.
	 *
	 * Use {@link #deleteAndDecrementBatch(long)} instead when the batch
	 * total_cheques and control_amount must be kept consistent.
	 *
	 * @param chequeId primary key of the cheque to delete
	 */
	void deleteCheque(Long chequeId);

	/**
	 * Atomically deletes a cheque and decrements the parent batch's
	 * total_cheques by 1 and control_amount by the cheque's amount.
	 * Both mutations run in a single Hibernate transaction.
	 *
	 * @param chequeId cheque PK to delete
	 */
	void deleteAndDecrementBatch(long chequeId);

	/**
	 * Returns the total count of cheques currently in a pending state
	 * across all batches in the system.
	 *
	 * Used by the dashboard to show how many cheques are awaiting action.
	 *
	 * @return count of pending cheques; 0 if none
	 */
	long countPendingCheques();

	// ══════════════════════════════════════════════════════════
	// MICR REPAIR OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads all cheques in the given batch that have failed IQA
	 * (Image Quality Assessment) and are flagged for MICR repair.
	 *
	 * Used to populate the MICR repair queue for a specific batch.
	 *
	 * @param batchId the batch ID to look up IQA-failed cheques for
	 * @return list of ChequeEntity with IQA failures; empty list if none
	 */
	List<ChequeEntity> loadIqaFailedCheques(String batchId);

	/**
	 * Persists corrected MICR field values back to the cts_cheques table
	 * after a repair operator has fixed the cheque data.
	 *
	 * Only cheque data fields are updated — status fields are not changed here.
	 *
	 * @param cheque ChequeEntity containing the corrected field values to save
	 */
	void updateChequeFields(ChequeEntity cheque);

	/**
	 * Returns the count of cheques that were MICR-repaired today
	 * (based on repair timestamp date = current date).
	 *
	 * Used by the dashboard to show today's repair throughput.
	 *
	 * @return count of cheques repaired today; 0 if none
	 */
	long countRepairedToday();

	// ══════════════════════════════════════════════════════════
	// HIGH VALUE (HV) OPERATIONS
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads all high-value cheques (amount ≥ ₹50,000) for the given batch.
	 *
	 * High-value cheques bypass V1 and go directly to V2 verification.
	 * This method is used by the HV queue screen to list those cheques.
	 *
	 * @param batchId the batch ID to load high-value cheques for
	 * @return list of high-value ChequeEntity objects; empty list if none
	 */
	List<ChequeEntity> loadHvChequesForBatch(String batchId);

	/**
	 * Returns the total count of high-value cheques currently pending
	 * verification across all batches.
	 *
	 * Used by the dashboard HV pending count widget.
	 *
	 * @return count of HV cheques in pending state; 0 if none
	 */
	long countHvPendingCheques();

	/**
	 * Returns the count of high-value cheques that are in the given
	 * verification status (e.g., V2_PENDING, VERIFIED, REJECTED).
	 *
	 * Used to build per-status breakdowns in HV reports and dashboard.
	 *
	 * @param verStatus the verification status to filter by
	 * @return count of HV cheques with the given verStatus; 0 if none
	 */
	long countHvByVerStatus(String verStatus);

	/**
	 * Returns the count of high-value cheques still pending verification
	 * within a specific batch.
	 *
	 * Used by the batch detail view to show HV pending count per batch.
	 *
	 * @param batchId the batch ID to check
	 * @return count of HV pending cheques in this batch; 0 if none
	 */
	long countHvPendingForBatch(String batchId);

	/**
	 * Returns the total rupee amount of all high-value cheques that are
	 * still pending verification across all batches.
	 *
	 * Used by the dashboard to display total HV pending amount.
	 *
	 * @return sum of amounts of HV pending cheques; BigDecimal.ZERO if none
	 */
	BigDecimal sumHvPendingAmount();

	// ══════════════════════════════════════════════════════════
	// VERIFICATION OPERATIONS
	// Author : Anusha (V1) / Girinath (V2)
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads all V1-pending cheques across all batches matching the
	 * given verification level and status filter.
	 *
	 * Used by the V1 verification queue to show all cheques awaiting
	 * first-level verification regardless of which batch they belong to.
	 *
	 * @param verLevel the verification level to filter on (e.g., "V1")
	 * @param status   the cheque status to filter on (e.g., "V1_PENDING")
	 * @return list of matching ChequeEntity objects; empty list if none
	 */
	List<ChequeEntity> loadAllPendingV1ChequesAcrossAllBatches(String verLevel, String status);

	/**
	 * Loads all V1 cheques belonging to a specific batch, regardless of
	 * their current verification status (pending, verified, rejected).
	 *
	 * Used to display the full V1 cheque history for a batch in the
	 * verification history view.
	 *
	 * @param batchId the batch ID to load V1 cheques for
	 * @return list of all V1 ChequeEntity objects in this batch; empty list if none
	 */
	List<ChequeEntity> loadAllV1ChequesForBatch(String batchId);

	/**
	 * Returns the count of cheques in a batch that are still pending
	 * verification (have not yet been accepted, rejected, or referred).
	 *
	 * Used by the verification dashboard to show how many cheques
	 * in a batch still need attention.
	 *
	 * @param batchId the batch ID to check
	 * @return count of cheques pending verification in this batch; 0 if none
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

	/**
	 * Returns the count of V1 cheques that have been processed (actioned)
	 * by a verifier in the given batch.
	 *
	 * Processed means the cheque has status VERIFIED, REJECTED, or V2_PENDING —
	 * i.e., the verifier has taken a decision on it.
	 *
	 * @param batchId the batch ID to count processed cheques for
	 * @return count of V1-processed cheques in this batch; 0 if none
	 */
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
	 * Called by V1 composer (Anusha) and V2 composer (Girinath).
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
	 * V1 → V2 escalation. Distinct from {@link #applyVerifierAction} because a
	 * Refer needs to flip TWO things applyVerifierAction never touches:
	 *   - ver_level    'V1' → 'V2'
	 *   - is_referred  false → true  (permanent flag; V2 must NOT reset this)
	 *
	 * status/ver_status both set to 'V2_PENDING'.
	 *
	 * @param chequeId   cheque PK
	 * @param verBy      V1 username performing the refer
	 * @param verRemarks reason (mandatory in UI)
	 */
	void referToVerificationTwo(Long chequeId, String verBy, String verRemarks);

	// ══════════════════════════════════════════════════════════
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════

	/**
	 * Loads all cheques that have been accepted (status = VERIFIED) and
	 * are eligible to be included in the CXF (Clearing XML File) generation.
	 *
	 * Used by the CXF generation module to pull the finalized instrument list
	 * before writing the outward clearing file.
	 *
	 * @return list of accepted ChequeEntity objects ready for CXF; empty list if none
	 */
	List<ChequeEntity> loadAcceptedInstrumentsForCxf();

	/**
	 * Updates the verification routing fields of a cheque after CXF
	 * processing has determined the final clearing path.
	 *
	 * Sets status, ver_level, and ver_status together to keep routing
	 * state consistent after CXF generation completes.
	 *
	 * @param chequeId  primary key of the cheque to update
	 * @param status    new cheque status (use ChequeStatus.db())
	 * @param verLevel  verification level assigned (e.g., "V1" or "V2")
	 * @param verStatus new verification status value
	 */
	void updateVerRouting(Long chequeId, String status, String verLevel, String verStatus);

}