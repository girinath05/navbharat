/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Service interface for cheque-level business
 *                operations. Declares bulk save, status update,
 *                MICR repair, and HV verification contracts
 *                consumed by the composer layer.
 * ============================================================
 */

package com.cts.outward.service;

import java.util.List;

import com.cts.outward.entity.ChequeEntity;

//ChequeService.java
public interface ChequeService {
	List<ChequeEntity> getChequesForBatch(String batchId);

	void saveChequeFields(ChequeEntity cheque);

	String[] lookupAccount(String accountNo);

	long countPending();
}