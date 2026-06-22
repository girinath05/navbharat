/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Enums
 *  File        : ChequeStatus.java
 *  Package     : com.cts.outward.enums
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Canonical enum for all lifecycle states of a single cheque
 *  instrument within an outward clearing batch.
 *
 *  Values are stored in TWO columns of cts_cheques:
 *    status      — overall cheque workflow position
 *    ver_status  — current verification routing position
 *  Both columns use the same set of dbValue strings.
 *
 * ──────────────────────────────────────────────────────────────
 *  FULL LIFECYCLE DIAGRAM
 * ──────────────────────────────────────────────────────────────
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │                     MAKER SIDE                          │
 *  └─────────────────────────────────────────────────────────┘
 *
 *  [ZIP imported by ZipImportServiceImpl]
 *           │
 *           ▼
 *        PENDING   ← "Pending"
 *  (cheque unreviewed by Maker)
 *           │
 *           │  Maker reviews fields in BatchDetailComposer
 *           │  and clicks Save Cheque
 *           ▼
 *         READY    ← "Ready"
 *  (cheque validated by Maker — counts toward batch completion)
 *           │
 *           │  Maker clicks "Save Batch" (all cheques READY)
 *           │  → BatchServiceImpl.saveBatch()
 *           ▼
 *       SUBMITTED  ← "Submitted"
 *  (cheque handed off to Verifier queue — Maker cannot edit)
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │                   VERIFIER ROUTING                      │
 *  └─────────────────────────────────────────────────────────┘
 *
 *       SUBMITTED
 *           │
 *           ├──► V1_PENDING  ← normal routing to Verification I
 *           │         │
 *           │         ├──► VERIFIED   (V1 accepts)
 *           │         ├──► REJECTED   (V1 rejects — excluded from CXF)
 *           │         └──► REFERRED   (V1 refers to V2 — high-value)
 *           │                  │
 *           └──► V2_PENDING ◄──┘  (direct routing OR referral from V1)
 *                     │
 *                     ├──► VERIFIED   (V2 accepts)  ← terminal
 *                     └──► REJECTED   (V2 rejects)  ← terminal
 *
 *  Terminal states: VERIFIED, REJECTED
 *  (REFERRED is transient — always moves to V2_PENDING)
 *
 * ──────────────────────────────────────────────────────────────
 *  DB CONTRACT
 * ──────────────────────────────────────────────────────────────
 *  Columns : cts_cheques.status      VARCHAR(20)
 *            cts_cheques.ver_status  VARCHAR(20)
 *  Written : ChequeStatus.PENDING.db()   → "Pending"
 *  Read    : ChequeStatus.fromDb(entity.getStatus())
 *
 *  ⚠ DO NOT use raw string literals for status comparisons:
 *     BAD:  if ("Ready".equals(cheque.getStatus()))
 *     GOOD: if (ChequeStatus.READY.db().equals(cheque.getStatus()))
 *     BEST: if (ChequeStatus.fromDb(cheque.getStatus()) == ChequeStatus.READY)
 *
 *  ⚠ DO NOT rename Maker-side DB values (PENDING / READY / SUBMITTED).
 *    BatchDetailComposer and BatchServiceImpl check these at runtime.
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHO READS / WRITES THESE VALUES
 * ──────────────────────────────────────────────────────────────
 *
 *  WRITTEN BY:
 *    ZipParser.parsePerChequeXml() / parseMasterXml()
 *      → initial: PENDING (both status + ver_status)
 *    BatchDetailComposer.onSaveFields()
 *      → after Maker saves cheque: READY
 *    BatchServiceImpl.saveBatch()
 *      → after Maker submits batch: SUBMITTED
 *    VerificationOneComposer
 *      → accept: VERIFIED  |  reject: REJECTED  |  refer: REFERRED → V2_PENDING
 *    VerificationTwoComposer
 *      → accept: VERIFIED  |  reject: REJECTED
 *
 *  READ BY:
 *    BatchDetailComposer   — colour-coded status chip per cheque row
 *    BatchServiceImpl      — count READY cheques to gate "Save Batch"
 *    ChequeDAOImpl         — HQL WHERE cheque.status = :status
 *    VerificationOneComposer / VerificationTwoComposer — queue filtering
 * ============================================================
 */

package com.cts.outward.enums;

/**
 * Canonical lifecycle statuses for a single cheque instrument in the
 * outward clearing workflow.
 *
 * <p>Values populate two columns: {@code cts_cheques.status} (overall position)
 * and {@code cts_cheques.ver_status} (verification routing position).
 *
 * <h3>Typical usage</h3>
 * <pre>
 *   // Writing to DB
 *   cheque.setStatus(ChequeStatus.PENDING.db());
 *   cheque.setVerStatus(ChequeStatus.PENDING.db());
 *
 *   // Reading from DB
 *   ChequeStatus current = ChequeStatus.fromDb(cheque.getStatus());
 *   if (current == ChequeStatus.READY) { ... }
 *
 *   // UI chip label
 *   chip.setLabel(ChequeStatus.fromDb(cheque.getStatus()).getLabel());
 * </pre>
 *
 * @author Umesh M.
 * @see BatchStatus
 * @see com.cts.outward.entity.ChequeEntity
 * @see com.cts.outward.service.ChequeService
 */
