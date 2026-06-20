/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Enums
 *  File        : BatchStatus.java
 *  Package     : com.cts.outward.enums
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Canonical enum for all lifecycle states of an outward clearing
 *  batch (cts_batches.status column).
 *
 *  Each constant holds two strings:
 *    dbValue — written to / read from PostgreSQL (never change)
 *    label   — shown in UI chips, dropdowns, and notifications
 *
 * ──────────────────────────────────────────────────────────────
 *  FULL LIFECYCLE DIAGRAM
 * ──────────────────────────────────────────────────────────────
 *
 *  [Maker creates batch in Step 1 modal]
 *           │
 *           ▼
 *        DRAFT
 *  (ZIP not yet uploaded)
 *           │
 *           │  Maker uploads ZIP in Step 2 (ZipImportServiceImpl)
 *           ▼
 *        PENDING  ◄─── "VerificationInProgressAtMaker"
 *  (Some cheques Ready, some still Pending — Maker working)
 *           │
 *           │  Maker clicks "Save Batch" (BatchServiceImpl.saveBatch)
 *           ▼
 *  READY_FOR_VERIFICATION  ◄─── "ReadyForVerification"
 *  (All cheques actioned — batch locked read-only for Maker)
 *           │
 *           │  Verifier opens batch (VerificationOneComposer)
 *           ▼
 *  VERIFICATION_IN_PROGRESS
 *           │
 *           │  All cheques actioned by Verifier(s)
 *           ▼
 *        VERIFIED
 *           │
 *           │  CBSService.generateCxfFile(batchId)
 *           ▼
 *      CXF_GENERATED
 *           │
 *           │  File sent to ClearPay clearing network
 *           ▼
 *       DISPATCHED  ◄── terminal state
 *
 * ──────────────────────────────────────────────────────────────
 *  DB CONTRACT
 * ──────────────────────────────────────────────────────────────
 *  Column  : cts_batches.status  VARCHAR(30)
 *  Written : BatchStatus.DRAFT.db()          → "Draft"
 *  Read    : BatchStatus.fromDb(entity.getStatus())
 *
 *  ⚠ NEVER compare status with raw string literals in code:
 *     BAD:  if ("Draft".equals(batch.getStatus()))
 *     GOOD: if (BatchStatus.DRAFT.db().equals(batch.getStatus()))
 *     BEST: if (BatchStatus.fromDb(batch.getStatus()) == BatchStatus.DRAFT)
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHO READS / WRITES THESE VALUES
 * ──────────────────────────────────────────────────────────────
 *
 *  WRITTEN BY:
 *    BatchServiceImpl.createBatch()
 *      → new batch: DRAFT
 *    ZipImportServiceImpl.importZip()
 *      → after ZIP attached: PENDING  (updateBatchActualCounts)
 *    BatchServiceImpl.saveBatch()
 *      → Maker saves: READY_FOR_VERIFICATION
 *    VerificationOneComposer / VerificationTwoComposer
 *      → verifier actions: VERIFICATION_IN_PROGRESS → VERIFIED
 *    CBSService.generateCxfFile()
 *      → after CXF written: CXF_GENERATED
 *
 *  READ BY:
 *    MyBatchesComposer      — status chip colour in listing grid
 *    BatchDetailComposer    — show/hide action buttons per status
 *    BatchDAOImpl           — HQL WHERE batch.status = :status
 *    ZipParser              — sets initial status = DRAFT on new batch
 * ============================================================
 */

package com.cts.outward.enums;

/**
 * Canonical lifecycle statuses for an outward clearing batch.
 *
 * <p>Each constant encapsulates the exact DB string ({@link #db()}) and the
 * human-readable UI label ({@link #getLabel()}), keeping status strings
 * in one place and eliminating scattered raw-string literals across the codebase.
 *
 * <h3>Typical usage</h3>
 * <pre>
 *   // Writing to DB
 *   batch.setStatus(BatchStatus.DRAFT.db());
 *
 *   // Reading from DB
 *   BatchStatus current = BatchStatus.fromDb(batch.getStatus());
 *   if (current == BatchStatus.READY_FOR_VERIFICATION) { ... }
 *
 *   // UI label
 *   statusChip.setLabel(BatchStatus.fromDb(batch.getStatus()).getLabel());
 * </pre>
 *
 * @author Umesh M.
 * @see ChequeStatus
 * @see com.cts.outward.entity.BatchEntity
 * @see com.cts.outward.service.BatchService
 */
