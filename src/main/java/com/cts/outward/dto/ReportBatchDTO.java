package com.cts.outward.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for one batch row in the Outward Reports page.
 * Used by all four tabs (CXF, CIBF, Batch Summary).
 * Maps directly from cts_batches native-SQL results — no Hibernate entity needed.
 */
public class ReportBatchDTO {

    private String        batchId;
    private String        branchCode;
    private String        status;
    private int           totalCheques;
    private BigDecimal    totalAmount;
    private String        cxfFileName;
    private String        cibfFileName;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
    private String        createdBy;

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public String getBatchId()                     { return batchId; }
    public void   setBatchId(String v)             { this.batchId = v; }

    public String getBranchCode()                  { return branchCode; }
    public void   setBranchCode(String v)          { this.branchCode = v; }

    public String getStatus()                      { return status; }
    public void   setStatus(String v)              { this.status = v; }

    public int    getTotalCheques()                { return totalCheques; }
    public void   setTotalCheques(int v)           { this.totalCheques = v; }

    public BigDecimal getTotalAmount()             { return totalAmount; }
    public void       setTotalAmount(BigDecimal v) { this.totalAmount = v; }

    public String getCxfFileName()                 { return cxfFileName; }
    public void   setCxfFileName(String v)         { this.cxfFileName = v; }

    public String getCibfFileName()                { return cibfFileName; }
    public void   setCibfFileName(String v)        { this.cibfFileName = v; }

    public LocalDateTime getGeneratedAt()          { return generatedAt; }
    public void          setGeneratedAt(LocalDateTime v) { this.generatedAt = v; }

    public LocalDateTime getCreatedAt()            { return createdAt; }
    public void          setCreatedAt(LocalDateTime v)  { this.createdAt = v; }

    public String getCreatedBy()                  { return createdBy; }
    public void   setCreatedBy(String v)          { this.createdBy = v; }
}