public enum ChequeStatus {

    // ══════════════════════════════════════════════════════════════════
    // MAKER STATES
    // ⚠ DO NOT change DB values for these three — used in live composer logic
    // ══════════════════════════════════════════════════════════════════

    /**
     * Initial state — cheque imported from ZIP but not yet reviewed by the Maker.
     *
     * <p><b>Set by:</b> {@code ZipParser.parsePerChequeXml()} and
     * {@code parseMasterXml()} for both {@code status} and {@code ver_status}
     * fields immediately after parsing from the ZIP XML.
     *
     * <p><b>UI behaviour:</b> Row shows a grey "Pending" chip in
     * {@code BatchDetailComposer}. Save Cheque button is available.
     * Pending cheques block the "Save Batch" action — all must become READY first.
     *
     * <p><b>DB value:</b> {@code "Pending"}  ← DO NOT change
     */
    PENDING("Pending", "Pending"),

    /**
     * Maker has reviewed and saved this cheque in {@code BatchDetailComposer}.
     * All mandatory fields (amount, payee, drawer, date) have been confirmed.
     *
     * <p><b>Set by:</b> {@code BatchDetailComposer.onSaveFields()} via
     * {@code ChequeServiceImpl.saveChequeFields()}.
     *
     * <p><b>UI behaviour:</b> Row chip turns green. When ALL cheques in the batch
     * are READY, {@code BatchServiceImpl.saveBatch()} gates allow the Maker
     * to submit. READY cheques can still be edited before batch submission.
     *
     * <p><b>DB value:</b> {@code "Ready"}  ← DO NOT change
     */
    READY("Ready", "Ready"),

    /**
     * Maker has submitted the entire batch ({@code BatchServiceImpl.saveBatch()}).
     * All cheques transition to SUBMITTED at that point — the Maker can no
     * longer edit any cheque in the batch.
     *
     * <p><b>Set by:</b> {@code BatchServiceImpl.saveBatch()} — bulk update across
     * all READY cheques in the batch via {@code ChequeDAOImpl.updateAllToSubmitted()}.
     *
     * <p><b>UI behaviour:</b> Cheque rows become read-only in all composer views.
     * Batch listing shows the batch as "Ready for Verification".
     *
     * <p><b>DB value:</b> {@code "Submitted"}  ← DO NOT change
     */
    SUBMITTED("Submitted", "Submitted"),

    // ══════════════════════════════════════════════════════════════════
    // VERIFIER ROUTING STATES
    // ══════════════════════════════════════════════════════════════════

    /**
     * Cheque has been routed to the Verification I (L1) queue.
     *
     * <p><b>Set by:</b> Batch submission routing logic in
     * {@code BatchServiceImpl} or {@code VerificationOneComposer}
     * when assigning cheques to the V1 queue.
     *
     * <p>Standard-value and low-value cheques typically go directly to V1.
     * High-value cheques ({@code highValue == true}) may be routed to
     * V2 directly, bypassing V1.
     *
     * <p><b>DB value:</b> {@code "V1_PENDING"}
     */
    V1_PENDING("V1_PENDING", "V1 Pending"),

    /**
     * Cheque has been routed to the Verification II (L2 / senior) queue.
     * This occurs in two cases:
     * <ol>
     *   <li>Direct routing: high-value cheque ({@code highValue == true})
     *       routed directly from SUBMITTED to V2, bypassing V1.</li>
     *   <li>Escalation: V1 Verifier clicked "Refer to V2" for a standard cheque
     *       ({@link #REFERRED} → V2_PENDING transition).</li>
     * </ol>
     *
     * <p><b>DB value:</b> {@code "V2_PENDING"}
     */
    V2_PENDING("V2_PENDING", "V2 Pending"),

    // ══════════════════════════════════════════════════════════════════
    // TERMINAL / OUTCOME STATES
    // ══════════════════════════════════════════════════════════════════

    /**
     * Cheque has been accepted by a Verifier (V1 or V2) and is included
     * in the CXF file for submission to the ClearPay clearing network.
     *
     * <p><b>Set by:</b> {@code VerificationOneComposer} or
     * {@code VerificationTwoComposer} when the Verifier clicks "Accept".
     *
     * <p><b>UI behaviour:</b> Row chip turns green "Verified". Cheque is
     * included when {@code CBSService.generateCxfFile()} runs.
     * This is a terminal state — no further transitions.
     *
     * <p><b>DB value:</b> {@code "VERIFIED"}
     */
    VERIFIED("VERIFIED", "Verified"),

