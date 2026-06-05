/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeEntity.java
 *  Package     : com.cts.outward.entity
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Hibernate entity mapped to the cts_cheques
 *                table. Stores MICR fields, amount, account
 *                number, high-value flag, verStatus,
 *                and front/back image BLOBs. FK batchId links
 *                to BatchEntity; BLOB columns excluded from
 *                projection queries for list-view performance.
 * ============================================================
 */

package com.cts.outward.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================ ChequeEntity —
 * cts_cheques table (Supabase PostgreSQL)
 *
 * ROOT CAUSE OF BLANK IMAGES (and the fix):
 * ───────────────────────────────────────── Supabase SQL confirms: front_magic
 * = ffd8ffe0 (valid JPEG), front_bytes = 109587. Data IS in the DB. Servlet IS
 * deployed. Yet getFrontImage() returns null or 0 bytes.
 *
 * WHY: Hibernate 6 without an explicit PostgreSQL dialect maps byte[] with
 * columnDefinition="BYTEA" to MaterializedBlobType — which reads data via
 * PostgreSQL's large-object API (OID references). But the column is plain
 * BYTEA, not an OID column. Hibernate sends a getLargeObject() call, PostgreSQL
 * returns nothing (no matching OID row), getFrontImage() returns empty byte[].
 *
 * FIX:
 * 
 * @JdbcTypeCode(SqlTypes.BINARY) on both image fields. This tells Hibernate 6
 *                                to treat the column as raw binary — reads via
 *                                ResultSet.getBytes() instead of
 *                                getLargeObject(). ResultSet.getBytes() on a
 *                                BYTEA column returns the actual bytes.
 *
 *                                No schema change needed. No data migration
 *                                needed. Only the Java annotation changes.
 *
 *                                EXISTING FIXES RETAINED: - @Lob removed
 *                                (avoids bigint OID insert error) - Projection
 *                                constructor (17 params) for
 *                                loadChequesForBatch() - transactionCode field
 *                                + getTc() alias
 *                                ============================================================
 */
@Entity
@Table(name = "cts_cheques", indexes = { @Index(name = "idx_cheque_batch_id", columnList = "batch_id"),
		@Index(name = "idx_cheque_cheque_no", columnList = "cheque_no"),
		@Index(name = "idx_cheque_account_no", columnList = "account_no") })
