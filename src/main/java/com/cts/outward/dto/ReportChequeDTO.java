package com.cts.outward.dto;

import java.math.BigDecimal;

/**
 * DTO for one cheque row in the Cheque-level Report tab.
 * Maps from cts_cheques JOIN cts_batches — no bytea columns (images not needed for reports).
 */
public class ReportChequeDTO {

    private String     chequeNo;
    private String     batchId;
    private String     branchCode;
    private String     accountNo;
    private BigDecimal amount;
    private String     chequeDate;
    private String     draweeBankCode;   // cts_cheques.sort_code
    private String     iqaStatus;
    private String     batchStatus;
    private String     drawerName;
    private String     payeeName;
    private String     verStatus;
    private String     status;

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public String getChequeNo()                    { return chequeNo; }
    public void   setChequeNo(String v)            { this.chequeNo = v; }

    public String getBatchId()                     { return batchId; }
    public void   setBatchId(String v)             { this.batchId = v; }

    public String getBranchCode()                  { return branchCode; }
    public void   setBranchCode(String v)          { this.branchCode = v; }

    public String getAccountNo()                   { return accountNo; }
    public void   setAccountNo(String v)           { this.accountNo = v; }

    public BigDecimal getAmount()                  { return amount; }
    public void       setAmount(BigDecimal v)      { this.amount = v; }

    public String getChequeDate()                  { return chequeDate; }
    public void   setChequeDate(String v)          { this.chequeDate = v; }

    public String getDraweeBankCode()              { return draweeBankCode; }
    public void   setDraweeBankCode(String v)      { this.draweeBankCode = v; }

    public String getIqaStatus()                   { return iqaStatus; }
    public void   setIqaStatus(String v)           { this.iqaStatus = v; }

    public String getBatchStatus()                 { return batchStatus; }
    public void   setBatchStatus(String v)         { this.batchStatus = v; }

    public String getDrawerName()                  { return drawerName; }
    public void   setDrawerName(String v)          { this.drawerName = v; }

    public String getPayeeName()                   { return payeeName; }
    public void   setPayeeName(String v)           { this.payeeName = v; }

    public String getVerStatus()                   { return verStatus; }
    public void   setVerStatus(String v)           { this.verStatus = v; }

    public String getStatus()                      { return status; }
    public void   setStatus(String v)              { this.status = v; }
}
