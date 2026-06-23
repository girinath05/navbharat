package com.cts.outward.dto;

import java.math.BigDecimal;

/**
 * Data Transfer Object for one cheque row needed for CXF/CIBF generation.
 * Maps from cts_cheques (real schema).
 */
public class CxfChequeDTO {

    private Long       id;
    private String     batchId;
    private String     chequeNo;
    private String     chequeId;       // instrument serial / ItemSeqNo
    private String     accountNo;
    private BigDecimal amount;
    private String     chequeDate;
    private String     drawerName;
    private String     payeeName;
    private String     payeeAccountNo;
    private String     sortCode;       // drawee bank routing (PayorBankRoutNo)
    private String     baseNo;         // presenting bank routing (PresentingBankRoutNo)
    private String     transactionCode;
    private String     iqaStatus;
    private byte[]     frontImage;     // bytea from DB
    private byte[]     rearImage;      // bytea from DB

    public Long getId()                            { return id; }
    public void setId(Long v)                      { this.id = v; }

    public String getBatchId()                     { return batchId; }
    public void setBatchId(String v)               { this.batchId = v; }

    public String getChequeNo()                    { return chequeNo; }
    public void setChequeNo(String v)              { this.chequeNo = v; }

    public String getChequeId()                    { return chequeId; }
    public void setChequeId(String v)              { this.chequeId = v; }

    public String getAccountNo()                   { return accountNo; }
    public void setAccountNo(String v)             { this.accountNo = v; }

    public BigDecimal getAmount()                  { return amount; }
    public void setAmount(BigDecimal v)            { this.amount = v; }

    public String getChequeDate()                  { return chequeDate; }
    public void setChequeDate(String v)            { this.chequeDate = v; }

    public String getDrawerName()                  { return drawerName; }
    public void setDrawerName(String v)            { this.drawerName = v; }

    public String getPayeeName()                   { return payeeName; }
    public void setPayeeName(String v)             { this.payeeName = v; }

    public String getPayeeAccountNo()              { return payeeAccountNo; }
    public void setPayeeAccountNo(String v)        { this.payeeAccountNo = v; }

    public String getSortCode()                    { return sortCode; }
    public void setSortCode(String v)              { this.sortCode = v; }

    public String getBaseNo()                      { return baseNo; }
    public void setBaseNo(String v)                { this.baseNo = v; }

    public String getTransactionCode()             { return transactionCode; }
    public void setTransactionCode(String v)       { this.transactionCode = v; }

    public String getIqaStatus()                   { return iqaStatus; }
    public void setIqaStatus(String v)             { this.iqaStatus = v; }

    public byte[] getFrontImage()                  { return frontImage; }
    public void setFrontImage(byte[] v)            { this.frontImage = v; }

    public byte[] getRearImage()                   { return rearImage; }
    public void setRearImage(byte[] v)             { this.rearImage = v; }
}
