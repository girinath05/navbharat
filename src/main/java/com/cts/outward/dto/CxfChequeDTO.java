package com.cts.outward.dto;

import java.math.BigDecimal;

/**
 * Data Transfer Object for one cheque row needed for CXF/CIBF generation.
 * Maps from cts_cheques (real schema).
 */
public class CxfChequeDTO {

    /** Unique identifier of the cheque record. */
    private Long       id;
    /** Unique identifier of the batch. */
    private String     batchId;
    /** Cheque number. */
    private String     chequeNo;
    /** Instrument serial number or ItemSeqNo. */
    private String     chequeId;
    /** Account number of the drawer. */
    private String     accountNo;
    /** Monetary amount of the cheque. */
    private BigDecimal amount;
    /** Date on the cheque. */
    private String     chequeDate;
    /** Name of the drawer. */
    private String     drawerName;
    /** Name of the payee. */
    private String     payeeName;
    /** Account number of the payee. */
    private String     payeeAccountNo;
    /** Drawee bank routing sort code. */
    private String     sortCode;
    /** Presenting bank routing base number. */
    private String     baseNo;
    /** Transaction code. */
    private String     transactionCode;
    /** Image Quality Analysis (IQA) status. */
    private String     iqaStatus;
    /** Front image binary content. */
    private byte[]     frontImage;
    /** Rear image binary content. */
    private byte[]     rearImage;

    /**
     * Gets the cheque database record ID.
     *
     * @return the record ID
     */
    public Long getId() { 
        return id; 
    }

    /**
     * Sets the cheque database record ID.
     *
     * @param id the record ID to set
     */
    public void setId(Long id) { 
        this.id = id; 
    }

    /**
     * Gets the batch ID.
     *
     * @return the batch ID
     */
    public String getBatchId() { 
        return batchId; 
    }

    /**
     * Sets the batch ID.
     *
     * @param batchId the batch ID to set
     */
    public void setBatchId(String batchId) { 
        this.batchId = batchId; 
    }

    /**
     * Gets the cheque number.
     *
     * @return the cheque number
     */
    public String getChequeNo() { 
        return chequeNo; 
    }

    /**
     * Sets the cheque number.
     *
     * @param chequeNo the cheque number to set
     */
    public void setChequeNo(String chequeNo) { 
        this.chequeNo = chequeNo; 
    }

    /**
     * Gets the cheque ID (instrument serial).
     *
     * @return the cheque ID
     */
    public String getChequeId() { 
        return chequeId; 
    }

    /**
     * Sets the cheque ID (instrument serial).
     *
     * @param chequeId the cheque ID to set
     */
    public void setChequeId(String chequeId) { 
        this.chequeId = chequeId; 
    }

    /**
     * Gets the account number.
     *
     * @return the account number
     */
    public String getAccountNo() { 
        return accountNo; 
    }

    /**
     * Sets the account number.
     *
     * @param accountNo the account number to set
     */
    public void setAccountNo(String accountNo) { 
        this.accountNo = accountNo; 
    }

    /**
     * Gets the cheque amount.
     *
     * @return the cheque amount
     */
    public BigDecimal getAmount() { 
        return amount; 
    }

    /**
     * Sets the cheque amount.
     *
     * @param amount the cheque amount to set
     */
    public void setAmount(BigDecimal amount) { 
        this.amount = amount; 
    }

    /**
     * Gets the cheque date string.
     *
     * @return the cheque date
     */
    public String getChequeDate() { 
        return chequeDate; 
    }

    /**
     * Sets the cheque date string.
     *
     * @param chequeDate the cheque date to set
     */
    public void setChequeDate(String chequeDate) { 
        this.chequeDate = chequeDate; 
    }

    /**
     * Gets the drawer name.
     *
     * @return the drawer name
     */
    public String getDrawerName() { 
        return drawerName; 
    }

    /**
     * Sets the drawer name.
     *
     * @param drawerName the drawer name to set
     */
    public void setDrawerName(String drawerName) { 
        this.drawerName = drawerName; 
    }

    /**
     * Gets the payee name.
     *
     * @return the payee name
     */
    public String getPayeeName() { 
        return payeeName; 
    }

    /**
     * Sets the payee name.
     *
     * @param payeeName the payee name to set
     */
    public void setPayeeName(String payeeName) { 
        this.payeeName = payeeName; 
    }

    /**
     * Gets the payee account number.
     *
     * @return the payee account number
     */
    public String getPayeeAccountNo() { 
        return payeeAccountNo; 
    }

    /**
     * Sets the payee account number.
     *
     * @param payeeAccountNo the payee account number to set
     */
    public void setPayeeAccountNo(String payeeAccountNo) { 
        this.payeeAccountNo = payeeAccountNo; 
    }

    /**
     * Gets the sort code (drawee bank routing).
     *
     * @return the sort code
     */
    public String getSortCode() { 
        return sortCode; 
    }

    /**
     * Sets the sort code (drawee bank routing).
     *
     * @param sortCode the sort code to set
     */
    public void setSortCode(String sortCode) { 
        this.sortCode = sortCode; 
    }

    /**
     * Gets the base number (presenting bank routing).
     *
     * @return the base number
     */
    public String getBaseNo() { 
        return baseNo; 
    }

    /**
     * Sets the base number (presenting bank routing).
     *
     * @param baseNo the base number to set
     */
    public void setBaseNo(String baseNo) { 
        this.baseNo = baseNo; 
    }

    /**
     * Gets the transaction code.
     *
     * @return the transaction code
     */
    public String getTransactionCode() { 
        return transactionCode; 
    }

    /**
     * Sets the transaction code.
     *
     * @param transactionCode the transaction code to set
     */
    public void setTransactionCode(String transactionCode) { 
        this.transactionCode = transactionCode; 
    }

    /**
     * Gets the Image Quality Analysis status.
     *
     * @return the IQA status
     */
    public String getIqaStatus() { 
        return iqaStatus; 
    }

    /**
     * Sets the Image Quality Analysis status.
     *
     * @param iqaStatus the IQA status to set
     */
    public void setIqaStatus(String iqaStatus) { 
        this.iqaStatus = iqaStatus; 
    }

    /**
     * Gets the front image byte array.
     *
     * @return the front image bytes
     */
    public byte[] getFrontImage() { 
        return frontImage; 
    }

    /**
     * Sets the front image byte array.
     *
     * @param frontImage the front image bytes to set
     */
    public void setFrontImage(byte[] frontImage) { 
        this.frontImage = frontImage; 
    }

    /**
     * Gets the rear image byte array.
     *
     * @return the rear image bytes
     */
    public byte[] getRearImage() { 
        return rearImage; 
    }

    /**
     * Sets the rear image byte array.
     *
     * @param rearImage the rear image bytes to set
     */
    public void setRearImage(byte[] rearImage) { 
        this.rearImage = rearImage; 
    }
}