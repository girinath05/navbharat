/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchModel.java
 *  Package     : com.cts.outward.model
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : UI-layer transfer object for batch data.
 *                Decouples the ZK composer from the Hibernate
 *                entity; populated from ZIP/XML parse results
 *                before being converted to BatchEntity for
 *                persistence.
 * ============================================================
 */

package com.cts.outward.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * ClearPay CTS — BatchModel
 * Represents one ZIP-uploaded batch of cheques.
 * Mapped to Firestore collection: cts_batches
 * ============================================================
 */
public class BatchModel {

    // ── Identity ─────────────────────────────────────────────
    private String batchId;           // e.g. BATCH0001
    private String branchCode;        // e.g. MUM01
    private String presentingBankId;  // CTS bank code

    // ── Counts ───────────────────────────────────────────────
    private int expectedCheques;
    private int totalCheques;

    /**
     * Number of cheques already submitted/processed in this batch.
     * Populated by BatchServiceImpl.getBatchesFiltered() via a
     * cheque-level count query (COUNT where status = 'Submitted').
     */
    private int submittedCheques;

    /**
     * Number of cheques still pending (not yet submitted/processed).
     * Populated by BatchServiceImpl.getBatchesFiltered() via a
     * cheque-level count query (COUNT where status NOT IN processed states).
     * Derived as: totalCheques - submittedCheques  OR  direct DB count.
     */
    private int pendingCheques;

    // ── Amounts ──────────────────────────────────────────────
    private BigDecimal totalAmount    = BigDecimal.ZERO;
    private BigDecimal expectedAmount = BigDecimal.ZERO;

    // ── Status ───────────────────────────────────────────────
    // Created → Submitted → CXF_Generated → Exported → Settled
    private String status = "Created";

    // ── Timestamps ───────────────────────────────────────────
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Firebase ─────────────────────────────────────────────
    private String zipStorageUrl;     // Firebase Storage URL of ZIP

    // ── Children ─────────────────────────────────────────────
    private List<ChequeModel> cheques = new ArrayList<>();

    // ─────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────

    public BatchModel() {}

    public BatchModel(String batchId, String branchCode) {
        this.batchId     = batchId;
        this.branchCode  = branchCode;
    }

    // ─────────────────────────────────────────────────────────
    // Derived helpers
    // ─────────────────────────────────────────────────────────

    /** Recalculate totalAmount and totalCheques from children. */
    public void recalculate() {
        this.totalCheques = cheques.size();
        this.totalAmount  = cheques.stream()
                .map(ChequeModel::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.updatedAt    = LocalDateTime.now();
    }

    public boolean isBalanced() {
        return expectedAmount != null
                && totalAmount != null
                && totalAmount.compareTo(expectedAmount) == 0;
    }

    // ─────────────────────────────────────────────────────────
    // Getters & Setters
    // ─────────────────────────────────────────────────────────

    public String getBatchId()              { return batchId; }
    public void   setBatchId(String v)      { this.batchId = v; }

    public String getBranchCode()           { return branchCode; }
    public void   setBranchCode(String v)   { this.branchCode = v; }

    public String getPresentingBankId()           { return presentingBankId; }
    public void   setPresentingBankId(String v)   { this.presentingBankId = v; }

    public int  getExpectedCheques()        { return expectedCheques; }
    public void setExpectedCheques(int v)   { this.expectedCheques = v; }

    public int  getTotalCheques()           { return totalCheques; }
    public void setTotalCheques(int v)      { this.totalCheques = v; }

    public int  getSubmittedCheques()       { return submittedCheques; }
    public void setSubmittedCheques(int v)  { this.submittedCheques = v; }

    public int  getPendingCheques()         { return pendingCheques; }
    public void setPendingCheques(int v)    { this.pendingCheques = v; }

    public BigDecimal getTotalAmount()              { return totalAmount; }
    public void       setTotalAmount(BigDecimal v)  { this.totalAmount = v; }

    public BigDecimal getExpectedAmount()             { return expectedAmount; }
    public void       setExpectedAmount(BigDecimal v) { this.expectedAmount = v; }

    public String getStatus()           { return status; }
    public void   setStatus(String v)   { this.status = v; this.updatedAt = LocalDateTime.now(); }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void          setCreatedAt(LocalDateTime v) { this.createdAt = v; }

    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void          setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }

    public String getZipStorageUrl()            { return zipStorageUrl; }
    public void   setZipStorageUrl(String v)    { this.zipStorageUrl = v; }

    public List<ChequeModel> getCheques()               { return cheques; }
    public void              setCheques(List<ChequeModel> v) { this.cheques = v; }

    @Override
    public String toString() {
        return "BatchModel{batchId='" + batchId + "', branch='" + branchCode
                + "', cheques=" + totalCheques + ", submitted=" + submittedCheques
                + ", pending=" + pendingCheques + ", amount=" + totalAmount
                + ", status='" + status + "'}";
    }
}