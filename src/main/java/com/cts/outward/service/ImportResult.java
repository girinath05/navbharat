/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ImportResult.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Immutable-ish value object (VO) that carries the complete
 *  outcome of a single ZIP import run back to the calling
 *  composer (BatchChequeEntryComposer).
 *
 *  The composer inspects this object and branches into one of
 *  three UI paths:
 *
 *    ┌─────────────────────────────────────────────────────┐
 *    │ ImportResult                                        │
 *    │                                                     │
 *    │  isAllDuplicates() == true  ──► "All Duplicates"   │
 *    │                                  dialog             │
 *    │                                                     │
 *    │  cheques.size() ≠ declared  ──► "Mismatch"         │
 *    │  expected count                  dialog             │
 *    │                                                     │
 *    │  everything matches         ──► success toast       │
 *    └─────────────────────────────────────────────────────┘
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHERE THIS OBJECT IS CREATED AND READ
 * ──────────────────────────────────────────────────────────────
 *
 *  CREATED BY  (producer):
 *  ───────────────────────
 *  ZipImportServiceImpl.importZip(...)
 *    → new ImportResult(batch, cheques, elapsedMs)
 *    → result.setSkippedDuplicates(skipped)
 *    → result.setParsedTotal(parsedTotal)
 *    → result.setParsedTotalAmount(parsedTotalAmount)
 *    → return result
 *
 *  READ BY  (consumers):
 *  ─────────────────────
 *  BatchChequeEntryComposer
 *    onZipUpload()          — Path A (direct upload)
 *    onScanZipUpload()      — Path B (scan-modal upload)
 *      Both call importZip() → receive ImportResult → inspect:
 *        result.isAllDuplicates()       → show AllDuplicates dialog
 *        result.getCheques().size()     → compare with declared count
 *        result.getBatch().getBatchId() → store as pendingBatchId
 *        result.getSkippedDuplicates()  → show in Mismatch dialog
 *        result.getParsedTotalAmount()  → show in AllDuplicates dialog
 *
 * ──────────────────────────────────────────────────────────────
 *  DESIGN NOTES
 * ──────────────────────────────────────────────────────────────
 *  - Core fields (batch, cheques, elapsedMs) are final — set once
 *    at construction and never changed.
 *  - Duplicate-tracking fields (skippedDuplicates, parsedTotal,
 *    parsedTotalAmount) are set via setters immediately after
 *    construction in ZipImportServiceImpl — they are logically
 *    final but Java records were not used here to keep the class
 *    compatible with the existing codebase structure.
 *  - isAllDuplicates() is a derived convenience predicate; it
 *    does not store a separate boolean field to avoid state drift.
 *
 *  CHANGED (Jun 2026):
 *    + skippedDuplicates  — count of cheques already in DB, skipped
 *    + parsedTotal        — total cheques found in ZIP (before dedup)
 *    + parsedTotalAmount  — sum of all parsed amounts   (before dedup)
 *      These three fields enable the "All Duplicates" dialog to show
 *      accurate counts even when zero cheques were persisted.
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;

/**
 * Value object encapsulating the full outcome of one ZIP cheque import run.
 *
 * <p>Instances are created by {@link ZipImportServiceImpl#importZip} and
 * consumed by {@code BatchChequeEntryComposer} to decide which UI dialog or
 * toast to display after the import completes.
 *
 * <h3>Three possible outcomes</h3>
 * <ol>
 *   <li><b>All Duplicates</b> — {@link #isAllDuplicates()} returns {@code true};
 *       {@link #getCheques()} is empty; nothing was written to the DB.</li>
 *   <li><b>Count Mismatch</b> — {@link #getCheques()}.size() differs from the
 *       Maker's declared expected count; cheques were saved but the composer
 *       must prompt the user to accept or discard.</li>
 *   <li><b>Clean Import</b> — all cheques saved and counts match; composer
 *       shows a success notification.</li>
 * </ol>
 *
 * @author Umesh M.
 * @see ZipImportServiceImpl
 * @see ZipImportService
 */
public class ImportResult {

    // ══════════════════════════════════════════════════════════════════
    // CORE FIELDS — set once at construction, never mutated
    // ══════════════════════════════════════════════════════════════════

