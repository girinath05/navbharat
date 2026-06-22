/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Entity Layer
 *  File        : ChequeEntity.java
 *  Package     : com.cts.outward.entity
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Hibernate JPA entity mapping the PostgreSQL table cts_cheques.
 *  One row = one physical cheque instrument within an outward
 *  clearing batch.
 *
 *  The entity carries:
 *    - MICR-line data extracted from the cheque XML by ZipParser
 *    - Front and rear TIFF/JPEG images stored as PostgreSQL BYTEA
 *    - Verification workflow fields updated by Verifier composers
 *    - Audit flags (highValue, duplicate, amountWordsMismatch, referred)
 *
 * ──────────────────────────────────────────────────────────────
 *  DB TABLE  :  cts_cheques  (Supabase PostgreSQL)
 * ──────────────────────────────────────────────────────────────
 *  Column                 Type               Notes
 *  ─────────────────────  ─────────────────  ──────────────────────────────────
 *  id                     BIGSERIAL PK       DB-generated surrogate key
 *  batch_id               VARCHAR(20) FK     → cts_batches.batch_id  NOT NULL
 *  cheque_id              VARCHAR(20)        Vendor's internal cheque reference
 *  cheque_no              VARCHAR(20)        MICR cheque serial number
 *  account_no             VARCHAR(25)        Drawee account number
 *  sort_code              VARCHAR(15)        Full MICR sort code (bank+branch)
 *  transaction_code       VARCHAR(10)        TC — last token of MICRLine (RBI)
 *  base_no                VARCHAR(20)        3rd MICR token (drawee account at drawee bank)
 *  amount                 NUMERIC(15,2)      Face value in INR
 *  amount_in_words        VARCHAR(200)       Amount in words from XML
 *  amount_words_mismatch  BOOLEAN            true if digits ≠ words (blocks Save)
 *  cheque_date            VARCHAR(12)        Date on cheque face (DD-MM-YYYY or raw)
 *  drawer_name            VARCHAR(100)       Name of account holder issuing cheque
 *  payee_name             VARCHAR(100)       Name of payee to whom cheque is drawn
 *  payee_account_no       VARCHAR(30)        Payee's account (OCR'd from rear endorsement)
 *  iqa_status             VARCHAR(10)        Image Quality Assessment: Pass / Fail
 *  ver_status             VARCHAR(20)        Verification status (ChequeStatus enum)
 *  ver_level              VARCHAR(20)        Which verification level last acted
 *  ver_action             VARCHAR(20)        Accept / Reject / Hold
 *  ver_by                 VARCHAR(50)        Username of last Verifier
 *  ver_remarks            VARCHAR(255)       Verifier's comment/reason
 *  status                 VARCHAR(20)        Cheque workflow status (ChequeStatus enum)
 *  high_value             BOOLEAN            true if amount ≥ high-value threshold
 *  duplicate_flag         BOOLEAN            true if duplicate detected at import
 *  is_referred            BOOLEAN            true if cheque referred for further review (default false)
 *  front_image            BYTEA              Front scan image (TIFF/JPEG)
 *  rear_image             BYTEA              Rear scan image (TIFF/JPEG)
 *  created_at             TIMESTAMP          Insert time
 *  updated_at             TIMESTAMP          Last status/field change time
 *
 * ──────────────────────────────────────────────────────────────
 *  INDEXES  (defined via @Table annotation)
 * ──────────────────────────────────────────────────────────────
 *  idx_cheque_batch_id    — batch_id  (all queries filter by batch)
 *  idx_cheque_cheque_no   — cheque_no (duplicate detection query)
 *  idx_cheque_account_no  — account_no (payee lookup / search)
 *
 * ──────────────────────────────────────────────────────────────
 *  MICR LINE FORMAT  (RBI CTS-2010)
 * ──────────────────────────────────────────────────────────────
 *  Space-separated tokens:
 *    Token 0 : ChequeNo     → chequeNo
 *    Token 1 : BankCode     (part of sortCode)
 *    Token 2 : BranchCode   → baseNo  (drawee account at drawee bank)
 *    Token 3 : TC           → transactionCode  (LAST token — always)
 *
 *  Example: "123456 110532005 20000123456789 29"
 *    chequeNo        = "123456"
 *    baseNo          = "20000123456789"
 *    transactionCode = "29"
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHO READS / WRITES THIS ENTITY
 * ──────────────────────────────────────────────────────────────
 *
 *  CREATED (full entity including BLOBs):
 *    ZipParser.parsePerChequeXml() / parseMasterXml()
 *      → populates all MICR fields + front/rear image bytes
 *    ZipImportServiceImpl.importZip()
 *      → ChequeDAOImpl.saveCheques(list)  — batch INSERT
 *
 *  UPDATED (MICR/amount fields only — no BLOBs):
 *    BatchDetailComposer.onSaveFields()
 *      → ChequeServiceImpl.saveChequeFields(entity)
 *        → ChequeDAOImpl.updateChequeFields(entity)
 *
 *  UPDATED (verification fields):
 *    VerificationOneComposer / VerificationTwoComposer
 *      → ChequeServiceImpl.updateVerification(entity)
 *        → ChequeDAOImpl.updateChequeVerification(entity)
 *
 *  READ (without BLOBs — projection constructor):
 *    ChequeDAOImpl.loadChequesForBatch(batchId)
 *      → HQL SELECT new ChequeEntity(id, batchId, ...) — 17-param projection
 *      → Used by BatchDetailComposer to populate grid rows
 *
 *  READ (with BLOBs — full load):
 *    ChequeImageServlet.doGet()
 *      → ChequeDAOImpl.getChequeById(id)
 *      → streams frontImage or rearImage bytes to browser
 *
 * ──────────────────────────────────────────────────────────────
 *  CHANGES (Jun 2026)
 * ──────────────────────────────────────────────────────────────
 *  + amountInWords       — XML field for amount-in-words mismatch detection
 *  + amountWordsMismatch — set true when digits ≠ words; blocks Save in UI
 *  + baseNo              — 3rd MICR token; required for RBI CTS-2010 compliance
 *  + referred             — maps to is_referred; true if cheque referred for review
 *                            (column already exists in DB; NOT yet wired into the
 *                            17-param projection constructor — see that constructor's
 *                            Javadoc if you need it in grid-listing queries)
 * ============================================================
 */

