/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Concrete implementation of BatchService.
 *                Generates sequential BATCH{n} IDs via
 *                BatchDAO.loadMaxBatchSeq(), delegates all
 *                persistence to BatchDAO, and enforces business
 *                rules (non-zero cheque count, status flow)
 *                before committing.
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.dao.BatchDAO;
import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.exception.BatchSubmitException;

public class BatchServiceImpl implements BatchService {

	private final BatchDAO batchDao;
	private final ChequeDAO chequeDao;

	public BatchServiceImpl(BatchDAO batchDao, ChequeDAO chequeDao) {
		this.batchDao = batchDao;
		this.chequeDao = chequeDao;
	}

	@Override
	public BatchEntity createBatch(String branchCode, int expectedCheques, BigDecimal expectedAmount,
			String createdBy) {
		String batchId = "BATCH" + String.format("%04d", batchDao.loadMaxBatchSeq());
		BatchEntity batch = new BatchEntity();
		batch.setBatchId(batchId);
		batch.setBranchCode(branchCode.toUpperCase());
		batch.setExpectedCheques(expectedCheques);
		batch.setExpectedAmount(expectedAmount);
		batch.setTotalCheques(expectedCheques);
		batch.setStatus("Pending");
		batch.setCreatedBy(createdBy);
		batchDao.saveBatch(batch);
		return batch;
	}

	@Override
	public void discardBatch(String batchId) {
		batchDao.deleteBatchAndCheques(batchId);
	}

	@Override
	public void submitBatch(String batchId) throws BatchSubmitException {
		List<ChequeEntity> cheques = chequeDao.loadChequesForBatch(batchId);
		long pending = cheques.stream()
				.filter(c -> !"Ready".equalsIgnoreCase(c.getStatus()) && !"MICR_Repair".equalsIgnoreCase(c.getStatus()))
				.count();
		long micrRepair = cheques.stream().filter(c -> "MICR_Repair".equalsIgnoreCase(c.getStatus())).count();
		if (pending > 0)
			throw new BatchSubmitException(pending + " cheque(s) still Pending.");
		if (micrRepair > 0)
			throw new BatchSubmitException(micrRepair + " cheque(s) need MICR Repair.");
		batchDao.updateBatchStatus(batchId, "Ready_for_Verification");
	}

	@Override
	public void submitBatchForVerification(String batchId) throws BatchSubmitException {
		submitBatch(batchId);
	}

	@Override
	public BatchEntity getBatchById(String batchId) {
		return batchDao.findBatchById(batchId);
	}

	@Override
	public List<BatchEntity> getAllBatches() {
		return batchDao.loadAllBatches();
	}

	@Override
	public int nextBatchSeq() {
		return batchDao.loadMaxBatchSeq();
	}
}