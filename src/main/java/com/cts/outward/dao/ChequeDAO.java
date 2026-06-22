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
import java.util.Set;

import com.cts.outward.entity.ChequeEntity;

public interface ChequeDAO {

	// ══════════════════════════════════════════════════════════
	// DASHBOARD
	// ══════════════════════════════════════════════════════════

	DashboardData loadDashboardData();

	// ══════════════════════════════════════════════════════════
	// CHEQUE OPERATIONS
	// ══════════════════════════════════════════════════════════

	void saveCheque(ChequeEntity cheque);

	void saveCheques(List<ChequeEntity> cheques);

	Set<String> findExistingChequeNos(List<String> chequeNos);

	Set<String> loadReadyBatchIds(List<String> batchIds);

	List<ChequeEntity> loadChequesForBatch(String batchId);

	List<ChequeEntity> loadChequesForBatchFull(String batchId);

	ChequeEntity loadChequeWithImages(Long chequeId);

	void updateChequeStatus(Long chequeId, String status, String verStatus);

	void deleteCheque(Long chequeId);

	long countPendingCheques();

	// ══════════════════════════════════════════════════════════
	// MICR REPAIR OPERATIONS
	// ══════════════════════════════════════════════════════════

	List<ChequeEntity> loadIqaFailedCheques(String batchId);

	void updateChequeFields(ChequeEntity cheque);

	long countRepairedToday();

	// ══════════════════════════════════════════════════════════
	// HIGH VALUE (HV) OPERATIONS
	// ══════════════════════════════════════════════════════════

	List<ChequeEntity> loadHvChequesForBatch(String batchId);

	long countHvPendingCheques();

	long countHvByVerStatus(String verStatus);

	long countHvPendingForBatch(String batchId);

	BigDecimal sumHvPendingAmount();

	// ══════════════════════════════════════════════════════════
	// VERIFICATION OPERATIONS
	// Author : Anusha (V1) / Girinath (V2)
	// ══════════════════════════════════════════════════════════

	List<ChequeEntity> loadChequesByVerLevel(String verLevel, String status);

	long countPendingVerificationForBatch(String batchId);

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

	List<ChequeEntity> loadAcceptedInstrumentsForCxf();

	void updateVerRouting(Long chequeId, String status, String verLevel, String verStatus);
}