package com.cts.outward.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * JPA entity for the {@code cts_cheques} table.
 *
 * <p>
 * Represents one physical cheque instrument within an outward clearing batch.
 * Populated from ZIP-parsed XML during import; updated by verifier composers
 * during the verification workflow.
 *
 * <h3>Two constructors</h3>
 * <ul>
 * <li><b>No-arg</b> — used by Hibernate for full loads (with BLOB images) and
 * by {@code ZipParser} when building entities from scratch.</li>
 * <li><b>17-param projection</b> — used by HQL queries that SELECT specific
 * columns without loading the BYTEA image columns for grid rendering. See
 * constructor Javadoc for why {@code verLevel} is excluded.</li>
 * </ul>
 *
 * @author Umesh M.
 * @see com.cts.outward.enums.ChequeStatus
 * @see com.cts.outward.dao.ChequeDAO
 * @see com.cts.outward.service.ChequeService
 */
@Entity
@Table(name = "cts_cheques", indexes = { @Index(name = "idx_cheque_batch_id", columnList = "batch_id"), // all batch
																										// queries
		@Index(name = "idx_cheque_cheque_no", columnList = "cheque_no"), // duplicate detection
		@Index(name = "idx_cheque_account_no", columnList = "account_no") // payee search
})
public class ChequeEntity {

	// ══════════════════════════════════════════════════════════════════
	// PRIMARY KEY
	// ══════════════════════════════════════════════════════════════════

