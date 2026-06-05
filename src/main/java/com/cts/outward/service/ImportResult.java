/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ImportResult.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Value object returned after a ZIP import run.
 *                Summarises success/failure counts, the assigned
 *                batchId, and a list of per-cheque error messages
 *                displayed in the batch-entry result dialog.
 * ============================================================
 */

package com.cts.outward.service;

import java.util.List;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;

public class ImportResult {

	private final BatchEntity batch;
	private final List<ChequeEntity> cheques;
	private final long elapsedMs;

	public ImportResult(BatchEntity batch, List<ChequeEntity> cheques, long elapsedMs) {
		this.batch = batch;
		this.cheques = cheques;
		this.elapsedMs = elapsedMs;
	}

	public BatchEntity getBatch() {
		return batch;
	}

	public List<ChequeEntity> getCheques() {
		return cheques;
	}

	public long getElapsedMs() {
		return elapsedMs;
	}
}