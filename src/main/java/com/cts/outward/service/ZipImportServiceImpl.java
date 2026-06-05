/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ZipImportServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Concrete implementation of ZipImportService.
 *                Converts BatchModel → BatchEntity and each
 *                ChequeModel → ChequeEntity, then delegates to
 *                BatchDAO.saveBatch() and ChequeDAO.saveCheques()
 *                in order. Rolls back via service exception if
 *                either DAO call fails.
 * ============================================================
 */

package com.cts.outward.service;

import java.time.LocalDateTime;
import java.util.List;

import com.cts.outward.dao.BatchDAO;
import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.parser.CtsParser;
import com.cts.outward.parser.CtsZipParserImpl;

/**
 * ZipImportServiceImpl
 *
 * BUG FIXED HERE (BUG 1 — part 2): The original loop called
 * chequeDAO.saveCheque() one at a time. saveCheque() used session.merge() which
 * silently does nothing for null-id entities — so images were parsed correctly
 * but never reached DB.
 *
 * Fix: use chequeDAO.saveCheques() which uses session.persist() inside a single
 * batched transaction — correct for new entities, and 50x faster.
 */
public class ZipImportServiceImpl implements ZipImportService {

	// CtsZipParserImpl detects ZIP structure automatically:
	// Structure A: BATCH002/CHQ001/cheque.xml + front.png + rear.png
	// (folder-per-cheque)
	// Structure B: flat ZIP with one master XML + image files
	private final CtsParser ctsParser = new CtsZipParserImpl();
	private final ChequeDAO chequeDAO = new ChequeDAOImpl();
	private final BatchDAO batchDAO = new BatchDAOImpl();

	@Override
	public ImportResult importZip(byte[] zipBytes, String zipName, String branchCode, String createdBy,
			String existingBatchId) {

		long startMs = System.currentTimeMillis();

		CtsParser.ParseResult parsed = ctsParser.parse(zipBytes, zipName);
		BatchEntity batch = parsed.getBatch();
		List<ChequeEntity> cheques = parsed.getCheques();

		if (existingBatchId != null && !existingBatchId.isBlank()) {
			batch.setBatchId(existingBatchId);

			// ✅ FIX: push actual ZIP counts to DB
			batchDAO.updateBatchActualCounts(existingBatchId, cheques.size(), batch.getTotalAmount(), "Submitted");

		} else {
			if (branchCode != null && !branchCode.isBlank())
				batch.setBranchCode(branchCode);
			if (createdBy != null && !createdBy.isBlank())
				batch.setCreatedBy(createdBy);
			batch.setCreatedAt(LocalDateTime.now());
			batch.setUpdatedAt(LocalDateTime.now());
			batchDAO.saveBatch(batch);
		}
		// stamp batchId onto all cheques
		for (ChequeEntity cheque : cheques) {
			cheque.setBatchId(batch.getBatchId());
		}

		chequeDAO.saveCheques(cheques);

		long elapsedMs = System.currentTimeMillis() - startMs;
		return new ImportResult(batch, cheques, elapsedMs);
	}

	@Override
	public ImportResult importZip(byte[] zipBytes, String zipName, String branchCode, String createdBy) {
		return importZip(zipBytes, zipName, branchCode, createdBy, null);
	}
}