	/**
	 * DB-generated surrogate primary key (BIGSERIAL / IDENTITY). Referenced by
	 * {@code ChequeImageServlet} to serve front/rear images:
	 * {@code GET /chequeImage?id=123&side=front}
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// ══════════════════════════════════════════════════════════════════
	// BATCH RELATIONSHIP
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Foreign key to {@code cts_batches.batch_id}. NOT NULL — every cheque must
	 * belong to a batch. Set by {@code ZipImportServiceImpl} after the batch row is
	 * created or identified.
	 */
	@Column(name = "batch_id", length = 20, nullable = false)
	private String batchId;

	// ══════════════════════════════════════════════════════════════════
	// CHEQUE IDENTITY FIELDS (from XML / MICR line)
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Vendor-assigned internal cheque reference from the ZIP XML (tag:
	 * {@code <ChequeId>} or {@code <ChequeID>}). Used as a folder-name fallback in
	 * Structure A ZIPs.
	 */
	@Column(name = "cheque_id", length = 20)
	private String chequeId;

	/**
	 * MICR cheque serial number — first token of the MICR line (tag:
	 * {@code <ChequeNumber>} / {@code <ChequeNo>} / {@code <InstrNo>}). Used for
	 * duplicate detection in {@code ChequeDAOImpl.findExistingChequeNos()}.
	 * Indexed: {@code idx_cheque_cheque_no}.
	 */
	@Column(name = "cheque_no", length = 20)
	private String chequeNo;

	/**
	 * Drawee account number of the account holder issuing the cheque (tag:
	 * {@code <AccountNumber>} / {@code <AccountNo>} / {@code <AcctNo>}). Indexed:
	 * {@code idx_cheque_account_no}.
	 */
	@Column(name = "account_no", length = 25)
	private String accountNo;

	/**
	 * Full MICR sort code combining bank and branch codes (tag: {@code <SortCode>}
	 * / {@code <MICRCode>} / {@code <MicrCode>}). Format per RBI CTS-2010: 9-digit
	 * numeric (3 city + 3 bank + 3 branch).
	 */
	@Column(name = "sort_code", length = 15)
	private String sortCode;

	/**
	 * Transaction Code — the <b>last whitespace-separated token</b> of the MICR
	 * line, per RBI CTS-2010 specification.
	 *
	 * <p>
	 * MICR line format: {@code "<ChequeNo> <BankCode> <BaseNo> <TC>"} Example:
	 * {@code "123456 110532005 20000123456789 29"} → TC = {@code "29"}
	 *
	 * <p>
	 * Aliases tried (vendor variations): {@code TC}, {@code TransactionCode},
	 * {@code TxCode}. Set by {@code ZipParser} after MICR line token split.
	 *
	 * <p>
	 * Also accessible via the legacy alias getter {@link #getTc()}.
	 */
	@Column(name = "transaction_code", length = 10)
	private String transactionCode;

	/**
	 * Base Number — the 3rd whitespace-separated token of the MICR line. Represents
	 * the drawee account number at the drawee bank.
	 *
	 * <p>
	 * MICR example: {@code "123456 110532005 20000123456789 29"} ^^^^^^^^^^^^^^
	 * this
	 *
	 * <p>
	 * Required field for RBI CTS-2010 compliance. Set by {@code ZipParser} during
	 * MICR line parsing.
	 */
	@Column(name = "base_no", length = 20)
	private String baseNo;

	// ══════════════════════════════════════════════════════════════════
	// AMOUNT FIELDS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Face value of the cheque in INR (tag: {@code <Amount>} / {@code <Amt>}).
	 * Parsed as {@link BigDecimal} with 15-digit precision and 2 decimal places.
	 * May be null if the XML contained an unparseable or missing amount.
	 */
	@Column(name = "amount", precision = 15, scale = 2)
	private BigDecimal amount;