    /**
     * Cheque has been rejected by a Verifier (V1 or V2) and is excluded
     * from the CXF file. Rejection reason stored in {@code cts_cheques.ver_remarks}.
     *
     * <p><b>Set by:</b> {@code VerificationOneComposer} or
     * {@code VerificationTwoComposer} when the Verifier clicks "Reject"
     * and provides a mandatory rejection reason.
     *
     * <p><b>UI behaviour:</b> Row chip turns red "Rejected".
     * Rejected cheques do NOT appear in the generated CXF XML.
     * This is a terminal state — no further transitions.
     *
     * <p><b>DB value:</b> {@code "REJECTED"}
     */
    
    REJECTED("REJECTED", "Rejected"),
    /**
     * V1 Verifier has escalated this cheque to Verification II for senior review.
     * This is a <b>transient state</b> — the routing logic immediately
     * transitions the cheque from REFERRED → {@link #V2_PENDING}.
     *
     * <p><b>Set by:</b> {@code VerificationOneComposer} when the V1 Verifier
     * clicks "Refer to V2". Triggers escalation logic in
     * {@code ChequeServiceImpl.referToVerificationTwo()}.
     *
     * <p><b>UI behaviour:</b> Briefly shows "Referred" chip before V2_PENDING
     * routing completes. Appear in V2 queue immediately after referral.
     *
     * <p><b>DB value:</b> {@code "REFERRED"}
     */
    REFERRED("REFERRED", "Referred");

    // ══════════════════════════════════════════════════════════════════
    // ENUM FIELDS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Exact string stored in {@code cts_cheques.status} and
     * {@code cts_cheques.ver_status} columns.
     * Must never be changed for PENDING / READY / SUBMITTED — live composer
     * logic depends on these exact values.
     */
    private final String dbValue;

    /**
     * Human-readable text for UI status chips and notification messages.
     * Safe to change without DB migration impact.
     */
    private final String label;

    // ══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════

    ChequeStatus(String dbValue, String label) {
        this.dbValue = dbValue;
        this.label   = label;
    }

    // ══════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns the exact string stored in the {@code cts_cheques.status}
     * and {@code cts_cheques.ver_status} PostgreSQL columns.
     *
     * @return DB column value, e.g. {@code "Pending"}, {@code "V1_PENDING"}
     */
    public String getDbValue() { return dbValue; }

    /**
     * Returns the human-readable label for UI display in status chips
     * and notification dialogs.
     *
     * @return UI label, e.g. {@code "Ready"}, {@code "V1 Pending"}
     */
    public String getLabel()   { return label; }

    /**
     * Shorthand alias for {@link #getDbValue()} — reduces verbosity at
     * high-frequency call sites in parsers and DAOs.
     *
     * <pre>
     *   cheque.setStatus(ChequeStatus.PENDING.db());
     * </pre>
     *
     * @return same as {@link #getDbValue()}
     */
    public String db()         { return dbValue; }

    // ══════════════════════════════════════════════════════════════════
    // FACTORY / LOOKUP
    // ══════════════════════════════════════════════════════════════════

    /**
     * Looks up a {@code ChequeStatus} constant by its DB string value.
     *
     * <p>Case-insensitive and trims whitespace — safe to call directly
     * with raw values from HQL results or external JSON.
     *
     * <p>Returns {@link #PENDING} as a safe default when the input is
     * {@code null}, blank, or unrecognised — matching the initial state
     * of any newly imported cheque.
     *
     * <pre>
     *   ChequeStatus cs = ChequeStatus.fromDb(cheque.getStatus());
     *   // cs is never null
     * </pre>
     *
     * <p><b>Called by:</b> {@code BatchDetailComposer} (chip colour logic),
     * {@code VerificationOneComposer} / {@code VerificationTwoComposer}
     * (action button visibility), {@code ChequeDAOImpl} (result mapping).
     *
     * @param raw raw DB string, e.g. {@code "Ready"}, {@code "V1_PENDING"}; may be null
     * @return matching constant; {@link #PENDING} if not found
     */
    public static ChequeStatus fromDbValue(String raw) {
        if (raw == null || raw.isBlank()) return PENDING;
        for (ChequeStatus status : values()) {
            if (status.dbValue.equalsIgnoreCase(raw.trim())) return status;
        }
        return PENDING; // safe default — unknown treated as unreviewed
    }

    /**
     * Shorthand alias for {@link #fromDbValue(String)}.
     *
     * @param raw raw DB string; may be null
     * @return matching constant or {@link #PENDING} as fallback
     */
    public static ChequeStatus fromDb(String raw) { return fromDbValue(raw); }

    // ══════════════════════════════════════════════════════════════════
    // OBJECT METHODS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns {@link #dbValue} so the enum can be used directly in string
     * concatenation and logging without calling {@code .db()} explicitly.
     *
     * <pre>
     *   LOG.info("Cheque status: " + ChequeStatus.VERIFIED); // → "VERIFIED"
     * </pre>
     *
     * @return the DB column value string
     */
    @Override
    public String toString() { return dbValue; }
}