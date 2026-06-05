/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchEntity.java
 *  Package     : com.cts.outward.entity
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Hibernate entity mapped to the cts_batches
 *                table. Holds batch header data: batchId,
 *                branchCode, totalCheques, totalAmount,
 *                controlAmount, batchType, status, and audit
 *                timestamps (createdAt / updatedAt).
 * ============================================================
 */

package com.cts.outward.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================ BatchEntity —
 * cts_batches table (Supabase PostgreSQL)
 *
 * FIXES vs previous version: 1. Added controlAmount field → getControlAmount()
 * / setControlAmount() 2. Added batchType field → getBatchType() /
 * setBatchType()
 *
 * These are called by BatchChequeEntryComposer: controlAmount(batch) →
 * batch.getControlAmount() batchType(batch) → batch.getBatchType()
 * batch.setBatchType() in onCreateBatch()
 *
 * DB columns added (run migration if needed): ALTER TABLE cts_batches ADD
 * COLUMN IF NOT EXISTS control_amount NUMERIC(15,2); ALTER TABLE cts_batches
 * ADD COLUMN IF NOT EXISTS batch_type VARCHAR(30);
 * ============================================================
 */
@Entity
@Table(name = "cts_batches")
public class BatchEntity {

	@Id
	@Column(name = "batch_id", length = 20)
	private String batchId;

	@Column(name = "branch_code", length = 10)
	private String branchCode;

	@Column(name = "total_cheques")
	private int totalCheques;

	@Column(name = "total_amount", precision = 15, scale = 2)
	private BigDecimal totalAmount;

	// FIX 1: Added — used as "Control Amt" column in batch listing grid
	@Column(name = "control_amount", precision = 15, scale = 2)
	private BigDecimal controlAmount;

	@Column(name = "expected_cheques")
	private int expectedCheques;

	@Column(name = "expected_amount", precision = 15, scale = 2)
	private BigDecimal expectedAmount;

	// FIX 2: Added — used as "Type" chip in batch listing grid (e.g. "Retail",
	// "Loan")
	@Column(name = "batch_type", length = 30)
	private String batchType;

	@Column(name = "status", length = 30)
	private String status;

	@Column(name = "created_by", length = 50)
	private String createdBy;

	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now();

	@Column(name = "updated_at")
	private LocalDateTime updatedAt = LocalDateTime.now();

	// ── Getters & Setters ──────────────────────────────────

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String v) {
		this.batchId = v;
	}

	public String getBranchCode() {
		return branchCode;
	}

	public void setBranchCode(String v) {
		this.branchCode = v;
	}

	public int getTotalCheques() {
		return totalCheques;
	}

	public void setTotalCheques(int v) {
		this.totalCheques = v;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public void setTotalAmount(BigDecimal v) {
		this.totalAmount = v;
	}

	// FIX 1: getter/setter for controlAmount
	public BigDecimal getControlAmount() {
		return controlAmount;
	}

	public void setControlAmount(BigDecimal v) {
		this.controlAmount = v;
	}

	public int getExpectedCheques() {
		return expectedCheques;
	}

	public void setExpectedCheques(int v) {
		this.expectedCheques = v;
	}

	public BigDecimal getExpectedAmount() {
		return expectedAmount;
	}

	public void setExpectedAmount(BigDecimal v) {
		this.expectedAmount = v;
	}

	// FIX 2: getter/setter for batchType
	public String getBatchType() {
		return batchType;
	}

	public void setBatchType(String v) {
		this.batchType = v;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String v) {
		this.status = v;
		this.updatedAt = LocalDateTime.now();
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String v) {
		this.createdBy = v;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime v) {
		this.createdAt = v;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime v) {
		this.updatedAt = v;
	}
}