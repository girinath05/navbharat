/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Entity Layer
 *  File        : BatchEntity.java
 *  Package     : com.cts.outward.entity
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Hibernate JPA entity mapping the PostgreSQL table cts_batches.
 *  One row = one outward clearing batch submitted by a Maker.
 *
 *  A batch groups a set of physical cheques scanned at a branch
 *  into a single unit for verification, CXF generation, and
 *  ClearPay submission. This entity is the top-level aggregate
 *  root of the outward clearing domain.
 *
 * ──────────────────────────────────────────────────────────────
 *  DB TABLE  :  cts_batches  (Supabase PostgreSQL)
 * ──────────────────────────────────────────────────────────────
 *  Column              Type              Notes
 *  ──────────────────  ────────────────  ────────────────────────
 *  batch_id            VARCHAR(20) PK    Derived from ZIP filename
 *  branch_code         VARCHAR(10)       Maker's branch (e.g. MUM01)
 *  total_cheques       INT               Actual count after ZIP import
 *  total_amount        NUMERIC(15,2)     Actual sum after ZIP import
 *  control_amount      NUMERIC(15,2)     Maker-declared control total
 *  expected_cheques    INT               Maker-declared expected count
 *  expected_amount     NUMERIC(15,2)     Maker-declared expected amount
 *  batch_type          VARCHAR(30)       Retail / Loan / Govt / etc.
 *  status              VARCHAR(30)       BatchStatus enum db() value
 *  created_by          VARCHAR(50)       Logged-in Maker user name
 *  created_at          TIMESTAMP         Insert time (default = now)
 *  updated_at          TIMESTAMP         Last status/field change time
 *
 *  Run if columns are missing (first deploy):
 *    ALTER TABLE cts_batches ADD COLUMN IF NOT EXISTS control_amount NUMERIC(15,2);
 *    ALTER TABLE cts_batches ADD COLUMN IF NOT EXISTS batch_type     VARCHAR(30);
 *
 * ──────────────────────────────────────────────────────────────
 *  STATUS LIFECYCLE  (see BatchStatus enum)
 * ──────────────────────────────────────────────────────────────
 *  DRAFT
 *    → created by BatchServiceImpl.createBatch() (Step 1 modal)
 *    → ZIP attached by ZipImportServiceImpl (Step 2 scan modal)
 *  PENDING  (VerificationInProgressAtMaker)
 *    → Maker clicks "Save Batch" — batch locks read-only
 *  READY_FOR_VERIFICATION
 *    → Maker submits batch to Verifier queue
 *  VERIFIED / REJECTED
 *    → Set by VerificationOneComposer / VerificationTwoComposer
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHO READS / WRITES THIS ENTITY
 * ──────────────────────────────────────────────────────────────
 *
 *  CREATED:
 *    BatchServiceImpl.createBatch()
 *      → new BatchEntity() → BatchDAOImpl.saveBatch()
 *
 *  UPDATED:
 *    ZipImportServiceImpl.importZip()
 *      → BatchDAOImpl.updateBatchActualCounts(batchId, count, amount, status)
 *    BatchServiceImpl.saveBatch() / discardBatch()
 *      → BatchDAOImpl.updateBatchStatus()
 *
 *  READ:
 *    BatchChequeEntryComposer  — display batch header in scan module
 *    BatchDetailComposer       — display/edit batch detail page
 *    MyBatchesComposer         — list view (grid rows)
 *    VerificationOneComposer   — read-only header during verification
 *    VerificationTwoComposer   — read-only header during verification
 *    ZipImportServiceImpl      — carry metadata back as ImportResult.batch
 *
 * ──────────────────────────────────────────────────────────────
 *  CHANGES (Jun 2026)
 * ──────────────────────────────────────────────────────────────
 *  + controlAmount  — Maker-declared control total shown as "Control Amt"
 *                     in the batch listing grid (MyBatchesComposer)
 *  + batchType      — Retail / Loan / Govt chip shown in listing grid;
 *                     set by BatchChequeEntryComposer.onCreateBatch()
 * ============================================================
 */

