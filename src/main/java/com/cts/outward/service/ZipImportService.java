/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ZipImportService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Service interface for the full ZIP import pipeline:
 *
 *    raw ZIP bytes  →  parse  →  dedup  →  persist  →  ImportResult
 *
 *  This is the single orchestration point for all ZIP-based
 *  cheque imports. Callers (composers) hand off raw bytes and
 *  session context; this service owns the entire pipeline and
 *  returns a complete ImportResult for UI rendering.
 *
 *  Single implementation: ZipImportServiceImpl.java
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS INTERFACE FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Composer Layer]               [Service Layer]
 *  ──────────────────────────     ─────────────────────────────────────────────
 *  BatchChequeEntryComposer       ZipImportService (this)
 *    onZipUpload()       ──────►  importZip(bytes, name, branch, user)
 *    onScanZipUpload()   ──────►  importZip(bytes, name, branch, user, batchId)
 *                                   │
 *                                   ├──► ZipProcessingService (parse + dedup)
 *                                   │      └──► CtsZipParserImpl (XML extraction)
 *                                   │      └──► ChequeDAOImpl.findExistingChequeNos()
 *                                   │
 *                                   ├──► BatchDAOImpl.saveBatch()     [Path A only]
 *                                   └──► ChequeDAOImpl.saveCheques()
 *
 * ──────────────────────────────────────────────────────────────
 *  TWO IMPORT PATHS — OVERLOADED METHOD DESIGN
 * ──────────────────────────────────────────────────────────────
 *  The interface exposes two overloads reflecting two user flows:
 *
 *  PATH A — Direct Upload (no pre-created batch):
 *  ────────────────────────────────────────────────
 *  importZip(bytes, name, branch, user)
 *    → Impl generates a new batch row (auto-ID) inside the pipeline
 *    → Called from: BatchChequeEntryComposer.onZipUpload()
 *
 *  PATH B — Scan Modal (batch pre-created in Step 1):
 *  ────────────────────────────────────────────────────
 *  importZip(bytes, name, branch, user, existingBatchId)
 *    → Impl reuses the Draft batch created in Step 1
 *    → Updates the existing batch row (no INSERT)
 *    → Called from: BatchChequeEntryComposer.onScanZipUpload()
 *
 *  The existingBatchId parameter is the bridge between
 *  BatchChequeEntryComposer.pendingBatchId (set in Step 1 by
 *  onCreateBatch) and the ZIP import pipeline in Step 2.
 *
 * ──────────────────────────────────────────────────────────────
 *  IMPORT RESULT CASES
 * ──────────────────────────────────────────────────────────────
 *  The returned ImportResult signals one of three outcomes to
 *  the calling composer:
 *
 *  Case 1 — All Duplicates:
 *    importResult.isAllDuplicates() == true
 *    → No new data saved; calling composer discards pending batch
 *      and shows the "All Duplicates" dialog
 *
 *  Case 2 — Count Mismatch:
 *    importResult.getCheques().size() != expectedChequeCount
 *    → New cheques saved, but count differs from Maker's declared total
 *    → Calling composer shows the "Mismatch" dialog (Accept/Discard)
 *    → (Mismatch detection is done by the composer, not here)
 *
 *  Case 3 — Clean Import:
 *    All cheques saved; count matches declared total
 *    → Calling composer shows success toast
 *
 * ──────────────────────────────────────────────────────────────
 *  DEDUPLICATION STRATEGY
 * ──────────────────────────────────────────────────────────────
 *  Before saving, the pipeline calls ChequeDAOImpl.findExistingChequeNos()
 *  which queries cts_cheques for any cheque numbers already present.
 *  Matching cheques are excluded from the INSERT batch.
 *
 *  ImportResult.getSkippedDuplicates() carries the count of
 *  skipped cheques for display in the Mismatch dialog's
 *  "Duplicates skipped" row.
 * ============================================================
 */

package com.cts.outward.service;

/**
 * Service contract for the full ZIP-to-DB cheque import pipeline.
 *
 * <p>Two overloads cover the two user flows — direct upload (Path A) and
 * scan-modal upload against a pre-created batch (Path B). See class-level
 * Javadoc for the complete pipeline architecture and result cases.
 *
 * @author Umesh M.
 * @see ZipImportServiceImpl
 * @see ZipProcessingService
 * @see ImportResult
 */
public interface ZipImportService {

