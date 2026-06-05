/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeModel.java
 *  Package     : com.cts.outward.model
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : UI-layer transfer object for individual cheque
 *                data. Holds parsed MICR fields, image byte
 *                arrays,and amount. Converted to
 *                ChequeEntity before saving; keeps raw bytes
 *                out of the entity until flush time.
 * ============================================================
 */

package com.cts.outward.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================ ClearPay CTS —
 * ChequeModel Represents one instrument (cheque) inside a batch. Mapped to
 * Firestore collection: cts_cheques
 * ============================================================
 */
public class ChequeModel {

	// ── Identity ─────────────────────────────────────────────
	private String id; // UUID or composite key
	private String batchId; // Parent batch
	private String chequeNo; // Instrument number (6–7 digits)
	private String accountNo; // Payee account number

	// ── MICR Fields ──────────────────────────────────────────
	private String sortCode; // 9-digit MICR sort code (cityBankBranch)
	private String cityCode; // 3 digits
	private String bankCode; // 3 digits
	private String branchCode; // 3 digits
	private String transactionCode; // TC (2 digits)

	// ── Instrument Data ──────────────────────────────────────
	private BigDecimal amount;
	private String drawerBank;
	private String drawerBranch;
	private String chequeDate;

	// ── IQA / Quality ────────────────────────────────────────
	private String iqaStatus; // Pass | Fail
	private String iqaReason; // why if Fail

	// ── Verification ─────────────────────────────────────────
	private String verStatus; // Pending | Verified | Rejected | Referred
	private String ver1By;
	private String ver2By;
	private boolean highValue; // amount >= 10,00,000 (1 million)
	private boolean duplicate;
	private boolean hni;

	// ── Status ───────────────────────────────────────────────
	// Ready | MICR_Repair | Verified | Rejected | CXF | Exported
	private String status = "Ready";

	// ── Firebase Storage URLs ────────────────────────────────
	private String frontImageUrl;
	private String rearImageUrl;

	// ── Raw image bytes (transient — not stored in Firestore) ─
	private transient byte[] frontImageBytes;
	private transient byte[] rearImageBytes;

	// ── Timestamps ───────────────────────────────────────────
	private LocalDateTime createdAt = LocalDateTime.now();
	private LocalDateTime updatedAt = LocalDateTime.now();

	// ─────────────────────────────────────────────────────────
	// Constructors
	// ─────────────────────────────────────────────────────────

	public ChequeModel() {
	}

	// ─────────────────────────────────────────────────────────
	// Derived helpers
	// ─────────────────────────────────────────────────────────

	/** True if amount is ≥ ₹10,00,000 — triggers Verification II */
	public boolean isHighValue() {
		return amount != null && amount.compareTo(new BigDecimal("1000000")) >= 0;
	}

	/** Formatted MICR strip: chequeNo : sortCode : accountNo */
	public String getMicrStrip() {
		return chequeNo + " : " + sortCode + " : " + accountNo;
	}

	/** Update MICR sort code from city/bank/branch components */
	public void rebuildSortCode() {
		if (cityCode != null && bankCode != null && branchCode != null) {
			this.sortCode = cityCode + bankCode + branchCode;
		}
	}

	// ─────────────────────────────────────────────────────────
	// Getters & Setters
	// ─────────────────────────────────────────────────────────

	public String getId() {
		return id;
	}

	public void setId(String v) {
		this.id = v;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String v) {
		this.batchId = v;
	}

	public String getChequeNo() {
		return chequeNo;
	}

	public void setChequeNo(String v) {
		this.chequeNo = v;
	}

	public String getAccountNo() {
		return accountNo;
	}

	public void setAccountNo(String v) {
		this.accountNo = v;
		this.updatedAt = LocalDateTime.now();
	}

	public String getSortCode() {
		return sortCode;
	}

	public void setSortCode(String v) {
		this.sortCode = v;
	}

	public String getCityCode() {
		return cityCode;
	}

	public void setCityCode(String v) {
		this.cityCode = v;
		rebuildSortCode();
	}

	public String getBankCode() {
		return bankCode;
	}

	public void setBankCode(String v) {
		this.bankCode = v;
		rebuildSortCode();
	}

	public String getBranchCode() {
		return branchCode;
	}

	public void setBranchCode(String v) {
		this.branchCode = v;
		rebuildSortCode();
	}

	public String getTransactionCode() {
		return transactionCode;
	}

	public void setTransactionCode(String v) {
		this.transactionCode = v;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal v) {
		this.amount = v;
		this.highValue = isHighValue();
		this.updatedAt = LocalDateTime.now();
	}

	public String getDrawerBank() {
		return drawerBank;
	}

	public void setDrawerBank(String v) {
		this.drawerBank = v;
	}

	public String getDrawerBranch() {
		return drawerBranch;
	}

	public void setDrawerBranch(String v) {
		this.drawerBranch = v;
	}

	public String getChequeDate() {
		return chequeDate;
	}

	public void setChequeDate(String v) {
		this.chequeDate = v;
	}

	public String getIqaStatus() {
		return iqaStatus;
	}

	public void setIqaStatus(String v) {
		this.iqaStatus = v;
	}

	public String getIqaReason() {
		return iqaReason;
	}

	public void setIqaReason(String v) {
		this.iqaReason = v;
	}

	public String getVerStatus() {
		return verStatus;
	}

	public void setVerStatus(String v) {
		this.verStatus = v;
		this.updatedAt = LocalDateTime.now();
	}

	public String getVer1By() {
		return ver1By;
	}

	public void setVer1By(String v) {
		this.ver1By = v;
	}

	public String getVer2By() {
		return ver2By;
	}

	public void setVer2By(String v) {
		this.ver2By = v;
	}

	public boolean isDuplicate() {
		return duplicate;
	}

	public void setDuplicate(boolean v) {
		this.duplicate = v;
	}

	public boolean isHni() {
		return hni;
	}

	public void setHni(boolean v) {
		this.hni = v;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String v) {
		this.status = v;
		this.updatedAt = LocalDateTime.now();
	}

	public String getFrontImageUrl() {
		return frontImageUrl;
	}

	public void setFrontImageUrl(String v) {
		this.frontImageUrl = v;
	}

	public String getRearImageUrl() {
		return rearImageUrl;
	}

	public void setRearImageUrl(String v) {
		this.rearImageUrl = v;
	}

	public byte[] getFrontImageBytes() {
		return frontImageBytes;
	}

	public void setFrontImageBytes(byte[] v) {
		this.frontImageBytes = v;
	}

	public byte[] getRearImageBytes() {
		return rearImageBytes;
	}

	public void setRearImageBytes(byte[] v) {
		this.rearImageBytes = v;
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

	@Override
	public String toString() {
		return "ChequeModel{chequeNo='" + chequeNo + "', acct='" + accountNo + "', amount=" + amount + ", iqaStatus='"
				+ iqaStatus + "', status='" + status + "'}";
	}
}