	/**
	 * Amount expressed in words as printed on the cheque face (tag:
	 * {@code <AmountInWords>} / {@code <AmtInWords>} / {@code <AmountWords>}).
	 *
	 * <p>
	 * Used during import for mismatch detection:
	 * {@code AmountToWords.convert(amount)} is compared against this field. If they
	 * differ, {@link #amountWordsMismatch} is set to {@code true} and the Mismatch
	 * popup blocks Save until resolved.
	 */
	@Column(name = "amount_in_words", length = 200)
	private String amountInWords;

	/**
	 * Flag set to {@code true} when the numeric {@link #amount} (digits) does not
	 * match the {@link #amountInWords} field after conversion via
	 * {@code AmountToWords.convert()}.
	 *
	 * <p>
	 * Read by {@code BatchDetailComposer}: if {@code true}, the Amount Mismatch
	 * popup is shown and the Save button is disabled until the Maker resolves the
	 * discrepancy (accept the digits value or correct the entry).
	 */
	@Column(name = "amount_words_mismatch")
	private boolean amountWordsMismatch;

	// ══════════════════════════════════════════════════════════════════
	// CHEQUE DETAIL FIELDS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Date printed on the cheque face (tag: {@code <ChequeDate>} / {@code <Date>}).
	 * Stored as a string to preserve the original format from the vendor XML (e.g.
	 * {@code "15-05-2024"} or {@code "20240515"}) without imposing a parse format
	 * that might reject valid vendor variations.
	 */
	@Column(name = "cheque_date", length = 12)
	private String chequeDate;

	/**
	 * Name of the account holder who issued the cheque (drawer) (tag:
	 * {@code <DrawerName>} / {@code <Drawer>} / {@code <DrawerBank>}).
	 */
	@Column(name = "drawer_name", length = 100)
	private String drawerName;

	/**
	 * Name of the payee to whom the cheque is drawn (tag: {@code <PayeeName>} /
	 * {@code <Payee>}).
	 */
	@Column(name = "payee_name", length = 100)
	private String payeeName;

	/**
	 * Payee's crediting account number, OCR'd from the rear endorsement of the
	 * cheque. Stored separately from {@link #accountNo} (which is the drawee's
	 * account) to preserve both values for reconciliation.
	 *
	 * <p>
	 * Populated by {@code BatchDetailComposer.validatePayeeAccount()} when the
	 * Maker manually enters or confirms the payee account.
	 */
	@Column(name = "payee_account_no", length = 30)
	private String payeeAccountNo;

	// ══════════════════════════════════════════════════════════════════
	// IMAGE QUALITY
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Image Quality Assessment result from the scanning software (tag:
	 * {@code <IQA>} / {@code <IqaStatus>}). Typical values: {@code "Pass"} /
	 * {@code "Fail"}. Defaults to {@code "Pass"} if absent from the XML.
	 *
	 * <p>
	 * Cheques with IQA = Fail may be flagged for manual review by Verifiers.
	 */
	@Column(name = "iqa_status", length = 10)
	private String iqaStatus;