public enum BatchStatus {

    // ══════════════════════════════════════════════════════════════════
    // MAKER STATES
    // ══════════════════════════════════════════════════════════════════

    /**
     * Initial state — batch header created in Step 1 modal but ZIP not yet uploaded.
     *
     * <p><b>Set by:</b> {@code BatchServiceImpl.createBatch()} when the Maker
     * fills in the Create Batch form (Step 1) before uploading a ZIP.
     * Also set by {@code ZipParser.buildBatchEntity()} for direct-upload
     * (Path A) before the entity is persisted.
     *
     * <p><b>UI behaviour:</b> Batch row shows a "Draft" chip in the listing grid.
     * The scan modal (Step 2) remains available. Batch can still be discarded.
     *
     * <p><b>DB value:</b> {@code "Draft"}
     */
    DRAFT("Draft", "Draft"),

    /**
     * Maker has uploaded the ZIP — some cheques are Ready, others still Pending.
     * The Maker is actively reviewing and saving cheques in {@code BatchDetailComposer}.
     *
     * <p><b>Set by:</b> {@code ZipImportServiceImpl.importZip()} via
     * {@code BatchDAOImpl.updateBatchActualCounts()} immediately after the ZIP
     * is parsed and cheques are saved to {@code cts_cheques}.
     *
     * <p><b>UI behaviour:</b> Batch detail page is editable. "Save Batch" button
     * becomes available once all cheques are marked Ready. Maker cannot submit
     * until every cheque has been actioned.
     *
     * <p><b>DB value:</b> {@code "VerificationInProgressAtMaker"}
     * (legacy name from ClearPay spec — do not rename)
     */
    PENDING("VerificationInProgressAtMaker", "Pending"),

    /**
     * All cheques in the batch have been reviewed and saved by the Maker.
     * The batch is now locked read-only for the Maker and sits in the
     * Verifier's queue.
     *
     * <p><b>Set by:</b> {@code BatchServiceImpl.saveBatch()} when the Maker
     * clicks "Save Batch" and all cheques carry status = READY.
     *
     * <p><b>UI behaviour:</b> Batch detail page becomes read-only for the Maker.
     * Edit/delete buttons are hidden. Verifier sees the batch in their queue.
     *
     * <p><b>DB value:</b> {@code "ReadyForVerification"}
     * (replaced earlier value "SubmittedByMaker" — DB migration ran June 2026)
     */
    READY_FOR_VERIFICATION("ReadyForVerification", "Ready for Verification"),

    // ══════════════════════════════════════════════════════════════════
    // VERIFIER / DOWNSTREAM STATES
    // ══════════════════════════════════════════════════════════════════

    /**
     * A Verifier has opened the batch and is actively reviewing cheques.
     *
     * <p><b>Set by:</b> {@code VerificationOneComposer} or
     * {@code VerificationTwoComposer} when the verifier opens the batch
     * detail page (prevents two verifiers acting on the same batch simultaneously).
     *
     * <p><b>UI behaviour:</b> Batch appears as "In Progress" in the Verifier
     * listing grid. Other verifiers see it as locked.
     *
     * <p><b>DB value:</b> {@code "VerificationInProgress"}
     */
    VERIFICATION_IN_PROGRESS("VerificationInProgress", "Verification In Progress"),

    /**
     * All cheques in the batch have been actioned (Accepted/Rejected/Referred)
     * by the required Verifier level(s). Batch is ready for CXF file generation.
     *
     * <p><b>Set by:</b> {@code VerificationOneComposer} / {@code VerificationTwoComposer}
     * when the last pending cheque in the batch is actioned.
     *
     * <p><b>UI behaviour:</b> "Generate CXF" button becomes visible in
     * {@code BatchDetailComposer}. Batch cannot be re-opened for editing.
     *
     * <p><b>DB value:</b> {@code "Verified"}
     */
    VERIFIED("Verified", "Verified"),

    /**
     * CXF (Clearing Exchange Format) XML file has been generated by
     * {@code CBSService.generateCxfFile()} and written to the server filesystem.
     *
     * <p><b>Set by:</b> {@code CBSService.generateCxfFile()} on success,
     * communicated to the composer via {@code CxfFileResult.ok(...)}.
     *
     * <p><b>UI behaviour:</b> "Download CXF" button appears. "Dispatch" button
     * becomes available. Batch detail shows generation timestamp.
     *
     * <p><b>DB value:</b> {@code "CxfGenerated"}
     */
    CXF_GENERATED("CxfGenerated", "CXF Generated"),