package com.cts.outward.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity for the {@code cts_batches} table.
 *
 * <p>Represents one outward clearing batch: a logical grouping of scanned
 * cheques submitted by a Maker at a branch, progressing through the
 * DRAFT → PENDING → READY_FOR_VERIFICATION → VERIFIED/REJECTED lifecycle.
 *
 * <p>Batch ID is the natural key derived from the ZIP filename
 * (e.g. {@code MUM01_20260610}) rather than a DB-generated surrogate,
 * because it must match the ClearPay submission reference exactly.
 *
 * @author Umesh M.
 * @see com.cts.outward.enums.BatchStatus
 * @see com.cts.outward.dao.BatchDAO
 * @see com.cts.outward.service.BatchService
 */
@Entity
@Table(name = "cts_batches")
public class BatchEntity {

    // ══════════════════════════════════════════════════════════════════
    // PRIMARY KEY
    // ══════════════════════════════════════════════════════════════════

    /**
     * Natural primary key derived from the ZIP filename (without extension),
     * uppercased. Example: {@code MUM01_20260610}.
     *
     * <p>Not DB-generated — the ClearPay clearing network expects this ID
     * to match the submitted ZIP filename for reconciliation.
     * Set by {@code ZipParser.buildBatchId()} during ZIP parsing.
     */
    @Id
    @Column(name = "batch_id", length = 20)
    private String batchId;

    // ══════════════════════════════════════════════════════════════════
    // BRANCH / OWNERSHIP
    // ══════════════════════════════════════════════════════════════════

    /**
     * RBI branch code of the submitting branch (e.g. {@code MUM01}).
     * Sourced from the logged-in Maker's session in
     * {@code BatchChequeEntryComposer} and written by
     * {@code ZipImportServiceImpl.importZip()}.
     */
    @Column(name = "branch_code", length = 10)
    private String branchCode;