	// ══════════════════════════════════════════════════════════════════
	// VERIFICATION WORKFLOW FIELDS
	// Updated by VerificationOneComposer / VerificationTwoComposer
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Verification status — tracks where the cheque is in the verification
	 * workflow. Set to {@link com.cts.outward.enums.ChequeStatus#PENDING PENDING}
	 * at import; updated by Verifier composers.
	 *
	 * <p>
	 * {@link #setVerStatus(String)} auto-updates {@link #updatedAt}.
	 */
	@Column(name = "ver_status", length = 20)
	private String verStatus;

	/**
	 * Verification level that last acted on this cheque. Example values:
	 * {@code "L1"} (Verification I), {@code "L2"} (Verification II).
	 *
	 * <p>
	 * <b>Critical note on projection constructor:</b> This field is intentionally
	 * excluded from the 17-param projection constructor used by HQL grid queries.
	 * Those callers load {@code verLevel} separately via
	 * {@code loadChequesByVerLevel()} and set it via {@link #setVerLevel(String)}.
	 * See the 17-param constructor Javadoc for the full reasoning.
	 */
	@Column(name = "ver_level", length = 20)
	private String verLevel;

	/**
	 * Action taken by the last Verifier. Example values: {@code "Accept"},
	 * {@code "Reject"}, {@code "Hold"}.
	 */
	@Column(name = "ver_action", length = 20)
	private String verAction;

	/**
	 * Username of the Verifier who last updated this cheque's verification state.
	 * Sourced from the ZK session attribute at the time of the verifier action.
	 */
	@Column(name = "ver_by", length = 50)
	private String verBy;

	/**
	 * Free-text remarks entered by the Verifier when rejecting or holding a cheque.
	 * Displayed in the Verification detail panel.
	 */
	@Column(name = "ver_remarks", length = 255)
	private String verRemarks;

	// ══════════════════════════════════════════════════════════════════
	// WORKFLOW STATUS AND FLAGS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Overall cheque workflow status. Stores the {@code .db()} value from
	 * {@link com.cts.outward.enums.ChequeStatus}.
	 *
	 * <p>
	 * {@link #setStatus(String)} auto-updates {@link #updatedAt}.
	 */
	@Column(name = "status", length = 20)
	private String status;

	/**
	 * {@code true} when the cheque amount meets or exceeds the high-value threshold
	 * defined by RBI (currently ₹1,00,000). High-value cheques may require
	 * additional verification steps or separate reporting.
	 */
	@Column(name = "high_value")
	private boolean highValue;

	/**
	 * {@code true} when this cheque's number was already present in
	 * {@code cts_cheques} at import time. Duplicates are excluded from batch INSERT
	 * but flagged for audit. See {@code ChequeDAOImpl.findExistingChequeNos()}.
	 */
	@Column(name = "duplicate_flag")
	private boolean duplicate;

	/**
	 * {@code true} when this cheque has been referred for further review (e.g.
	 * flagged by a Maker/Verifier for supervisor attention before it can proceed
	 * through the normal workflow). Maps to the {@code is_referred} column, which
	 * defaults to {@code false} at the DB level.
	 *
	 * <p>
	 * <b>Not yet included</b> in the 17-param projection constructor below — add
	 * it there (and to the corresponding HQL SELECT projections in
	 * {@code ChequeDAOImpl}) if grid-listing queries need to display this flag.
	 */
	@Column(name = "is_referred")
	private boolean referred;

	// ══════════════════════════════════════════════════════════════════
	// IMAGE COLUMNS (BYTEA — excluded from projection queries)
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Raw bytes of the front-face cheque scan image (TIFF or JPEG). Stored as
	 * PostgreSQL {@code BYTEA}. Can be several hundred KB per cheque.
	 *
	 * <p>
	 * <b>NOT loaded</b> by the 17-param projection constructor — loading BLOBs for
	 * every row in a grid query would cause severe memory pressure. Loaded on
	 * demand by {@code ChequeImageServlet.doGet()} for display.
	 *
	 * <p>
	 * Use {@link #hasFrontImage()} to check presence before serving.
	 */
	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(name = "front_image", columnDefinition = "BYTEA")
	private byte[] frontImage;

	/**
	 * Raw bytes of the rear-face cheque scan image (TIFF or JPEG). Same storage and
	 * access pattern as {@link #frontImage}.
	 *
	 * <p>
	 * Use {@link #hasRearImage()} to check presence before serving.
	 */
	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(name = "rear_image", columnDefinition = "BYTEA")
	private byte[] rearImage;

	// ══════════════════════════════════════════════════════════════════
	// AUDIT TIMESTAMPS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Server timestamp when this cheque row was inserted. Defaults to
	 * {@code LocalDateTime.now()} at object construction. Never changed after
	 * initial INSERT.
	 */
	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now();

	/**
	 * Server timestamp of the most recent modification. Auto-updated by
	 * {@link #setStatus(String)} and {@link #setVerStatus(String)} on every status
	 * change. Also set explicitly by DAOs after field updates.
	 */
	@Column(name = "updated_at")
	private LocalDateTime updatedAt = LocalDateTime.now();

	// ══════════════════════════════════════════════════════════════════
	// CONSTRUCTORS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * No-arg constructor required by Hibernate for full entity loads (including
	 * BLOB columns). Also used by {@code ZipParser} when building new entities from
	 * parsed XML before persisting.
	 */
	public ChequeEntity() {
	}

	/**
	 * Projection constructor for HQL queries that load cheque metadata
	 * <b>without</b> the BYTEA image columns (for grid rendering performance).
	 *
	 * <p>
	 * Used by: {@code ChequeDAOImpl.loadChequesForBatch(batchId)},
	 * {@code loadChequesByVerLevel()}, and similar listing queries.
	 *
	 * <p>
	 * <b>Why {@code verLevel} is excluded from this constructor:</b><br>
	 * This 17-param signature is embedded in multiple HQL SELECT projections.
	 * Adding {@code verLevel} to the constructor would require updating every HQL
	 * query string simultaneously. Instead, callers that need {@code verLevel} load
	 * it via a separate targeted query and call {@link #setVerLevel(String)}.
	 * {@code ChequeServiceImpl.saveChequeFields()} avoids passing stale entities to
	 * {@code updateChequeFields()} to prevent {@code ver_level} being overwritten
	 * with null.
	 *
	 * <p>
	 * <b>Note:</b> {@code referred} (is_referred) is likewise NOT included here yet,
	 * for the same reason — adding it would require touching every existing HQL
	 * projection string. If grid listings need to show the referred flag, add a
	 * {@code boolean referred} parameter here AND update each HQL SELECT new
	 * ChequeEntity(...) projection in {@code ChequeDAOImpl} to match.
	 *
	 * @param id         DB surrogate key
	 * @param batchId    parent batch ID (FK)
	 * @param chequeId   vendor internal reference
	 * @param chequeNo   MICR serial number
	 * @param accountNo  drawee account number
	 * @param sortCode   full MICR sort code
	 * @param amount     face value in INR
	 * @param chequeDate date on cheque face
	 * @param drawerName account holder name
	 * @param payeeName  payee name
	 * @param iqaStatus  IQA Pass/Fail
	 * @param verStatus  verification lifecycle status
	 * @param status     overall workflow status
	 * @param highValue  true if amount ≥ high-value threshold
	 * @param duplicate  true if duplicate at import time
	 * @param createdAt  insert timestamp
	 * @param updatedAt  last-modified timestamp
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
		// verLevel intentionally omitted — see Javadoc above
		// referred intentionally omitted (for now) — see Javadoc above
	}

	// ══════════════════════════════════════════════════════════════════
	// GETTERS AND SETTERS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * @return DB-generated surrogate PK; used by ChequeImageServlet for image
	 *         lookup
	 */
	public Long getId() {
		return id;
	}

	/** @return parent batch ID (FK → cts_batches.batch_id) */
	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String v) {
		this.batchId = v;
	}

	/** @return vendor-assigned internal cheque reference from ZIP XML */
	public String getChequeId() {
		return chequeId;
	}

	public void setChequeId(String v) {
		this.chequeId = v;
	}

	/** @return MICR cheque serial number; used for duplicate detection */
	public String getChequeNo() {
		return chequeNo;
	}

	public void setChequeNo(String v) {
		this.chequeNo = v;
	}

	/** @return drawee account number (account that the cheque is drawn on) */
	public String getAccountNo() {
		return accountNo;
	}

	public void setAccountNo(String v) {
		this.accountNo = v;
	}

	/** @return full MICR sort code (9-digit: city + bank + branch) */
	public String getSortCode() {
		return sortCode;
	}

	public void setSortCode(String v) {
		this.sortCode = v;
	}

	/**
	 * Returns the Transaction Code — last token of the MICR line (RBI CTS-2010).
	 *
	 * @return TC string (e.g. {@code "29"}); may be null if MICR line was missing
	 */
	public String getTransactionCode() {
		return transactionCode;
	}

	public void setTransactionCode(String v) {
		this.transactionCode = v;
	}

	/**
	 * Legacy alias for {@link #getTransactionCode()} — provided for backward
	 * compatibility with older composer code that used {@code getTc()}.
	 *
	 * @return same value as {@link #getTransactionCode()}
	 */
	public String getTc() {
		return transactionCode;
	}

	public void setTc(String v) {
		this.transactionCode = v;
	}

	/**
	 * Returns the Base Number — 3rd MICR token (drawee account at drawee bank).
	 * Required for RBI CTS-2010 compliance.
	 *
	 * @return base number string; may be null if MICR line had fewer than 3 tokens
	 */
	public String getBaseNo() {
		return baseNo;
	}

	public void setBaseNo(String v) {
		this.baseNo = v;
	}

	/** @return face value in INR; null if XML amount was missing or unparseable */
	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal v) {
		this.amount = v;
	}

	/** @return amount in words as printed on cheque; used for mismatch detection */
	public String getAmountInWords() {
		return amountInWords;
	}

	public void setAmountInWords(String v) {
		this.amountInWords = v;
	}

	/**
	 * Returns whether the digits amount and words amount disagree. When
	 * {@code true}, the Amount Mismatch popup in {@code BatchDetailComposer} blocks
	 * Save until the Maker resolves the discrepancy.
	 *
	 * @return {@code true} if digits ≠ words
	 */
	public boolean isAmountWordsMismatch() {
		return amountWordsMismatch;
	}

	public void setAmountWordsMismatch(boolean v) {
		this.amountWordsMismatch = v;
	}

	/** @return date printed on cheque face; stored as-is from vendor XML */
	public String getChequeDate() {
		return chequeDate;
	}

	public void setChequeDate(String v) {
		this.chequeDate = v;
	}

	/** @return name of the account holder issuing the cheque (drawer) */
	public String getDrawerName() {
		return drawerName;
	}

	public void setDrawerName(String v) {
		this.drawerName = v;
	}

	/** @return name of the payee on the cheque face */
	public String getPayeeName() {
		return payeeName;
	}

	public void setPayeeName(String v) {
		this.payeeName = v;
	}

	/** @return payee's credit account number from rear endorsement; may be null */
	public String getPayeeAccountNo() {
		return payeeAccountNo;
	}

	public void setPayeeAccountNo(String v) {
		this.payeeAccountNo = v;
	}

	/**
	 * @return IQA result from scanning software: {@code "Pass"} or {@code "Fail"}
	 */
	public String getIqaStatus() {
		return iqaStatus;
	}

	public void setIqaStatus(String v) {
		this.iqaStatus = v;
	}

	/**
	 * @return verification lifecycle status
	 *         ({@link com.cts.outward.enums.ChequeStatus} db value)
	 */
	public String getVerStatus() {
		return verStatus;
	}

	/**
	 * Sets the verification status and auto-refreshes {@link #updatedAt}. Always
	 * call this setter (never set field directly) for consistent audit.
	 *
	 * @param v {@link com.cts.outward.enums.ChequeStatus#db()} value
	 */
	public void setVerStatus(String v) {
		this.verStatus = v;
		this.updatedAt = LocalDateTime.now();
	}

	/**
	 * Returns the verification level that last acted on this cheque. Note: not
	 * populated by the 17-param projection constructor — see class Javadoc.
	 *
	 * @return verification level string (e.g. {@code "L1"}, {@code "L2"}); may be
	 *         null
	 */
	public String getVerLevel() {
		return verLevel;
	}

	public void setVerLevel(String v) {
		this.verLevel = v;
	}

	/**
	 * @return action taken by last Verifier: {@code "Accept"} / {@code "Reject"} /
	 *         {@code "Hold"}
	 */
	public String getVerAction() {
		return verAction;
	}

	public void setVerAction(String v) {
		this.verAction = v;
	}

	/** @return username of the last Verifier who acted on this cheque */
	public String getVerBy() {
		return verBy;
	}

	public void setVerBy(String v) {
		this.verBy = v;
	}

	/** @return free-text remarks from the Verifier (rejection reason, notes) */
	public String getVerRemarks() {
		return verRemarks;
	}

	public void setVerRemarks(String v) {
		this.verRemarks = v;
	}

	/**
	 * @return overall workflow status ({@link com.cts.outward.enums.ChequeStatus}
	 *         db value)
	 */
	public String getStatus() {
		return status;
	}

	/**
	 * Sets the overall workflow status and auto-refreshes {@link #updatedAt}.
	 * Always call this setter for consistent audit trail.
	 *
	 * @param v {@link com.cts.outward.enums.ChequeStatus#db()} value
	 */
	public void setStatus(String v) {
		this.status = v;
		this.updatedAt = LocalDateTime.now();
	}

	/** @return true if amount ≥ high-value threshold (currently ₹1,00,000) */
	public boolean isHighValue() {
		return highValue;
	}

	public void setHighValue(boolean v) {
		this.highValue = v;
	}

	/** @return true if this cheque was a duplicate at import time */
	public boolean isDuplicate() {
		return duplicate;
	}

	public void setDuplicate(boolean v) {
		this.duplicate = v;
	}

	/**
	 * Returns whether this cheque has been referred for further review (maps to
	 * the {@code is_referred} column).
	 *
	 * @return {@code true} if the cheque is currently flagged as referred
	 */
	public boolean isReferred() {
		return referred;
	}

	public void setReferred(boolean v) {
		this.referred = v;
	}

	/**
	 * Returns the raw front-image bytes (TIFF/JPEG) of the cheque. NOT loaded by
	 * projection queries — use {@code ChequeImageServlet} for display.
	 *
	 * @return image bytes; {@code null} if not loaded (projection query) or not
	 *         present
	 */
	public byte[] getFrontImage() {
		return frontImage;
	}

	public void setFrontImage(byte[] v) {
		this.frontImage = v;
	}

	/**
	 * Returns the raw rear-image bytes (TIFF/JPEG) of the cheque. Same loading
	 * behaviour as {@link #getFrontImage()}.
	 *
	 * @return image bytes; {@code null} if not loaded or not present
	 */
	public byte[] getRearImage() {
		return rearImage;
	}

	public void setRearImage(byte[] v) {
		this.rearImage = v;
	}

	/** @return insert timestamp; never changed after row creation */
	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime v) {
		this.createdAt = v;
	}

	/** @return last-modified timestamp; auto-updated by status setters */
	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime v) {
		this.updatedAt = v;
	}

	// ══════════════════════════════════════════════════════════════════
	// CONVENIENCE PREDICATES
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Returns {@code true} if front image bytes are present and non-empty. Used by
	 * {@code ChequeImageServlet} and {@code BatchDetailComposer} to decide whether
	 * to render the image viewer or show a placeholder.
	 *
	 * @return {@code true} if front image is available
	 */
	public boolean hasFrontImage() {
		return frontImage != null && frontImage.length > 0;
	}

	/**
	 * Returns {@code true} if rear image bytes are present and non-empty.
	 *
	 * @return {@code true} if rear image is available
	 */
	public boolean hasRearImage() {
		return rearImage != null && rearImage.length > 0;
	}
}