    // ══════════════════════════════════════════════════════════════════════
    // PATH A — DIRECT UPLOAD (auto-creates a new batch)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs the full ZIP import pipeline and creates a new batch row in the DB.
     *
     * <p><b>Path A</b> — used when the Maker uploads a ZIP directly without
     * first filling in the Create Batch modal. The batch ID and control totals
     * are derived entirely from the parsed ZIP contents; no Maker-declared
     * expected count is involved.
     *
     * <h3>Pipeline steps</h3>
     * <ol>
     *   <li>Parse ZIP bytes → extract cheque XML/image files via
     *       {@code ZipProcessingService}</li>
     *   <li>Deduplicate — query DB for existing cheque numbers; exclude matches</li>
     *   <li>If all cheques are duplicates → return early with
     *       {@code ImportResult.isAllDuplicates() == true}; nothing saved</li>
     *   <li>Create new batch row (auto-generated batchId) via {@code BatchDAOImpl}</li>
     *   <li>Save non-duplicate cheques to {@code cts_cheques} via
     *       {@code ChequeDAOImpl}</li>
     *   <li>Return {@link ImportResult} with saved batch + cheques for UI rendering</li>
     * </ol>
     *
     * <h3>Called by</h3>
     * {@code BatchChequeEntryComposer.onZipUpload(UploadEvent)} — Path A upload
     * handler on the main scan module page (not inside the scan modal).
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchChequeEntryComposer.onZipUpload()
     *   → ZipImportServiceImpl.importZip(bytes, name, branch, user)
     *       → ZipProcessingServiceImpl.processZip(bytes)   [parse + dedup]
     *       → if allDuplicates → return ImportResult(allDuplicates=true)
     *       → BatchDAOImpl.saveBatch(newBatch)              [INSERT new batch]
     *       → ChequeDAOImpl.saveCheques(cheques)            [INSERT cheques]
     *       → return ImportResult(batch, cheques, parsedTotal, skippedCount)
     * </pre>
     *
     * @param zipBytes   raw bytes of the uploaded ZIP file from ZK UploadEvent
     * @param zipName    original filename (e.g. "MUM01_20260610.zip"); used for
     *                   branch derivation and logging
     * @param branchCode branch code from the logged-in user's session (e.g. "MUM01")
     * @param createdBy  logged-in user name from session; stored as batch.createdBy
     * @return {@link ImportResult} describing the outcome; check
     *         {@code isAllDuplicates()} before accessing batch/cheque fields
     */
    ImportResult importZip(byte[] zipBytes, String zipName, String branchCode, String createdBy);

    // ══════════════════════════════════════════════════════════════════════
    // PATH B — SCAN MODAL UPLOAD (attaches cheques to pre-created batch)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs the full ZIP import pipeline and attaches parsed cheques to an
     * already-created Draft batch instead of creating a new one.
     *
     * <p><b>Path B</b> — used when the Maker completed Step 1 (Create Batch modal)
     * and is now uploading a ZIP in Step 2 (Scan Modal). The {@code existingBatchId}
     * was created by {@code BatchServiceImpl.createBatch()} and stored as
     * {@code BatchChequeEntryComposer.pendingBatchId}.
     *
     * <p>Key difference from the no-{@code existingBatchId} overload:
     * <ul>
     *   <li>No new batch row is inserted — the existing Draft batch is <b>updated</b>
     *       with the actual cheque count and total amount from the ZIP</li>
     *   <li>Mismatch detection (actual count vs Maker's declared count) is done
     *       by the calling composer after this method returns, not here</li>
     *   <li>If all cheques are duplicates, the calling composer calls
     *       {@code BatchService.discardBatch(existingBatchId)} to clean up
     *       the empty Draft batch — this method does NOT discard it</li>
     * </ul>
     *
     * <h3>Pipeline steps</h3>
     * <ol>
     *   <li>Parse ZIP bytes → extract cheque XML/image files</li>
     *   <li>Deduplicate against existing cheque numbers in DB</li>
     *   <li>If all duplicates → return {@code ImportResult(allDuplicates=true)};
     *       caller is responsible for discarding {@code existingBatchId}</li>
     *   <li>UPDATE existing batch row: set total_cheques, total_amount,
     *       status = "VerificationInProgressAtMaker"</li>
     *   <li>INSERT non-duplicate cheques linked to {@code existingBatchId}</li>
     *   <li>Return {@link ImportResult} for the calling composer to inspect</li>
     * </ol>
     *
     * <h3>Called by</h3>
     * {@code BatchChequeEntryComposer.onScanZipUpload(UploadEvent)} — ZIP upload
     * handler inside the Scan Modal (Step 2 of the two-step workflow).
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchChequeEntryComposer.onScanZipUpload()
     *   → ZipImportServiceImpl.importZip(bytes, name, branch, user, existingBatchId)
     *       → ZipProcessingServiceImpl.processZip(bytes)       [parse + dedup]
     *       → if allDuplicates → return ImportResult(allDuplicates=true)
     *                            [caller discards existingBatchId]
     *       → BatchDAOImpl.updateBatch(existingBatchId, count, amount, status)
     *                                                           [UPDATE, not INSERT]
     *       → ChequeDAOImpl.saveCheques(cheques, existingBatchId) [INSERT cheques]
     *       → return ImportResult(batch, cheques, parsedTotal, skippedCount)
     *   → composer inspects result:
     *       Case 1: allDuplicates → discardPendingBatch() → openDuplicateDialog()
     *       Case 2: count mismatch → store pendingMismatchResult → openMismatchDialog()
     *       Case 3: clean import  → finishSuccessfulImport()
     * </pre>
     *
     * @param zipBytes        raw bytes of the uploaded ZIP file
     * @param zipName         original filename for logging
     * @param branchCode      branch code from session
     * @param createdBy       logged-in user name from session
     * @param existingBatchId batchId of the Draft batch created in Step 1
     *                        (BatchChequeEntryComposer.pendingBatchId);
     *                        must be non-null and exist in cts_batches
     * @return {@link ImportResult} describing the outcome; check
     *         {@code isAllDuplicates()} before accessing batch/cheque fields
     */
    ImportResult importZip(byte[] zipBytes, String zipName, String branchCode,
                           String createdBy, String existingBatchId);
}