    /**
     * CXF file has been submitted to the ClearPay clearing network.
     * This is the terminal state — no further transitions are possible.
     *
     * <p><b>Set by:</b> Dispatch action (post-CXF generation step).
     *
     * <p><b>UI behaviour:</b> All action buttons hidden. Batch appears as
     * "Dispatched" with a green terminal chip in all listing grids.
     *
     * <p><b>DB value:</b> {@code "Dispatched"}
     */
    DISPATCHED("Dispatched", "Dispatched");

    // ══════════════════════════════════════════════════════════════════
    // ENUM FIELDS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Exact string stored in {@code cts_batches.status} column.
     * Must never be changed after data is in production — DB rows hold this value.
     */
    private final String dbValue;

    /**
     * Human-readable text for UI display (status chips, dropdowns, dialogs).
     * Safe to rename without DB migration impact.
     */
    private final String label;

    // ══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════

    BatchStatus(String dbValue, String label) {
        this.dbValue = dbValue;
        this.label   = label;
    }

    // ══════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns the exact string value stored in the {@code cts_batches.status}
     * PostgreSQL column. Use this when writing to or comparing against DB values.
     *
     * @return DB column value, e.g. {@code "ReadyForVerification"}
     */
    public String getDbValue() { return dbValue; }

    /**
     * Returns the human-readable label for UI display in status chips,
     * dropdowns, and notification messages.
     *
     * @return UI label, e.g. {@code "Ready for Verification"}
     */
    public String getLabel() { return label; }

    /**
     * Shorthand alias for {@link #getDbValue()} — reduces verbosity at
     * high-frequency call sites like DAO methods and service layer.
     *
     * <pre>
     *   batch.setStatus(BatchStatus.DRAFT.db());  // vs .getDbValue()
     * </pre>
     *
     * @return same as {@link #getDbValue()}
     */
    public String db() { return dbValue; }

    // ══════════════════════════════════════════════════════════════════
    // FACTORY / LOOKUP
    // ══════════════════════════════════════════════════════════════════

    /**
     * Looks up a {@code BatchStatus} constant by its DB string value.
     *
     * <p>Case-insensitive and trims whitespace — safe to call directly
     * with raw values from HQL query results or JSON deserialization.
     *
     * <p>Returns {@link #DRAFT} as a safe default when the input is
     * {@code null}, blank, or does not match any known status. Callers
     * that need to distinguish "unknown" from "draft" should check the
     * raw value first.
     *
     * <pre>
     *   BatchStatus status = BatchStatus.fromDb(entity.getStatus());
     *   // status is never null — DRAFT is the fallback
     * </pre>
     *
     * <p><b>Called by:</b> {@code MyBatchesComposer} (status chip rendering),
     * {@code BatchDetailComposer} (button visibility logic),
     * {@code BatchDAOImpl} (result mapping).
     *
     * @param raw raw DB string, e.g. {@code "ReadyForVerification"}; may be null
     * @return matching {@code BatchStatus} constant; {@link #DRAFT} if not found
     */
    public static BatchStatus fromDbValue(String raw) {
        if (raw == null || raw.isBlank()) return DRAFT;
        for (BatchStatus status : values()) {
            if (status.dbValue.equalsIgnoreCase(raw.trim())) return status;
        }
        return DRAFT; // safe default — unknown statuses treated as early-stage
    }

    /**
     * Shorthand alias for {@link #fromDbValue(String)}.
     *
     * @param raw raw DB string; may be null
     * @return matching constant or {@link #DRAFT} as fallback
     */
    public static BatchStatus fromDb(String raw) { return fromDbValue(raw); }

    // ══════════════════════════════════════════════════════════════════
    // OBJECT METHODS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns {@link #dbValue} so the enum can be used directly in string
     * concatenation and logging without calling {@code .db()} explicitly.
     *
     * <pre>
     *   LOG.info("Batch status: " + BatchStatus.VERIFIED); // → "Verified"
     * </pre>
     *
     * @return the DB column value string
     */
    @Override
    public String toString() { return dbValue; }
}