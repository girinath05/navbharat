/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ZipImportService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Service interface for persisting a parsed ZIP
 *                import. Accepts a BatchModel, saves the batch
 *                header and all child ChequeEntities in a single
 *                logical operation, and returns an ImportResult.
 * ============================================================
 */

package com.cts.outward.service;

public interface ZipImportService {

	/**
	 * Full pipeline: ZIP bytes → parsed entities → saved to Supabase.
	 *
	 * @param zipBytes        Raw bytes of the uploaded ZIP
	 * @param zipName         Original filename (used for batch ID)
	 * @param branchCode      Branch from the logged-in user's session
	 * @param createdBy       Username from session
	 * @param existingBatchId If non-null, reuse this batch ID instead of creating a
	 *                        new one. The existing batch row (already in DB) is
	 *                        updated, NOT re-inserted.
	 * @return ImportResult with batch + cheques for UI rendering
	 */
	ImportResult importZip(byte[] zipBytes, String zipName, String branchCode, String createdBy);

	// NEW: attach parsed cheques to an already-created batch
	ImportResult importZip(byte[] zipBytes, String zipName, String branchCode, String createdBy,
			String existingBatchId); // ← pass batchId from step 1
}