    // ══════════════════════════════════════════════════════════════════
    // ACTUAL COUNTS (set after ZIP import)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Actual number of non-duplicate cheques saved to {@code cts_cheques}
     * for this batch. Set by {@code ZipImportServiceImpl} after deduplication.
     *
     * <p>Compared against {@link #expectedCheques} by
     * {@code BatchChequeEntryComposer} for mismatch detection.
     */
    @Column(name = "total_cheques")
    private int totalCheques;

    /**
     * Actual sum of all saved cheque amounts in INR.
     * Set by {@code ZipImportServiceImpl} after deduplication (null-safe sum).
     *
     * <p>Compared against {@link #expectedAmount} for mismatch detection and
     * shown in the batch summary header of {@code BatchDetailComposer}.
     */
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    // ══════════════════════════════════════════════════════════════════
    // MAKER-DECLARED CONTROL TOTALS (set at Step 1 — Create Batch modal)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Maker-declared control amount entered in the "Create Batch" modal
     * (Step 1) before the ZIP is uploaded. Shown as "Control Amt" in the
     * {@code MyBatchesComposer} batch listing grid.
     *
     * <p>Used for reconciliation: if {@code totalAmount} ≠ {@code controlAmount}
     * after import, the Mismatch dialog is triggered.
     *
     * <p><b>DB migration required on first deploy:</b>
     * {@code ALTER TABLE cts_batches ADD COLUMN IF NOT EXISTS control_amount NUMERIC(15,2);}
     */
    @Column(name = "control_amount", precision = 15, scale = 2)
    private BigDecimal controlAmount;

    /**
     * Maker-declared expected cheque count entered in the "Create Batch" modal.
     * Compared against {@link #totalCheques} after ZIP import for mismatch detection.
     */
    @Column(name = "expected_cheques")
    private int expectedCheques;

    /**
     * Maker-declared expected total amount entered in the "Create Batch" modal.
     * Compared against {@link #totalAmount} after ZIP import for mismatch detection.
     */
    @Column(name = "expected_amount", precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    // ══════════════════════════════════════════════════════════════════
    // CLASSIFICATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Batch type classification selected by the Maker in Step 1.
     * Example values: {@code "Retail"}, {@code "Loan"}, {@code "Govt"}.
     *
     * <p>Displayed as a chip/badge in the {@code MyBatchesComposer} listing grid.
     * Set by {@code BatchChequeEntryComposer.onCreateBatch()} from a ZUL dropdown.
     *
     * <p><b>DB migration required on first deploy:</b>
     * {@code ALTER TABLE cts_batches ADD COLUMN IF NOT EXISTS batch_type VARCHAR(30);}
     */
    @Column(name = "batch_type", length = 30)
    private String batchType;

    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE STATUS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Current lifecycle status of the batch. Stores the {@code .db()} string
     * value from {@link com.cts.outward.enums.BatchStatus}, e.g.
     * {@code "Draft"}, {@code "VerificationInProgressAtMaker"},
     * {@code "ReadyForVerification"}.
     *
     * <p>{@link #setStatus(String)} auto-updates {@link #updatedAt} on every
     * status change to maintain a reliable audit trail.
     */
    @Column(name = "status", length = 30)
    private String status;

    // ══════════════════════════════════════════════════════════════════
    // AUDIT FIELDS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Username of the Maker who created the batch. Sourced from the ZK
     * session attribute set at login. Stored for audit and display in
     * the batch detail header.
     */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    /**
     * Server timestamp when this batch row was first inserted.
     * Defaults to {@code LocalDateTime.now()} at object construction.
     * Never updated after the initial INSERT.
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Server timestamp of the most recent modification to this batch row.
     * Auto-updated by {@link #setStatus(String)} on every status change.
     * Also updated explicitly by {@code BatchDAOImpl.updateBatchActualCounts()}.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ══════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS
    // ══════════════════════════════════════════════════════════════════

    /** @return natural PK derived from ZIP filename (e.g. {@code MUM01_20260610}) */
    public String getBatchId()              { return batchId; }
    public void   setBatchId(String v)      { this.batchId = v; }

    /** @return RBI branch code of the submitting branch (e.g. {@code MUM01}) */
    public String getBranchCode()           { return branchCode; }
    public void   setBranchCode(String v)   { this.branchCode = v; }

    /** @return actual cheque count saved after ZIP import and deduplication */
    public int    getTotalCheques()         { return totalCheques; }
    public void   setTotalCheques(int v)    { this.totalCheques = v; }

    /** @return actual total amount of saved cheques in INR; may be null before import */
    public BigDecimal getTotalAmount()          { return totalAmount; }
    public void       setTotalAmount(BigDecimal v) { this.totalAmount = v; }

    /**
     * Returns the Maker-declared control amount from the Create Batch modal.
     * Shown as "Control Amt" in the batch listing grid.
     *
     * @return control amount; may be null if not entered
     */
    public BigDecimal getControlAmount()            { return controlAmount; }
    public void       setControlAmount(BigDecimal v){ this.controlAmount = v; }

    /** @return Maker-declared expected cheque count (for mismatch detection) */
    public int    getExpectedCheques()          { return expectedCheques; }
    public void   setExpectedCheques(int v)     { this.expectedCheques = v; }

    /** @return Maker-declared expected total amount (for mismatch detection) */
    public BigDecimal getExpectedAmount()            { return expectedAmount; }
    public void       setExpectedAmount(BigDecimal v){ this.expectedAmount = v; }

    /**
     * Returns the batch type classification (e.g. {@code "Retail"}, {@code "Loan"}).
     * Displayed as a chip in the batch listing grid.
     *
     * @return batch type string; may be null if not set
     */
    public String getBatchType()            { return batchType; }
    public void   setBatchType(String v)    { this.batchType = v; }

    /**
     * Returns the current lifecycle status as a DB string.
     * Use {@link com.cts.outward.enums.BatchStatus} to interpret the value.
     *
     * @return status string, e.g. {@code "Draft"}, {@code "ReadyForVerification"}
     */
    public String getStatus()               { return status; }

    /**
     * Sets the lifecycle status and auto-refreshes {@link #updatedAt}.
     *
     * <p>Always call this setter (never set the field directly) to ensure
     * {@code updated_at} is kept in sync with every status transition.
     *
     * @param v {@link com.cts.outward.enums.BatchStatus#db()} value
     */
    public void setStatus(String v) {
        this.status    = v;
        this.updatedAt = LocalDateTime.now();  // auto-audit on every status change
    }

    /** @return username of the Maker who created this batch */
    public String getCreatedBy()            { return createdBy; }
    public void   setCreatedBy(String v)    { this.createdBy = v; }

    /** @return timestamp of initial row INSERT; never changed after creation */
    public LocalDateTime getCreatedAt()             { return createdAt; }
    public void          setCreatedAt(LocalDateTime v){ this.createdAt = v; }

    /**
     * Returns the timestamp of the most recent modification.
     * Auto-updated by {@link #setStatus(String)} and by
     * {@code BatchDAOImpl.updateBatchActualCounts()}.
     *
     * @return last modification timestamp
     */
    public LocalDateTime getUpdatedAt()             { return updatedAt; }
    public void          setUpdatedAt(LocalDateTime v){ this.updatedAt = v; }
}