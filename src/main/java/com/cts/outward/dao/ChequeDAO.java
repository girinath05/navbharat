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
 *                and CXF generation selects. Implemented by
 *                ChequeDAOImpl using Hibernate sessions.
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.List;

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
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════

	List<ChequeEntity> loadAcceptedInstrumentsForCxf();
}