    /**
     * The BatchEntity that was either created (Path A) or updated (Path B)
     * during this import. On all-duplicates, carries metadata only — the
     * batch may not have been persisted (depends on which path called).
     *
     * <p>Used by the composer to extract {@code batchId}, branch, totals, etc.
     */
    private final BatchEntity batch;

    /**
     * Non-duplicate cheques that were actually saved to {@code cts_cheques}.
     * Empty when all cheques were duplicates ({@link #isAllDuplicates()}).
     *
     * <p>The composer uses this list to render the batch detail table and to
     * compare against the Maker's declared expected count for mismatch detection.
     */
    private final List<ChequeEntity> savedCheques;

    /**
     * Wall-clock milliseconds from the start of {@code importZip()} to just
     * before it returns. Used for performance logging and optional UI display.
     */
    private final long importDurationMs;

    // ══════════════════════════════════════════════════════════════════
    // DUPLICATE-TRACKING FIELDS — set via setters right after construction
    // ══════════════════════════════════════════════════════════════════

    /**
     * Number of cheque numbers from the ZIP that already existed in
     * {@code cts_cheques} and were therefore excluded from the INSERT.
     *
     * <p>Populated by {@code ZipImportServiceImpl} from the set returned by
     * {@code ChequeDAOImpl.findExistingChequeNos()}.
     * Shown in the Mismatch dialog's "Duplicates skipped" row.
     */
    private int skippedDuplicates = 0;

    /**
     * Total cheques extracted from the ZIP XML <b>before</b> duplicate
     * filtering. Equals {@code savedCheques.size() + skippedDuplicates}.
     *
     * <p>Used in the All-Duplicates dialog to inform the user how many
     * cheques the ZIP contained even though none were saved.
     */
    private int parsedTotal = 0;

    /**
     * Sum of {@code Amount} fields across <b>all</b> parsed cheques,
     * including those skipped as duplicates. Always non-null (defaults to ZERO).
     *
     * <p>Shown in the All-Duplicates dialog as "Amount in ZIP" so the user
     * understands the full scope of the rejected upload.
     */
    private BigDecimal parsedTotalAmount = BigDecimal.ZERO;

    // ══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════

    /**
     * Constructs an ImportResult with the core import outcome.
     *
     * <p><b>Caller:</b> {@link ZipImportServiceImpl#importZip} — invoked once
     * per ZIP upload, regardless of whether cheques were saved or all-duplicates.
     *
     * @param batch            the batch entity (persisted or metadata-only)
     * @param savedCheques     cheques actually inserted into the DB (may be empty)
     * @param importDurationMs elapsed time of the full import pipeline in milliseconds
     */
    public ImportResult(BatchEntity batch, List<ChequeEntity> savedCheques, long importDurationMs) {
        this.batch             = batch;
        this.savedCheques      = savedCheques;
        this.importDurationMs  = importDurationMs;
    }

    // ══════════════════════════════════════════════════════════════════
    // CORE GETTERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns the batch entity associated with this import.
     *
     * <p>On Path A (direct upload) — this is the newly created batch row.
     * On Path B (scan modal)       — this is the updated Draft batch.
     * On all-duplicates            — batch carries metadata but may not be persisted;
     *                                the caller is responsible for discarding it.
     *
     * @return non-null {@link BatchEntity}
     */
    public BatchEntity getBatch() {
        return batch;
    }

    /**
     * Returns the list of cheque entities that were successfully saved to the DB.
     *
     * <p>Empty when {@link #isAllDuplicates()} is {@code true}.
     * The composer uses this list for:
     * <ul>
     *   <li>Mismatch detection: {@code savedCheques.size()} vs declared count</li>
     *   <li>Batch detail table population after a clean import</li>
     * </ul>
     *
     * @return unmodifiable list of saved {@link ChequeEntity} objects
     */
    public List<ChequeEntity> getCheques() {
        return savedCheques;
    }

    /**
     * Returns total wall-clock time (in milliseconds) taken by the full
     * import pipeline, from ZIP parsing through DB persistence.
     *
     * @return elapsed time in milliseconds; always ≥ 0
     */
    public long getElapsedMs() {
        return importDurationMs;
    }

