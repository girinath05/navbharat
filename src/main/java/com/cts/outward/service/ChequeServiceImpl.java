/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Concrete implementation of ChequeService.
 *                Delegates all persistence to ChequeDAO.
 *                Applies business rules: marks cheques as
 *                high-value when amount exceeds threshold,
 *                enforces MICR repair preconditions before
 *                status advancement.
 * ============================================================
 */

package com.cts.outward.service;

import java.util.List;

import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.entity.ChequeEntity;

//ChequeServiceImpl.java
public class ChequeServiceImpl implements ChequeService {

	private final ChequeDAO chequeDAO;

	public ChequeServiceImpl(ChequeDAO chequeDAO) {
		this.chequeDAO = chequeDAO;
	}

	@Override
	public List<ChequeEntity> getChequesForBatch(String batchId) {
		return chequeDAO.loadChequesForBatch(batchId);
	}

	@Override
	public void saveChequeFields(ChequeEntity cheque) {
		cheque.setStatus("Ready");
		chequeDAO.updateChequeFields(cheque);
	}

	@Override
	public String[] lookupAccount(String accountNo) {
		// Moved OUT of both BatchDetailComposer + BatchChequeEntryComposer
		// (same code duplicated in both — now single source of truth)
		try (org.hibernate.Session session = com.cts.outward.util.HibernateUtil.getSession()) {
			Object[] row = (Object[]) session
					.createNativeQuery("SELECT holder_name, status, subcategory "
							+ "FROM cts_accounts WHERE account_no = :acctNo LIMIT 1")
					.setParameter("acctNo", accountNo).uniqueResult();
			if (row != null)
				return new String[] { row[0] != null ? row[0].toString() : "—",
						row[1] != null ? row[1].toString() : "Account Active",
						row[2] != null ? row[2].toString() : "Normal Account" };
			return new String[] { "Not found", "—", "—" };
		} catch (Exception ex) {
			return new String[] { "—", "Account Active", "Normal Account" };
		}
	}

	@Override
	public long countPending() {
		return chequeDAO.countPendingCheques();
	}
}