package com.cts.outward.dto;

import java.math.BigDecimal;

/**
 * DTO for one cheque row in the Cheque-level Report tab.
 * Maps from cts_cheques JOIN cts_batches — no bytea columns (images not needed for reports).
 */
public class ReportChequeDTO {

    /** Unique cheque number. */
    private String     chequeNo;
    /** Unique batch identifier. */
    private String     batchId;
    /** Branch code of the batch. */
    private String     branchCode;
    /** Account number of the drawer. */
    private String     accountNo;
    /** Monetary amount of the cheque. */
    private BigDecimal amount;
    /** Date string on the cheque. */
    private String     chequeDate;
    /** Drawee bank routing sort code. */
    private String     draweeBankCode;
    /** Image Quality Analysis (IQA) status. */
    private String     iqaStatus;
    /** Current status of the batch. */
    private String     batchStatus;
    /** Name of the drawer. */
    private String     drawerName;
    /** Name of the payee. */
    private String     payeeName;
    /** Verification status of the cheque. */
    private String     verStatus;
    /** Database status. */
    private String     status;

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
     * Gets the branch code.
     *
     * @return the branch code
     */
    public String getBranchCode() { 
        return branchCode; 
    }

    /**
     * Sets the branch code.
     *
     * @param branchCode the branch code to set
     */
    public void setBranchCode(String branchCode) { 
        this.branchCode = branchCode; 
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
     * Gets the drawee bank routing code.
     *
     * @return the drawee bank code
     */
    public String getDraweeBankCode() { 
        return draweeBankCode; 
    }

    /**
     * Sets the drawee bank routing code.
     *
     * @param draweeBankCode the drawee bank code to set
     */
    public void setDraweeBankCode(String draweeBankCode) { 
        this.draweeBankCode = draweeBankCode; 
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
     * Gets the status of the batch.
     *
     * @return the batch status
     */
    public String getBatchStatus() { 
        return batchStatus; 
    }

    /**
     * Sets the status of the batch.
     *
     * @param batchStatus the batch status to set
     */
    public void setBatchStatus(String batchStatus) { 
        this.batchStatus = batchStatus; 
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
     * Gets the verification status of the cheque.
     *
     * @return the verification status
     */
    public String getVerStatus() { 
        return verStatus; 
    }

    /**
     * Sets the verification status of the cheque.
     *
     * @param verStatus the verification status to set
     */
    public void setVerStatus(String verStatus) { 
        this.verStatus = verStatus; 
    }

    /**
     * Gets the status.
     *
     * @return the status
     */
    public String getStatus() { 
        return status; 
    }

    /**
     * Sets the status.
     *
     * @param status the status to set
     */
    public void setStatus(String status) { 
        this.status = status; 
    }
}