    // ══════════════════════════════════════════════════════════════════
    // DUPLICATE-TRACKING GETTERS AND SETTERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns how many cheques were skipped because their cheque numbers
     * already existed in {@code cts_cheques}.
     *
     * <p>Read by {@code BatchChequeEntryComposer} to populate the
     * "Duplicates skipped" field in the Mismatch dialog.
     *
     * @return count of skipped duplicate cheques; 0 if no duplicates were found
     */
    public int getSkippedDuplicates() {
        return skippedDuplicates;
    }

    /**
     * Sets the duplicate skip count.
     *
     * <p><b>Caller:</b> {@link ZipImportServiceImpl#importZip} — set immediately
     * after constructing this result, using the size of the set returned by
     * {@code ChequeDAOImpl.findExistingChequeNos()}.
     *
     * @param skippedDuplicates number of cheques excluded due to duplication
     */
    public void setSkippedDuplicates(int skippedDuplicates) {
        this.skippedDuplicates = skippedDuplicates;
    }

    /**
     * Returns the total cheque count from the ZIP <b>before</b> duplicate filtering.
     *
     * <p>Equals {@code getCheques().size() + getSkippedDuplicates()} when the
     * import completed normally. Used in the All-Duplicates dialog header:
     * "All N cheques in this ZIP already exist in the system."
     *
     * @return total cheques parsed from ZIP; 0 if ZIP was empty
     */
    public int getParsedTotal() {
        return parsedTotal;
    }

    /**
     * Sets the total parsed cheque count (before duplicate filtering).
     *
     * <p><b>Caller:</b> {@link ZipImportServiceImpl#importZip}
     *
     * @param parsedTotal count of cheques extracted from ZIP XML
     */
    public void setParsedTotal(int parsedTotal) {
        this.parsedTotal = parsedTotal;
    }

    /**
     * Returns the sum of all cheque amounts from the ZIP,
     * <b>including</b> those that were skipped as duplicates.
     *
     * <p>Shown in the All-Duplicates dialog as "Total amount in ZIP"
     * so the Maker understands the financial scope of the rejected upload.
     *
     * @return total amount across all parsed cheques; never {@code null}
     */
    public BigDecimal getParsedTotalAmount() {
        return parsedTotalAmount;
    }

    /**
     * Sets the total parsed amount (before duplicate filtering).
     * Guards against null by defaulting to {@link BigDecimal#ZERO}.
     *
     * <p><b>Caller:</b> {@link ZipImportServiceImpl#importZip}
     *
     * @param parsedTotalAmount sum of all cheque amounts from ZIP (pre-dedup)
     */
    public void setParsedTotalAmount(BigDecimal parsedTotalAmount) {
        this.parsedTotalAmount = (parsedTotalAmount != null) ? parsedTotalAmount : BigDecimal.ZERO;
    }

    

    /**
     * Returns {@code true} when every cheque in the uploaded ZIP was already
     * present in the database — meaning no new data was saved.
     *
     * <p>This predicate drives the "All Cheques Already Present" dialog branch
     * in {@code BatchChequeEntryComposer}:
     * <pre>
     *   ImportResult result = zipImportService.importZip(...);
     *   if (result.isAllDuplicates()) {
     *       batchService.discardBatch(pendingBatchId);  // clean up empty draft
     *       openAllDuplicatesDialog(result);
     *       return;
     *   }
     * </pre>
     *
     * <h3>Predicate logic</h3>
     * All three conditions must hold:
     * <ol>
     *   <li>{@code parsedTotal > 0}  — ZIP was not empty</li>
     *   <li>{@code skippedDuplicates == parsedTotal}  — every cheque was a dup</li>
     *   <li>{@code savedCheques.isEmpty()}  — nothing made it past dedup</li>
     * </ol>
     *
     * @return {@code true} if zero new cheques were saved because all were duplicates
     */
    public boolean isAllDuplicates() {
        return parsedTotal > 0
                && skippedDuplicates == parsedTotal
                && savedCheques.isEmpty();
    }
}