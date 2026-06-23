package com.cts.outward.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * View model for one batch row on the CXF-CIBF Generation page.
 * Maps directly from cts_batches columns (real schema).
 *
 * cts_batches columns used here:
 *   batch_id       varchar  PK
 *   branch_code    varchar
 *   status         varchar
 *   total_cheques  int4
 *   total_amount   numeric
 *   cxf_file_name  varchar  (added via migration SQL)
 *   cibf_file_name varchar  (added via migration SQL)
 *   generated_at   timestamp(added via migration SQL)
 *   sent_at        timestamp(added via migration SQL)
 *   ack_at         timestamp(added via migration SQL)
 *   created_at     timestamp
 */
public class CxfBatchModel {

    private String        batchId;
    private String        branchCode;
    private String        status;
    private int           totalCheques;
    private BigDecimal    totalAmount;

    private String        cxfFileName;
    private String        cibfFileName;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;

    /** Human-readable reason shown in the Pending table */
    private String        statusReason;

    public String getBatchId()                     { return batchId; }
    public void setBatchId(String v)               { this.batchId = v; }

    public String getBranchCode()                  { return branchCode; }
    public void setBranchCode(String v)            { this.branchCode = v; }

    public String getStatus()                      { return status; }
    public void setStatus(String v)                { this.status = v; }

    public int getTotalCheques()                   { return totalCheques; }
    public void setTotalCheques(int v)             { this.totalCheques = v; }

    public BigDecimal getTotalAmount()             { return totalAmount; }
    public void setTotalAmount(BigDecimal v)       { this.totalAmount = v; }

    public String getCxfFileName()                 { return cxfFileName; }
    public void setCxfFileName(String v)           { this.cxfFileName = v; }

    public String getCibfFileName()                { return cibfFileName; }
    public void setCibfFileName(String v)          { this.cibfFileName = v; }

    public LocalDateTime getGeneratedAt()          { return generatedAt; }
    public void setGeneratedAt(LocalDateTime v)    { this.generatedAt = v; }

    public LocalDateTime getCreatedAt()            { return createdAt; }
    public void setCreatedAt(LocalDateTime v)      { this.createdAt = v; }

    public String getStatusReason()                { return statusReason; }
    public void setStatusReason(String v)          { this.statusReason = v; }
}