public class ChequeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "batch_id", length = 20, nullable = false)
	private String batchId;

	@Column(name = "cheque_id", length = 20)
	private String chequeId;

	@Column(name = "cheque_no", length = 20)
	private String chequeNo;

	@Column(name = "account_no", length = 25)
	private String accountNo;

	@Column(name = "sort_code", length = 15)
	private String sortCode;

	@Column(name = "transaction_code", length = 10)
	private String transactionCode;

	@Column(name = "amount", precision = 15, scale = 2)
	private BigDecimal amount;

	@Column(name = "cheque_date", length = 12)
	private String chequeDate;

	@Column(name = "drawer_name", length = 100)
	private String drawerName;

	@Column(name = "payee_name", length = 100)
	private String payeeName;

	@Column(name = "iqa_status", length = 10)
	private String iqaStatus;

	@Column(name = "ver_status", length = 20)
	private String verStatus;

	@Column(name = "status", length = 20)
	private String status;

	@Column(name = "high_value")
	private boolean highValue;

	@Column(name = "duplicate_flag")
	private boolean duplicate;

	// ── IMAGE FIELDS — THE CRITICAL FIX ───────────────────────────────────
	//
	// @JdbcTypeCode(SqlTypes.BINARY) forces Hibernate 6 to use
	// ResultSet.getBytes() when reading this column — which correctly
	// returns the raw BYTEA bytes from PostgreSQL.
	//
	// WITHOUT this annotation: Hibernate 6 uses MaterializedBlobType →
	// calls getLargeObject() → OID lookup → returns null (no OID row)
	// → getFrontImage() / getRearImage() return null → blank images.
	//
	// WITH this annotation: Hibernate uses BinaryJdbcType →
	// calls getBytes() → returns the 109587-byte JPEG directly.
	//
	// @Lob is intentionally absent (removed in previous fix to prevent
	// the "column front_image is of type bytea but expression is of type
	// bigint" INSERT error on SQLState 42804).

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(name = "front_image", columnDefinition = "BYTEA")
	private byte[] frontImage;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(name = "rear_image", columnDefinition = "BYTEA")
	private byte[] rearImage;

	// ── ─────────────────────────────────────────────────────────────────────

	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now();

	@Column(name = "updated_at")
	private LocalDateTime updatedAt = LocalDateTime.now();

	// ── Default constructor — required by Hibernate ────────────────────────

	public ChequeEntity() {
	}

	/**
	 * Projection constructor — 17 params, excludes front_image and rear_image.
	 *
	 * Used by ChequeDAO.loadChequesForBatch() HQL: SELECT new
	 * com.cts.entity.ChequeEntity( c.id, c.batchId, c.chequeId, c.chequeNo,
	 * c.accountNo, c.sortCode, c.amount, c.chequeDate, c.drawerName, c.payeeName,
	 * c.iqaStatus, c.verStatus, c.status, c.highValue, c.duplicate, c.createdAt,
	 * c.updatedAt ) FROM ChequeEntity c WHERE c.batchId = :batchId ORDER BY c.id
	 *
	 * Call ChequeDAO.loadChequeWithImages(id) to get the full entity including
	 * images when the user clicks a specific cheque row.
	 */
	public ChequeEntity(Long id, String batchId, String chequeId, String chequeNo, String accountNo, String sortCode,
			BigDecimal amount, String chequeDate, String drawerName, String payeeName, String iqaStatus,
			String verStatus, String status, boolean highValue, boolean duplicate, LocalDateTime createdAt,
			LocalDateTime updatedAt) {
		this.id = id;
		this.batchId = batchId;
		this.chequeId = chequeId;
		this.chequeNo = chequeNo;
		this.accountNo = accountNo;
		this.sortCode = sortCode;
		this.amount = amount;
		this.chequeDate = chequeDate;
		this.drawerName = drawerName;
		this.payeeName = payeeName;
		this.iqaStatus = iqaStatus;
		this.verStatus = verStatus;
		this.status = status;
		this.highValue = highValue;
		this.duplicate = duplicate;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		// frontImage, rearImage, transactionCode intentionally not set
	}

	// ── Getters & Setters ──────────────────────────────────────────────────

	public Long getId() {
		return id;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String v) {
		this.batchId = v;
	}

	public String getChequeId() {
		return chequeId;
	}

	public void setChequeId(String v) {
		this.chequeId = v;
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
	}

	public String getSortCode() {
		return sortCode;
	}

	public void setSortCode(String v) {
		this.sortCode = v;
	}

	public String getTransactionCode() {
		return transactionCode;
	}

	public void setTransactionCode(String v) {
		this.transactionCode = v;
	}

	/** Alias for getTransactionCode() — used as fallback in composer. */
	public String getTc() {
		return transactionCode;
	}

	public void setTc(String v) {
		this.transactionCode = v;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal v) {
		this.amount = v;
	}

	public String getChequeDate() {
		return chequeDate;
	}

	public void setChequeDate(String v) {
		this.chequeDate = v;
	}

	public String getDrawerName() {
		return drawerName;
	}

	public void setDrawerName(String v) {
		this.drawerName = v;
	}

	public String getPayeeName() {
		return payeeName;
	}

	public void setPayeeName(String v) {
		this.payeeName = v;
	}

	public String getIqaStatus() {
		return iqaStatus;
	}

	public void setIqaStatus(String v) {
		this.iqaStatus = v;
	}

	public String getVerStatus() {
		return verStatus;
	}

	public void setVerStatus(String v) {
		this.verStatus = v;
		this.updatedAt = LocalDateTime.now();
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String v) {
		this.status = v;
		this.updatedAt = LocalDateTime.now();
	}

	public boolean isHighValue() {
		return highValue;
	}

	public void setHighValue(boolean v) {
		this.highValue = v;
	}

	public boolean isDuplicate() {
		return duplicate;
	}

	public void setDuplicate(boolean v) {
		this.duplicate = v;
	}

	public byte[] getFrontImage() {
		return frontImage;
	}

	public void setFrontImage(byte[] v) {
		this.frontImage = v;
	}

	public byte[] getRearImage() {
		return rearImage;
	}

	public void setRearImage(byte[] v) {
		this.rearImage = v;
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

	public boolean hasFrontImage() {
		return frontImage != null && frontImage.length > 0;
	}

	public boolean hasRearImage() {
		return rearImage != null && rearImage.length > 0;
	}
}
