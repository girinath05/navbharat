package com.cts.outward.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for one batch row in the Outward Reports page.
 * Used by all four tabs (CXF, CIBF, Batch Summary).
 * Maps directly from cts_batches native-SQL results — no Hibernate entity needed.
 */
public class ReportBatchDTO {

    /** Unique identifier of the batch. */
    private String        batchId;
    /** Branch code of the batch. */
    private String        branchCode;
    /** Current database status of the batch. */
    private String        status;
    /** Total number of cheques in the batch. */
    private int           totalCheques;
    /** Total monetary amount of all cheques in the batch. */
    private BigDecimal    totalAmount;
    /** Name of the generated CXF file. */
    private String        cxfFileName;
    /** Name of the generated CIBF file. */
    private String        cibfFileName;
    /** Timestamp when files were generated. */
    private LocalDateTime generatedAt;
    /** Timestamp when the batch was created. */
    private LocalDateTime createdAt;
    /** User ID of the batch creator. */
    private String        createdBy;

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

    /**
     * Gets the total number of cheques.
     *
     * @return the total number of cheques
     */
    public int getTotalCheques() { 
        return totalCheques; 
    }

    /**
     * Sets the total number of cheques.
     *
     * @param totalCheques the total number of cheques to set
     */
    public void setTotalCheques(int totalCheques) { 
        this.totalCheques = totalCheques; 
    }

    /**
     * Gets the total monetary amount.
     *
     * @return the total monetary amount
     */
    public BigDecimal getTotalAmount() { 
        return totalAmount; 
    }

    /**
     * Sets the total monetary amount.
     *
     * @param totalAmount the total monetary amount to set
     */
    public void setTotalAmount(BigDecimal totalAmount) { 
        this.totalAmount = totalAmount; 
    }

    /**
     * Gets the CXF file name.
     *
     * @return the CXF file name
     */
    public String getCxfFileName() { 
        return cxfFileName; 
    }

    /**
     * Sets the CXF file name.
     *
     * @param cxfFileName the CXF file name to set
     */
    public void setCxfFileName(String cxfFileName) { 
        this.cxfFileName = cxfFileName; 
    }

    /**
     * Gets the CIBF file name.
     *
     * @return the CIBF file name
     */
    public String getCibfFileName() { 
        return cibfFileName; 
    }

    /**
     * Sets the CIBF file name.
     *
     * @param cibfFileName the CIBF file name to set
     */
    public void setCibfFileName(String cibfFileName) { 
        this.cibfFileName = cibfFileName; 
    }

    /**
     * Gets the generation timestamp.
     *
     * @return the generation timestamp
     */
    public LocalDateTime getGeneratedAt() { 
        return generatedAt; 
    }

    /**
     * Sets the generation timestamp.
     *
     * @param generatedAt the generation timestamp to set
     */
    public void setGeneratedAt(LocalDateTime generatedAt) { 
        this.generatedAt = generatedAt; 
    }

    /**
     * Gets the creation timestamp.
     *
     * @return the creation timestamp
     */
    public LocalDateTime getCreatedAt() { 
        return createdAt; 
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation timestamp to set
     */
    public void setCreatedAt(LocalDateTime createdAt) { 
        this.createdAt = createdAt; 
    }

    /**
     * Gets the creator user ID.
     *
     * @return the creator user ID
     */
    public String getCreatedBy() { 
        return createdBy; 
    }

    /**
     * Sets the creator user ID.
     *
     * @param createdBy the creator user ID to set
     */
    public void setCreatedBy(String createdBy) { 
        this.createdBy = createdBy; 
    }
}