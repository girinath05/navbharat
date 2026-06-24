/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Scan / Batch-Create Module
 *  File        : BatchChequeEntryComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  ZK SelectorComposer that powers the Scan Module screen
 *  (scanModule.zul). Responsibilities:
 *
 *  1. Display the Maker's batch list (Draft + Pending batches only).
 *  2. Allow creation of a new manual batch (Step 1 modal: enter
 *     expected cheque count and control amount).
 *  3. Accept a ZIP file upload (Step 2 scan modal) to import
 *     scanned cheque images and parse MICR data.
 *  4. Handle all import edge cases:
 *       a. All cheques already in system → "All Duplicates" dialog
 *       b. Cheque count mismatch → "Mismatch" dialog (Accept/Discard)
 *       c. Clean import → success toast
 *  5. Navigate to batch-detail.zul when a batch row is clicked.
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS CLASS FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  Browser (ZUL)                   Server (Composer)               DB / Parser
 *  ─────────────────               ──────────────────────────────  ──────────────────
 *  scanModule.zul ─load──►  BatchChequeEntryComposer               Supabase PostgreSQL
 *      Batch list                  ↕ BatchServiceImpl                 cts_batches
 *      Create modal                ↕ ChequeServiceImpl                cts_cheques
 *      Scan modal                  ↕ ZipImportServiceImpl
 *      Mismatch dialog               ↕ CtsZipParserImpl            ZIP/XML parsing
 *      Dup dialog                    ↕ ChequeDAOImpl                  findExistingNos
 *      Success toast
 *
 * ──────────────────────────────────────────────────────────────
 *  NAVIGATION ENTRY POINTS  (who calls this page)
 * ──────────────────────────────────────────────────────────────
 *  1. DashboardComposer.loadPage("/zul/outward/scanModule.zul")
 *       Called from the sidebar "Scan" menu item.
 *
 *  2. MyBatchesComposer.onCreateBatch()
 *       Sets session["autoOpenBatchModal"] = true
 *       → DashboardComposer.loadPage(".../scanModule.zul")
 *
 * ──────────────────────────────────────────────────────────────
 *  LIFECYCLE  (ZK call order)
 * ──────────────────────────────────────────────────────────────
 *  doAfterCompose(comp)
 *      1. isSessionValid()          → redirect to index.zul if no session
 *      2. loadUserInfo()            → populate header labels
 *      3. loadBatchesFromService()  → BatchService → DB → in-memory list
 *      4. renderBatches()           → build Listitem rows in lbBatches
 *      5. refreshStats()            → update stat card labels
 *      6. updateBatchCountLabel()   → update "N batches" badge
 *      7. if session["autoOpenBatchModal"] → openBatchModal()
 *
 * ──────────────────────────────────────────────────────────────
 *  TWO-PATH ZIP IMPORT FLOW
 * ──────────────────────────────────────────────────────────────
 *
 *  PATH A — Direct upload (no pre-created batch):
 *  ────────────────────────────────────────────────
 *  btnUploadZip.onUpload
 *    └─► onZipUpload(UploadEvent)
 *          → readAllBytes(media.getStreamData())
 *          → ZipImportServiceImpl.importZip(bytes, name, branch, user)
 *              → CtsZipParserImpl.parse()
 *              → ChequeDAOImpl.findExistingChequeNos()  (dedup)
 *              → BatchDAOImpl.saveBatch()
 *              → ChequeDAOImpl.saveCheques()
 *              → returns ImportResult
 *          → if allDuplicates → openDuplicateDialog()
 *          → else → showSuccessToast()
 *
 *  PATH B — Scan modal (manual batch created first):
 *  ──────────────────────────────────────────────────
 *  btnOpenBatchModal.onClick
 *    └─► openBatchModal()    [shows Step 1 modal]
 *
 *  btnCreateBatch.onClick
 *    └─► onCreateBatch()
 *          → BatchServiceImpl.createBatch(branch, count, amount, user)
 *              → BatchDAOImpl.saveBatch()  [status=Draft]
 *          → store pendingBatchId
 *          → openScanModal(batchId)        [shows Step 2 modal]
 *
 *  btnScanUploadZip.onUpload
 *    └─► onScanZipUpload(UploadEvent)
 *          → ZipImportServiceImpl.importZip(bytes, name, branch, user, pendingBatchId)
 *              → (same as Path A, but links cheques to existingBatchId)
 *          → Case 1: allDuplicates → discardPendingBatch() → openDuplicateDialog()
 *          → Case 2: count mismatch → save pendingMismatchResult → openMismatchDialog()
 *          → Case 3: success → finishSuccessfulImport()
 *
 *  btnMismatchAccept.onClick
 *    └─► onMismatchAccept()
 *          → finishSuccessfulImport(pendingMismatchResult)
 *
 *  btnMismatchDiscard.onClick
 *    └─► onMismatchDiscard()
 *          → BatchServiceImpl.discardBatch(pendingBatchId)
 *              → BatchDAOImpl.deleteBatchAndCheques()
 *
 * ──────────────────────────────────────────────────────────────
 *  KEY DESIGN DECISIONS
 * ──────────────────────────────────────────────────────────────
 *  • Pure ZK MVC — all overlay/modal visibility driven by setVisible().
 *    No JavaScript innerHTML rebuilds, no DOM manipulation.
 *  • pendingBatchId is the bridge between Step 1 (create) and Step 2 (scan).
 *    It is cleared on: successful import, discard, or scan modal close.
 *  • Status filter: "Pending" in UI = "VerificationInProgressAtMaker" in DB.
 *  • Ghost batch guard: hides VerificationInProgressAtMaker batches with
 *    zero amount and no cheques (leftover from aborted manual creates).
 * ============================================================
 */
package com.cts.outward.composer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Timer;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;
import com.cts.outward.service.ChequeService;
import com.cts.outward.service.ChequeServiceImpl;
import com.cts.outward.service.ImportResult;
import com.cts.outward.service.ZipImportService;
import com.cts.outward.service.ZipImportServiceImpl;

/**
 * ZK SelectorComposer for {@code scanModule.zul}.
 *
 * <p>
 * Manages the full batch creation and ZIP import workflow for the Maker role.
 * See class-level Javadoc for the complete two-path import flow.
 *
 * @author Umesh M.
 * @see ZipImportService
 * @see BatchService
 */
public class BatchChequeEntryComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;

	/** Rows shown per page in the batch table. */
	private static final int BATCH_PAGE_SIZE = 5;

	/** Logger — import errors, discard events, session issues. */
	private static final Logger LOG = Logger.getLogger(BatchChequeEntryComposer.class.getName());

	// ── Service layer (manual wiring — no DI framework) ──────────────────
	// BatchService: batch CRUD + submit validation.
	// ChequeService: cheque field save + pending count for stat card.
	// ZipImportService: orchestrates ZIP parse → dedup → DB persist.
	private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
	private final ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());
	private final ZipImportService zipImportService = new ZipImportServiceImpl();

	// ── Session attribute key constants ───────────────────────────────────
	// Set by LoginComposer on successful login; read here for user context
	// and for setting createdBy / branchCode on new batches.
	private static final String SESS_LOGGED_USER = "loggedUser";
	private static final String SESS_USER_NAME = "userName";
	private static final String SESS_USER_ROLE = "userRole";
	private static final String SESS_USER_BRANCH = "userBranch";

	// ── In-memory state ───────────────────────────────────────────────────

	/**
	 * Master batch list — loaded from DB at startup and refreshed after every
	 * import / create / discard operation. Filtered in-memory by
	 * getFilteredBatches().
	 */
	private final List<BatchEntity> batches = new ArrayList<>();

	/** Current page index (1-based) for the batch table pagination controls. */
	private int batchPage = 1;

	/**
	 * Batch ID of the Draft batch created in Step 1 (onCreateBatch), waiting for a
	 * ZIP upload in Step 2 (onScanZipUpload). Cleared on: successful import, user
	 * discards, scan modal closed without upload.
	 */
	private String pendingBatchId = null;

	/**
	 * Holds the ImportResult from onScanZipUpload() when the cheque count does not
	 * match the Maker's declared count. Kept in memory until the user clicks Accept
	 * (finishSuccessfulImport) or Discard (deleteBatchAndCheques).
	 */
	private ImportResult pendingMismatchResult = null;

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — HEADER + TOP-LEVEL BUTTONS
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Label lblHdrUser; // "UMESH M." in the page header
	@Wire
	private Label lblHdrRole; // "ADMINISTRATOR" in the page header
	@Wire
	private Button btnLogout; // Invalidate session → redirect to login
	@Wire
	private Button btnCreateBatch; // Submit the Create Batch modal (Step 1)
	@Wire
	private Button btnViewBatches; // Navigate to My Batches page
	@Wire
	private Button btnOpenBatchModal; // Open the Create Batch modal
	@Wire
	private Button btnCloseBatchModal; // Close modal via × button
	@Wire
	private Button btnCancelBatchModal; // Close modal via Cancel button
	@Wire
	private Div batchModal; // Create Batch modal overlay Div
	@Wire
	private Button btnCloseScanModal; // Close scan modal via × button
	@Wire
	private Button btnScanCancelDiscard; // Legacy discard fallback button

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — SCAN MODAL (Step 2)
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Div scanModal; // The scan modal overlay Div
	@Wire
	private Label scanBatchIdLabel; // Shows "BATCH0123" inside scan modal header
	@Wire
	private Div scanProgress; // Progress bar container (hidden by default)
	@Wire
	private Div scanProgressFill; // Progress bar fill element (width: 0–90%)
	@Wire
	private Label scanProgressText; // "Processing ZIP — please wait…"

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — MISMATCH DIALOG
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Div mismatchDialog; // Modal shown when ZIP count ≠ declared count
	@Wire
	private Label lblMdExpectedCount; // "5 cheques" (declared in Step 1)
	@Wire
	private Label lblMdControlAmt; // "₹10,000.00" (declared in Step 1)
	@Wire
	private Label lblMdParsedCount; // "3 cheques" (found in ZIP)
	@Wire
	private Label lblMdParsedAmt; // "₹6,000.00" (parsed total from ZIP)
	@Wire
	private Div mdDupRow; // Duplicate count row (hidden if no dups)
	@Wire
	private Label lblMdSkippedCount; // "2 cheques" (already in system)
	@Wire
	private Label lblMdSavedCount; // "1 cheque" (actually saved)
	@Wire
	private Label lblMdFooterAmt; // Amount summary in footer
	@Wire
	private Button btnMismatchAccept; // Accept mismatch → proceed with actual count
	@Wire
	private Button btnMismatchDiscard; // Discard batch → delete from DB

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — ALL-DUPLICATES DIALOG
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Div duplicateDialog; // Shown when every cheque in ZIP already exists
	@Wire
	private Label lblDupCount; // "5 cheques"
	@Wire
	private Label lblDupAmt; // "₹50,000.00"
	@Wire
	private Label lblDupFlag; // "All 5 cheques are already registered in the system"
	@Wire
	private Button btnDuplicateOk; // Dismiss the dialog

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — SUCCESS TOAST
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Div batchSuccessToast; // Green success toast overlay
	@Wire
	private Label lblToastBatchId; // "BATCH0123"
	@Wire
	private Label lblToastChequeCount; // "12 cheques"
	@Wire
	private Label lblToastAmount; // "₹1,20,000.00"
	@Wire
	private Button btnToastDismiss; // "✕ Close" button inside toast
	@Wire
	private Button btnToastClose; // Small × button on toast corner
	@Wire
	private Timer toastTimer; // Auto-dismisses toast after 5 seconds

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — DASHBOARD STAT CARDS
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Label lblStatBatches; // Count of maker-visible batches
	@Wire
	private Label lblStatCheques; // Sum of totalCheques across those batches
	@Wire
	private Label lblStatPending; // Count of cheques still in Pending status

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — BATCH TABLE + TOOLBAR
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Listbox lbBatches; // The batch rows table
	@Wire
	private Label batchCountLabel; // "5 batches" badge above the table
	@Wire
	private Textbox txtBatchSearch; // Free-text search on batchId / branch
	@Wire
	private Combobox cmbBatchStatus; // "All Status / Draft / Pending"
	@Wire
	private Datebox dtBatchFrom; // Date range filter — from
	@Wire
	private Datebox dtBatchTo; // Date range filter — to
	@Wire
	private Button btnBatchClearDate; // Clear both date pickers
	@Wire
	private Button btnBatchPgFirst; // First page
	@Wire
	private Button btnBatchPgPrev; // Previous page
	@Wire
	private Label lblBatchPgInfo; // "Page 1 of 3"
	@Wire
	private Button btnBatchPgNext; // Next page
	@Wire
	private Button btnBatchPgLast; // Last page

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — CREATE BATCH MODAL INPUTS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Number of cheques the Maker declares are in the physical bundle (control
	 * total — used to detect ZIP count mismatch in Step 2).
	 */
	@Wire
	private Intbox txtChequeCount;
	/** Control amount declared by the Maker (sum of all cheque amounts). */
	@Wire
	private Decimalbox txtExpectedAmount;
	@Wire
	private Label errChequeCount; // Error label below txtChequeCount
	@Wire
	private Label errExpectedAmount; // Error label below txtExpectedAmount

	// ══════════════════════════════════════════════════════════════════════
	// LIFECYCLE — doAfterCompose
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * ZK lifecycle entry point — called once after the ZUL component tree is parsed
	 * and all {@code @Wire} fields are injected.
	 *
	 * <h3>Initialisation Order</h3>
	 * <ol>
	 * <li>Session guard — bounce if not logged in</li>
	 * <li>Header labels — user name / role from session</li>
	 * <li>Load batches from DB into in-memory {@link #batches} list</li>
	 * <li>Render the batch table first page</li>
	 * <li>Refresh stat cards</li>
	 * <li>Auto-open create modal if navigated from My Batches "Create Batch"
	 * button</li>
	 * </ol>
	 *
	 * @param comp root ZK component of scanModule.zul
	 */
	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);

		if (!isSessionValid()) {
			Executions.sendRedirect("/zul/login.zul");
			return;
		}

		loadUserInfo();
		loadBatchesFromService();
		renderBatches();
		refreshStats();
		updateBatchCountLabel();

		// Auto-open the create modal when navigated here from My Batches
		// "Create Batch" button (MyBatchesComposer.onCreateBatch() sets this flag).
		Object autoOpenFlag = Sessions.getCurrent().getAttribute("autoOpenBatchModal");
		if (Boolean.TRUE.equals(autoOpenFlag)) {
			Sessions.getCurrent().removeAttribute("autoOpenBatchModal");
			openBatchModal();
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// SESSION
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns true if the current HTTP session has an authenticated user. The
	 * {@code loggedUser} attribute is set by {@code LoginComposer} on successful
	 * login and cleared on logout or session timeout.
	 */
	private boolean isSessionValid() {
		return com.cts.util.SecurityUtil.isLoggedIn();
	}

	// ══════════════════════════════════════════════════════════════════════
	// HEADER
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Reads {@code userName} and {@code userRole} from session and sets the page
	 * header labels. Values are uppercased to match the design spec.
	 *
	 * <p>
	 * Called from: {@link #doAfterCompose(Component)}
	 */
	private void loadUserInfo() {
		if (lblHdrUser != null)
			lblHdrUser.setValue(sessionStr(SESS_USER_NAME, "USER").toUpperCase());
		if (lblHdrRole != null)
			lblHdrRole.setValue(sessionStr(SESS_USER_ROLE, "ADMINISTRATOR").toUpperCase());
	}

	// ══════════════════════════════════════════════════════════════════════
	// DATA LOAD FROM SERVICE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Clears the in-memory batch list and reloads all batches from the DB.
	 *
	 * <p>
	 * Uses {@code BatchService.getAllBatches()} which returns the full table (all
	 * statuses). The Maker-only view filter is applied in
	 * {@link #getFilteredBatches()} at render time, keeping this method fast and
	 * unconditional.
	 *
	 * <p>
	 * Called from: doAfterCompose, finishSuccessfulImport, discardPendingBatch,
	 * onMismatchDiscard, onBatchSearch, onBatchStatusFilter, onBatchDateFilter.
	 */
	private void loadBatchesFromService() {
		batches.clear();
		try {
			batches.addAll(batchService.getAllBatches());
		} catch (Exception ex) {
			Clients.showNotification("⚠ Could not load batches: " + ex.getMessage(), "warning", null, "middle_center",
					4000);
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// PATH A — DIRECT ZIP UPLOAD (no pre-created batch)
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Handles ZIP file upload from the main page upload button (Path A).
	 *
	 * <p>
	 * This path creates the batch implicitly inside ZipImportServiceImpl — the
	 * Maker does not fill in a control total first.
	 *
	 * <h3>Call Chain</h3>
	 * 
	 * <pre>
	 * onZipUpload(UploadEvent)
	 *   → readAllBytes(media.getStreamData())
	 *   → ZipImportServiceImpl.importZip(bytes, name, branch, user)
	 *       → CtsZipParserImpl.parse()
	 *       → ChequeDAOImpl.findExistingChequeNos()
	 *       → BatchDAOImpl.saveBatch()      [new batch row]
	 *       → ChequeDAOImpl.saveCheques()   [metadata + async image save]
	 *   → if allDuplicates → openDuplicateDialog()
	 *   → else → showSuccessToast()
	 * </pre>
	 *
	 * <p>
	 * Triggered by: {@code onUpload = #btnUploadZip} in scanModule.zul
	 */
	@Listen("onUpload = #btnUploadZip")
	public void onZipUpload(UploadEvent event) {
		Media uploadedMedia = event.getMedia();
		if (uploadedMedia == null) {
			Clients.showNotification("No file received.", "error", null, "middle_center", 3000);
			return;
		}
		if (!uploadedMedia.getName().toLowerCase().endsWith(".zip")) {
			Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000);
			return;
		}

		Clients.showBusy("Processing ZIP — please wait…");
		try {
			byte[] zipBytes = readAllBytes(uploadedMedia.getStreamData());
			String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");

			// Full import pipeline: parse → dedup → persist batch + cheques
			ImportResult importResult = zipImportService.importZip(zipBytes, uploadedMedia.getName(), branchCode,
					createdBy);
			Clients.clearBusy();

			// All cheques already exist — nothing was saved to DB
			if (importResult.isAllDuplicates()) {
				openDuplicateDialog(importResult.getParsedTotal(), importResult.getParsedTotalAmount());
				return;
			}

			// Update in-memory list and refresh the UI
			batches.add(0, importResult.getBatch());
			renderBatches();
			refreshStats();
			updateBatchCountLabel();

			String formattedAmount = importResult.getBatch().getTotalAmount() != null
					? String.format("%,.2f", importResult.getBatch().getTotalAmount())
					: "0.00";
			showSuccessToast(importResult.getBatch().getBatchId(), importResult.getCheques().size(), formattedAmount);

		} catch (Exception ex) {
			Clients.clearBusy();
			Clients.showNotification("❌ Error: " + ex.getMessage(), "error", null, "middle_center", 6000);
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// PATH B STEP 1 — CREATE BATCH MODAL
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Clears the cheque-count error highlight when the user modifies the field.
	 * Triggered by: {@code onChange = #txtChequeCount}
	 */
	@Listen("onChange = #txtChequeCount")
	public void onChequeCountChange() {
		if (errChequeCount != null)
			errChequeCount.setValue("");
		if (txtChequeCount != null)
			txtChequeCount.setSclass("mf-input");
	}

	/**
	 * Clears the control-amount error highlight when the user modifies the field.
	 * Triggered by: {@code onChange = #txtExpectedAmount}
	 */
	@Listen("onChange = #txtExpectedAmount")
	public void onExpectedAmountChange() {
		if (errExpectedAmount != null)
			errExpectedAmount.setValue("");
		if (txtExpectedAmount != null)
			txtExpectedAmount.setSclass("mf-input");
	}

	/**
	 * Validates the Create Batch modal inputs, creates an empty Draft batch in the
	 * DB, and opens the Scan Modal (Step 2) for ZIP upload.
	 *
	 * <h3>Validation</h3> Both fields are validated in one pass — all errors shown
	 * before returning.
	 *
	 * <h3>Call Chain on Success</h3>
	 * 
	 * <pre>
	 * onCreateBatch()
	 *   → closeBatchModal()
	 *   → BatchServiceImpl.createBatch(branch, count, expectedAmt, user)
	 *       → BatchDAOImpl.loadMaxBatchSeq()    [generates next BATCH{n} id]
	 *       → BatchDAOImpl.saveBatch()           [status=Draft, no cheques yet]
	 *   → pendingBatchId = batch.getBatchId()
	 *   → openScanModal(batchId)                [Step 2 — ZIP upload]
	 * </pre>
	 *
	 * <p>
	 * Called from: {@code onClick = #btnCreateBatch}
	 */
	@Listen("onClick = #btnCreateBatch")
	public void onCreateBatch() {
		// Disable immediately to prevent double-click during DB call
		if (btnCreateBatch != null)
			btnCreateBatch.setDisabled(true);

		// Clear previous validation error state
		if (errChequeCount != null)
			errChequeCount.setValue("");
		if (errExpectedAmount != null)
			errExpectedAmount.setValue("");
		if (txtChequeCount != null)
			txtChequeCount.setSclass("mf-input");
		if (txtExpectedAmount != null)
			txtExpectedAmount.setSclass("mf-input");

		// Read field values
		Integer rawChequeCount = (txtChequeCount != null) ? txtChequeCount.getValue() : null;
		int chequeCount = (rawChequeCount != null) ? rawChequeCount : 0;
		BigDecimal rawAmount = (txtExpectedAmount != null) ? txtExpectedAmount.getValue() : null;
		BigDecimal controlAmt = (rawAmount != null) ? rawAmount : BigDecimal.ZERO;

		// Validate both fields — collect all errors before returning
		boolean hasValidationError = false;

		if (rawChequeCount == null || chequeCount < 1) {
			hasValidationError = true;
			if (errChequeCount != null)
				errChequeCount.setValue(rawChequeCount == null ? "Cheque count is required." : "Must be at least 1.");
			if (txtChequeCount != null)
				txtChequeCount.setSclass("mf-input mf-input-error");
		}

		if (rawAmount == null || controlAmt.compareTo(BigDecimal.ZERO) <= 0) {
			hasValidationError = true;
			if (errExpectedAmount != null)
				errExpectedAmount
						.setValue(rawAmount == null ? "Control amount is required." : "Must be greater than 0.");
			if (txtExpectedAmount != null)
				txtExpectedAmount.setSclass("mf-input mf-input-error");
		}

		if (hasValidationError) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			return;
		}

		closeBatchModal();

		try {
			String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");

			// Persist Draft batch — returns entity with generated batchId (BATCH0123)
			BatchEntity newBatch = batchService.createBatch(branchCode, chequeCount, controlAmt, createdBy);
			pendingBatchId = newBatch.getBatchId();

			batches.add(0, newBatch);
			renderBatches();
			refreshStats();
			updateBatchCountLabel();

			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);

			// Proceed to Step 2: ZIP scan modal
			openScanModal(newBatch.getBatchId());

		} catch (Exception ex) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			Clients.showNotification("❌ Could not create batch: " + ex.getMessage(), "error", null, "middle_center",
					5000);
		}
	}

	/**
	 * Navigates to the My Batches page via DashboardComposer. Called from:
	 * {@code onClick = #btnViewBatches}
	 */
	@Listen("onClick = #btnViewBatches")
	public void onViewBatches() {
		com.cts.composer.DashboardComposer.navigateTo("/zul/outward/batchManagement.zul");
	}

	/**
	 * Closes the scan modal and discards the pending (empty) Draft batch. Called
	 * when the user clicks × on the scan modal without uploading a ZIP.
	 *
	 * <p>
	 * Called from: {@code onClick = #btnCloseScanModal}
	 */
	@Listen("onClick = #btnCloseScanModal")
	public void onCloseScanModal() {
		closeScanModal();
		discardPendingBatch(); // deletes the Draft batch row if no ZIP was uploaded
	}

	/**
	 * Legacy fallback discard button — discards the pending batch. Retained for
	 * backward compatibility with existing ZUL event wiring.
	 *
	 * <p>
	 * Called from: {@code onClick = #btnScanCancelDiscard}
	 */
	@Listen("onClick = #btnScanCancelDiscard")
	public void onScanCancelDiscard() {
		discardPendingBatch();
	}

	// ══════════════════════════════════════════════════════════════════════
	// CREATE BATCH MODAL — OPEN / CLOSE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Opens the Create Batch modal (Step 1). Called from:
	 * {@code onClick = #btnOpenBatchModal}
	 */
	@Listen("onClick = #btnOpenBatchModal")
	public void onOpenBatchModal() {
		openBatchModal();
	}

	/**
	 * Closes the Create Batch modal via the header × button. Called from:
	 * {@code onClick = #btnCloseBatchModal}
	 */
	@Listen("onClick = #btnCloseBatchModal")
	public void onCloseBatchModal() {
		closeBatchModal();
	}

	/**
	 * Closes the Create Batch modal via the footer Cancel button. Called from:
	 * {@code onClick = #btnCancelBatchModal}
	 */
	@Listen("onClick = #btnCancelBatchModal")
	public void onCancelBatchModal() {
		closeBatchModal();
	}

	/**
	 * Resets all modal inputs to blank state and shows the Create Batch modal
	 * overlay. Called by: onOpenBatchModal, doAfterCompose (autoOpenBatchModal
	 * flag).
	 */
	private void openBatchModal() {
		if (txtChequeCount != null) {
			txtChequeCount.setValue((Integer) null);
			txtChequeCount.setSclass("mf-input"); //
		}
		if (txtExpectedAmount != null) {
			txtExpectedAmount.setValue((java.math.BigDecimal) null); //
			txtExpectedAmount.setSclass("mf-input"); //
		}
		if (errChequeCount != null)
			errChequeCount.setValue("");
		if (errExpectedAmount != null)
			errExpectedAmount.setValue("");
		if (batchModal != null)
			batchModal.setVisible(true);
	}

	/** Hides the Create Batch modal. */
	private void closeBatchModal() {
		if (batchModal != null)
			batchModal.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// SCAN MODAL — OPEN / CLOSE / PROGRESS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Shows the scan modal (Step 2) and displays the pending batch ID in the
	 * header.
	 *
	 * @param batchId the batch ID created in Step 1 (displayed in modal header)
	 */
	private void openScanModal(String batchId) {
		if (scanBatchIdLabel != null)
			scanBatchIdLabel.setValue(batchId != null ? batchId : "—");
		hideScanProgress();
		if (scanModal != null)
			scanModal.setVisible(true);
	}

	/** Hides the scan modal and resets the progress bar to zero. */
	private void closeScanModal() {
		if (scanModal != null)
			scanModal.setVisible(false);
		hideScanProgress();
	}

	/**
	 * Shows the progress bar at ~90% fill with a status message. Called immediately
	 * after receiving the ZIP upload to give visual feedback during the synchronous
	 * parse + DB persist operation.
	 *
	 * @param statusMessage text to show in the progress label
	 */
	private void showScanProgress(String statusMessage) {
		if (scanProgress != null)
			scanProgress.setVisible(true);
		if (scanProgressText != null)
			scanProgressText.setValue(statusMessage != null ? statusMessage : "Scanning…");
		if (scanProgressFill != null)
			scanProgressFill.setStyle("width:90%;");
	}

	/** Hides the progress bar and resets its fill width to 0%. */
	private void hideScanProgress() {
		if (scanProgress != null)
			scanProgress.setVisible(false);
		if (scanProgressFill != null)
			scanProgressFill.setStyle("width:0%;");
	}

	// ══════════════════════════════════════════════════════════════════════
	// MISMATCH DIALOG — OPEN / CLOSE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Populates and shows the cheque-count mismatch dialog.
	 *
	 * <p>
	 * Shown when the number of non-duplicate cheques actually saved differs from
	 * the control total the Maker declared in Step 1.
	 *
	 * @param expectedChequeCount cheque count entered in Step 1
	 * @param controlAmount       control amount entered in Step 1
	 * @param parsedChequeCount   total cheques found in the ZIP
	 * @param parsedTotalAmount   total amount parsed from the ZIP
	 * @param savedChequeCount    new cheques actually saved (after duplicate
	 *                            removal)
	 * @param skippedDuplicates   cheques skipped because they already exist in
	 *                            system
	 */
	private void openMismatchDialog(int expectedChequeCount, BigDecimal controlAmount, int parsedChequeCount,
			BigDecimal parsedTotalAmount, int savedChequeCount, int skippedDuplicates) {

		if (lblMdExpectedCount != null)
			lblMdExpectedCount.setValue(expectedChequeCount + " cheque" + (expectedChequeCount != 1 ? "s" : ""));
		if (lblMdControlAmt != null)
			lblMdControlAmt.setValue("₹" + formatAmtRaw(controlAmount));
		if (lblMdParsedCount != null)
			lblMdParsedCount.setValue(parsedChequeCount + " cheque" + (parsedChequeCount != 1 ? "s" : ""));
		if (lblMdParsedAmt != null)
			lblMdParsedAmt.setValue("₹" + formatAmtRaw(parsedTotalAmount));
		if (lblMdSavedCount != null)
			lblMdSavedCount.setValue(savedChequeCount + " cheque" + (savedChequeCount != 1 ? "s" : ""));
		if (lblMdFooterAmt != null)
			lblMdFooterAmt.setValue("₹" + formatAmtRaw(parsedTotalAmount));

		// Show duplicate count row only when there were actually skipped cheques
		if (skippedDuplicates > 0) {
			if (lblMdSkippedCount != null)
				lblMdSkippedCount.setValue(skippedDuplicates + " cheque" + (skippedDuplicates != 1 ? "s" : ""));
			if (mdDupRow != null)
				mdDupRow.setVisible(true);
		} else if (mdDupRow != null) {
			mdDupRow.setVisible(false);
		}

		if (mismatchDialog != null)
			mismatchDialog.setVisible(true);
	}

	/** Hides the mismatch dialog. */
	private void closeMismatchDialog() {
		if (mismatchDialog != null)
			mismatchDialog.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// ALL-DUPLICATES DIALOG — OPEN / CLOSE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Dismisses the "All Cheques Already Present" dialog. Called from:
	 * {@code onClick = #btnDuplicateOk}
	 */
	@Listen("onClick = #btnDuplicateOk")
	public void onDuplicateOk() {
		closeDuplicateDialog();
	}

	/**
	 * Populates and shows the "All Cheques Already Present" dialog.
	 *
	 * <p>
	 * This dialog is shown when every cheque in the uploaded ZIP already exists in
	 * the cts_cheques table. No new data was saved to DB.
	 *
	 * @param totalParsedCount total cheques found in the ZIP
	 * @param totalParsedAmt   total amount of those cheques
	 */
	private void openDuplicateDialog(int totalParsedCount, BigDecimal totalParsedAmt) {
		if (lblDupCount != null)
			lblDupCount.setValue(totalParsedCount + " cheque" + (totalParsedCount != 1 ? "s" : ""));
		if (lblDupAmt != null)
			lblDupAmt.setValue("₹" + formatAmtRaw(totalParsedAmt));
		if (lblDupFlag != null)
			lblDupFlag.setValue("All " + totalParsedCount + " cheque" + (totalParsedCount != 1 ? "s are" : " is")
					+ " already registered in the system");
		if (duplicateDialog != null)
			duplicateDialog.setVisible(true);
	}

	/** Hides the all-duplicates dialog. */
	private void closeDuplicateDialog() {
		if (duplicateDialog != null)
			duplicateDialog.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// SUCCESS TOAST — OPEN / CLOSE
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * User clicks "✕ Close" inside the toast body. Called from:
	 * {@code onClick = #btnToastDismiss}
	 */
	@Listen("onClick = #btnToastDismiss")
	public void onToastDismiss() {
		closeSuccessToast();
	}

	/**
	 * User clicks the small × corner button on the toast. Called from:
	 * {@code onClick = #btnToastClose}
	 */
	@Listen("onClick = #btnToastClose")
	public void onToastClose() {
		closeSuccessToast();
	}

	/**
	 * Auto-dismiss timer fires 5 seconds after the toast was shown. Called from:
	 * {@code onTimer = #toastTimer}
	 */
	@Listen("onTimer = #toastTimer")
	public void onToastTimer() {
		closeSuccessToast();
	}

	/**
	 * Populates and shows the green "Batch Created Successfully" toast.
	 *
	 * <p>
	 * Also closes any overlay that might still be visible (scan modal, mismatch
	 * dialog, etc.) before showing the toast to avoid z-index conflicts.
	 *
	 * <p>
	 * In addition to the custom toast Div, always fires a ZK built-in
	 * {@code showNotification()} as a guaranteed-visible fallback in case the
	 * custom toast Div fails to render.
	 *
	 * @param batchId     the created / updated batch ID
	 * @param chequeCount number of cheques in the batch
	 * @param amountStr   pre-formatted amount string without ₹ symbol
	 */
	private void showSuccessToast(String batchId, int chequeCount, String amountStr) {
		// Force-close any overlay that may still be lingering
		if (scanModal != null)
			scanModal.setVisible(false);
		if (batchModal != null)
			batchModal.setVisible(false);
		if (mismatchDialog != null)
			mismatchDialog.setVisible(false);
		if (duplicateDialog != null)
			duplicateDialog.setVisible(false);

		if (lblToastBatchId != null)
			lblToastBatchId.setValue(nullSafe(batchId, "—"));
		if (lblToastChequeCount != null)
			lblToastChequeCount.setValue(chequeCount + " cheque" + (chequeCount != 1 ? "s" : ""));
		if (lblToastAmount != null)
			lblToastAmount.setValue("₹" + amountStr);

		LOG.info("showSuccessToast: batchId=" + batchId + " count=" + chequeCount + " amt=" + amountStr + " toastDiv="
				+ (batchSuccessToast != null));

		// Guaranteed fallback — ZK built-in notification works even if custom toast
		// fails
		Clients.showNotification("✅ Batch " + nullSafe(batchId, "—") + " created — " + chequeCount + " cheque"
				+ (chequeCount != 1 ? "s" : "") + " · ₹" + amountStr, "info", null, "top_center", 4000);

		if (batchSuccessToast != null)
			batchSuccessToast.setVisible(true);

		// Restart the auto-dismiss timer (pure ZK MVC — no JS setTimeout needed)
		if (toastTimer != null) {
			toastTimer.setRunning(false);
			toastTimer.setRunning(true);
		}
	}

	/** Hides the success toast and stops the auto-dismiss timer. */
	private void closeSuccessToast() {
		if (toastTimer != null)
			toastTimer.setRunning(false);
		if (batchSuccessToast != null)
			batchSuccessToast.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// PATH B STEP 2 — SCAN MODAL ZIP UPLOAD
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Handles ZIP upload inside the scan modal (Path B Step 2).
	 *
	 * <p>
	 * Differs from Path A: passes {@link #pendingBatchId} to importZip() so the
	 * cheques are linked to the already-created Draft batch instead of creating a
	 * new batch row.
	 *
	 * <h3>Three outcome cases</h3>
	 * <ul>
	 * <li><b>Case 1 — All duplicates:</b> discard pending batch → show dup
	 * dialog</li>
	 * <li><b>Case 2 — Count mismatch:</b> save result for later → show mismatch
	 * dialog</li>
	 * <li><b>Case 3 — Clean import:</b> call finishSuccessfulImport()</li>
	 * </ul>
	 *
	 * <p>
	 * Called from: {@code onUpload = #btnScanUploadZip} in scanModule.zul
	 */
	@Listen("onUpload = #btnScanUploadZip")
	public void onScanZipUpload(UploadEvent event) {
		Media uploadedMedia = event.getMedia();
		if (uploadedMedia == null) {
			Clients.showNotification("No file received.", "error", null, "middle_center", 3000);
			return;
		}
		if (!uploadedMedia.getName().toLowerCase().endsWith(".zip")) {
			Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000);
			return;
		}

		showScanProgress("Processing ZIP — please wait…");
		Clients.showBusy("Processing ZIP…");

		try {
			byte[] zipBytes = readAllBytes(uploadedMedia.getStreamData());
			String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");

			// importZip with existingBatchId:
			// - Does NOT create a new batch row
			// - Updates existing batch: sets total_cheques, total_amount
			// - Inserts new ChequeEntity rows only for non-duplicates
			ImportResult importResult = zipImportService.importZip(zipBytes, uploadedMedia.getName(), branchCode,
					createdBy, pendingBatchId);
			Clients.clearBusy();

			// ── Case 1: ALL cheques were duplicates ────────────────────────
			// ZipImportServiceImpl returned without saving anything.
			// Discard the now-empty Draft batch.
			if (importResult.isAllDuplicates()) {
				hideScanProgress();
				closeScanModal();
				discardPendingBatch();
				openDuplicateDialog(importResult.getParsedTotal(), importResult.getParsedTotalAmount());
				return;
			}

			// ── Case 2: Cheque count mismatch ─────────────────────────────
			// ZIP contained a different number of valid cheques than declared.
			// Show the mismatch dialog and wait for user decision.
			BatchEntity pendingBatchEntity = batches.stream().filter(b -> b.getBatchId().equals(pendingBatchId))
					.findFirst().orElse(null);

			int expectedChequeCount = pendingBatchEntity != null ? pendingBatchEntity.getExpectedCheques() : 0;
			BigDecimal declaredControlAmt = pendingBatchEntity != null && pendingBatchEntity.getExpectedAmount() != null
					? pendingBatchEntity.getExpectedAmount()
					: BigDecimal.ZERO;

			int actualSavedCount = importResult.getCheques().size(); // non-duplicate cheques saved
			int totalParsedCount = importResult.getParsedTotal(); // total cheques in ZIP

			if (expectedChequeCount > 0 && actualSavedCount != expectedChequeCount) {
				// Stash result — onMismatchAccept() will call finishSuccessfulImport() with it
				pendingMismatchResult = importResult;
				hideScanProgress();
				closeScanModal();
				openMismatchDialog(expectedChequeCount, declaredControlAmt, totalParsedCount,
						importResult.getParsedTotalAmount(), actualSavedCount, importResult.getSkippedDuplicates());
				return;
			}

			// ── Case 3: Clean import — all counts match ────────────────────
			hideScanProgress();
			closeScanModal();
			finishSuccessfulImport(importResult);

		} catch (Exception ex) {
			Clients.clearBusy();
			hideScanProgress();
			closeScanModal();
			String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			LOG.severe("onScanZipUpload error: " + errorMsg);
			Clients.showNotification("❌ Upload failed: " + errorMsg, "error", null, "middle_center", 6000);
			pendingBatchId = null; // clear so next Create Batch attempt works cleanly
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// MISMATCH DIALOG — ACCEPT / DISCARD
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * User accepts the mismatch — proceeds with the actual imported cheque count.
	 *
	 * <p>
	 * The cheques are already persisted in the DB at this point (importZip already
	 * ran). This just closes the dialog and triggers the normal success flow (UI
	 * refresh + toast).
	 *
	 * <p>
	 * Called from: {@code onClick = #btnMismatchAccept}
	 */
	@Listen("onClick = #btnMismatchAccept")
	public void onMismatchAccept() {
		closeMismatchDialog();
		if (pendingMismatchResult != null) {
			finishSuccessfulImport(pendingMismatchResult);
		}
		pendingMismatchResult = null;
	}

	/**
	 * User discards the mismatch — deletes the batch and all its cheques from DB.
	 *
	 * <h3>Call Chain</h3>
	 * 
	 * <pre>
	 * onMismatchDiscard()
	 *   → BatchServiceImpl.discardBatch(pendingBatchId)
	 *       → BatchDAOImpl.deleteBatchAndCheques()   [DELETE cascade]
	 *   → remove from in-memory batches list
	 *   → renderBatches() / refreshStats()
	 * </pre>
	 *
	 * <p>
	 * Called from: {@code onClick = #btnMismatchDiscard}
	 */
	@Listen("onClick = #btnMismatchDiscard")
	public void onMismatchDiscard() {
		closeMismatchDialog();

		String batchToDelete = pendingBatchId;
		pendingMismatchResult = null;
		pendingBatchId = null; // clear before DB call to avoid re-entry issues

		if (batchToDelete != null) {
			try {
				batchService.discardBatch(batchToDelete);
				batches.removeIf(b -> b.getBatchId().equals(batchToDelete));
				renderBatches();
				refreshStats();
				updateBatchCountLabel();
				Clients.showNotification("Batch " + batchToDelete + " discarded.", "warning", null, "middle_center",
						3000);
			} catch (Exception ex) {
				Clients.showNotification("❌ Discard failed: " + ex.getMessage(), "error", null, "middle_center", 5000);
			}
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// SHARED SUCCESS FINISH (all import paths converge here)
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Common end-state for all successful import paths:
	 * <ul>
	 * <li>Path A direct upload (no mismatch)</li>
	 * <li>Path B scan modal (clean import, Case 3)</li>
	 * <li>Path B mismatch accepted (onMismatchAccept)</li>
	 * </ul>
	 *
	 * <p>
	 * Reloads all batches from DB (ensures DB-authoritative counts), refreshes the
	 * table and stat cards, clears {@link #pendingBatchId}, and shows the success
	 * toast.
	 *
	 * @param importResult the result from ZipImportServiceImpl
	 */
	private void finishSuccessfulImport(ImportResult importResult) {
		// Reload from DB to get the persisted state (totalCheques, totalAmount, status)
		loadBatchesFromService();
		renderBatches();
		refreshStats();
		updateBatchCountLabel();
		pendingBatchId = null;

		String formattedAmount = importResult.getBatch().getTotalAmount() != null
				? String.format("%,.2f", importResult.getBatch().getTotalAmount())
				: "0.00";
		showSuccessToast(importResult.getBatch().getBatchId(), importResult.getCheques().size(), formattedAmount);
	}

	// ══════════════════════════════════════════════════════════════════════
	// MISC LISTENERS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Logout: invalidates the ZK session and redirects to the login page. Called
	 * from: {@code onClick = #btnLogout}
	 */
	@Listen("onClick = #btnLogout")
	public void onLogout(Event event) {
		Sessions.getCurrent().invalidate();
		Executions.sendRedirect("/zul/login.zul");
	}

	// ══════════════════════════════════════════════════════════════════════
	// FILTER / SEARCH / PAGINATION LISTENERS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Live search on batch ID or branch code. {@code onChanging} fires on every
	 * keystroke; {@code onChange} fires on blur. Both reset to page 1 and
	 * re-render.
	 *
	 * <p>
	 * Called from: {@code onChange = #txtBatchSearch; onChanging = #txtBatchSearch}
	 */
	@Listen("onChange = #txtBatchSearch; onChanging = #txtBatchSearch")
	public void onBatchSearch() {
		batchPage = 1;
		renderBatches();
	}

	/**
	 * Status combo filter (All Status / Draft / Pending). Called from:
	 * {@code onSelect = #cmbBatchStatus}
	 */
	@Listen("onSelect = #cmbBatchStatus")
	public void onBatchStatusFilter() {
		batchPage = 1;
		renderBatches();
	}

	/**
	 * Date-range filter on batch createdAt date. Called from:
	 * {@code onChange = #dtBatchFrom; onChange = #dtBatchTo}
	 */
	@Listen("onChange = #dtBatchFrom; onChange = #dtBatchTo")
	public void onBatchDateFilter() {
		batchPage = 1;
		renderBatches();
	}

	/**
	 * Clears both date pickers and re-renders the table. Called from:
	 * {@code onClick = #btnBatchClearDate}
	 */
	@Listen("onClick = #btnBatchClearDate")
	public void onBatchClearDate() {
		if (dtBatchFrom != null)
			dtBatchFrom.setValue(null);
		if (dtBatchTo != null)
			dtBatchTo.setValue(null);
		batchPage = 1;
		renderBatches();
	}

	@Listen("onClick = #btnBatchPgFirst")
	public void onBatchPgFirst() {
		batchPage = 1;
		renderBatches();
	}

	@Listen("onClick = #btnBatchPgPrev")
	public void onBatchPgPrev() {
		if (batchPage > 1) {
			batchPage--;
			renderBatches();
		}
	}

	@Listen("onClick = #btnBatchPgNext")
	public void onBatchPgNext() {
		batchPage++;
		renderBatches();
	}

	@Listen("onClick = #btnBatchPgLast")
	public void onBatchPgLast() {
		List<BatchEntity> filteredBatches = getFilteredBatches();
		int totalPages = Math.max(1, (int) Math.ceil((double) filteredBatches.size() / BATCH_PAGE_SIZE));
		batchPage = totalPages;
		renderBatches();
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH TABLE RENDER
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Applies all active filters to the in-memory {@link #batches} list and returns
	 * only the batches the Maker should see.
	 *
	 * <h3>Filter Pipeline</h3>
	 * <ol>
	 * <li><b>Maker status gate</b> — only Draft and VerificationInProgressAtMaker.
	 * Ghost rows (VerificationInProgressAtMaker with zero amount and no cheques)
	 * are excluded to avoid showing aborted manual creates.</li>
	 * <li><b>Text search</b> — ILIKE on batchId and branchCode.</li>
	 * <li><b>Status combo</b> — "Pending" in UI → VerificationInProgressAtMaker in
	 * DB.</li>
	 * <li><b>Date range</b> — on batch.createdAt (converted to
	 * java.util.Date).</li>
	 * </ol>
	 *
	 * @return filtered list of batches for the current UI filter state
	 */
	private List<BatchEntity> getFilteredBatches() {

		// ── 1. Maker status gate ───────────────────────────────────────────
		List<BatchEntity> makerVisibleBatches = batches.stream().filter(batch -> {
			String dbStatus = batch.getStatus();
			boolean isMakerVisible = (dbStatus == null) || "Draft".equalsIgnoreCase(dbStatus)
					|| "VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus);
			if (!isMakerVisible)
				return false;

			// Exclude ghost rows: empty Draft/Pending batches from aborted creates
			return !("VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus)
					&& (batch.getTotalAmount() == null || batch.getTotalAmount().compareTo(BigDecimal.ZERO) == 0)
					&& loadChequesForBatch(batch.getBatchId()).isEmpty());
		}).collect(java.util.stream.Collectors.toList());

		// ── 2. Text search ────────────────────────────────────────────────
		String searchTerm = (txtBatchSearch != null && txtBatchSearch.getValue() != null)
				? txtBatchSearch.getValue().trim().toLowerCase()
				: "";
		if (!searchTerm.isEmpty()) {
			makerVisibleBatches = makerVisibleBatches.stream()
					.filter(b -> (b.getBatchId() != null && b.getBatchId().toLowerCase().contains(searchTerm))
							|| (b.getBranchCode() != null && b.getBranchCode().toLowerCase().contains(searchTerm)))
					.collect(java.util.stream.Collectors.toList());
		}

		// ── 3. Status combo ───────────────────────────────────────────────
		// "Pending" UI label maps to "VerificationInProgressAtMaker" DB value.
		String comboStatusFilter = (cmbBatchStatus != null && cmbBatchStatus.getValue() != null)
				? cmbBatchStatus.getValue().trim()
				: "";
		if (!comboStatusFilter.isEmpty() && !"All Status".equalsIgnoreCase(comboStatusFilter)) {
			final String sf = comboStatusFilter;
			makerVisibleBatches = makerVisibleBatches.stream().filter(batch -> {
				String dbStatus = batch.getStatus();
				if ("Draft".equalsIgnoreCase(sf))
					return dbStatus == null || "Draft".equalsIgnoreCase(dbStatus);
				if ("Pending".equalsIgnoreCase(sf))
					return "VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus);
				return true;
			}).collect(java.util.stream.Collectors.toList());
		}

		// ── 4. Date range ─────────────────────────────────────────────────
		java.util.Date fromDate = (dtBatchFrom != null) ? dtBatchFrom.getValue() : null;
		java.util.Date toDate = (dtBatchTo != null) ? dtBatchTo.getValue() : null;
		if (fromDate != null || toDate != null) {
			makerVisibleBatches = makerVisibleBatches.stream().filter(batch -> {
				java.util.Date batchCreatedDate = parseBatchDate(batch.getCreatedAt());
				if (batchCreatedDate == null)
					return true; // null dates pass through
				if (fromDate != null && batchCreatedDate.before(fromDate))
					return false;
				if (toDate != null && batchCreatedDate.after(toDate))
					return false;
				return true;
			}).collect(java.util.stream.Collectors.toList());
		}

		return makerVisibleBatches;
	}

	/**
	 * Converts a {@link java.time.LocalDateTime} (from BatchEntity.createdAt) to
	 * {@link java.util.Date} for comparison with ZK Datebox values.
	 *
	 * @param localDateTime the batch creation timestamp
	 * @return java.util.Date equivalent, or null if input is null
	 */
	private java.util.Date parseBatchDate(java.time.LocalDateTime localDateTime) {
		if (localDateTime == null)
			return null;
		return java.util.Date.from(localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant());
	}

	/**
	 * Rebuilds the Listitem rows in {@code lbBatches} for the current page of
	 * filtered batches.
	 *
	 * <p>
	 * Clears all existing rows first, then either renders an empty-state row or
	 * calls {@link #appendBatchRow(BatchEntity)} for each batch on the page. Also
	 * updates pagination bar button states and the "Page X of Y" label.
	 */
	private void renderBatches() {
		if (lbBatches == null)
			return;
		lbBatches.getItems().clear();

		List<BatchEntity> filteredBatches = getFilteredBatches();
		int totalBatchCount = filteredBatches.size();
		int totalPages = Math.max(1, (int) Math.ceil((double) totalBatchCount / BATCH_PAGE_SIZE));

		// Clamp current page to valid range after filter changes
		batchPage = Math.max(1, Math.min(batchPage, totalPages));

		int startIndex = (batchPage - 1) * BATCH_PAGE_SIZE;
		int endIndex = Math.min(startIndex + BATCH_PAGE_SIZE, totalBatchCount);
		List<BatchEntity> currentPage = startIndex < totalBatchCount ? filteredBatches.subList(startIndex, endIndex)
				: new ArrayList<>();

		updateBatchCountLabel(totalBatchCount);
		updateBatchPagination(totalBatchCount, totalPages);

		// Empty state row
		if (currentPage.isEmpty()) {
			Listitem emptyRow = new Listitem();
			Listcell emptyCell = new Listcell();
			emptyCell.setClientAttribute("colspan", "6");
			Label emptyLabel = new Label(totalBatchCount == 0 ? "No draft or pending batches found."
					: "No batches match the current filter.");
			emptyLabel.setStyle("display:block;text-align:center;color:#94a3b8;padding:32px;font-size:13px;");
			emptyCell.appendChild(emptyLabel);
			emptyRow.appendChild(emptyCell);
			lbBatches.appendChild(emptyRow);
			return;
		}

		for (BatchEntity batch : currentPage) {
			appendBatchRow(batch);
		}
	}

	/**
	 * Updates the pagination bar: "Page X of Y" label and First/Prev/Next/Last
	 * states.
	 *
	 * @param totalBatchCount total matching batches (for display calculation)
	 * @param totalPages      total page count
	 */
	private void updateBatchPagination(int totalBatchCount, int totalPages) {
		if (lblBatchPgInfo != null)
			lblBatchPgInfo.setValue("Page " + batchPage + " of " + totalPages);
		if (btnBatchPgFirst != null)
			btnBatchPgFirst.setDisabled(batchPage <= 1);
		if (btnBatchPgPrev != null)
			btnBatchPgPrev.setDisabled(batchPage <= 1);
		if (btnBatchPgNext != null)
			btnBatchPgNext.setDisabled(batchPage >= totalPages);
		if (btnBatchPgLast != null)
			btnBatchPgLast.setDisabled(batchPage >= totalPages);
	}

	/**
	 * Builds and appends one Listitem row for a batch to the batch table.
	 *
	 * <p>
	 * Columns: Batch ID (link) | Cheque Count | Total Amount | Created Date |
	 * Status chip | "View & Edit" action link.
	 *
	 * <p>
	 * Row click → {@link #navigateToBatchDetail(BatchEntity)}.
	 *
	 * @param batch the BatchEntity to render as a table row
	 */
	private void appendBatchRow(BatchEntity batch) {
		Listitem row = new Listitem();
		row.setValue(batch);
		row.setSclass("mb-row");

		// Column 1: Batch ID — styled as a clickable link
		Listcell idCell = new Listcell();
		Label idLabel = new Label(nullSafe(batch.getBatchId(), "—"));
		idLabel.setSclass("mb-link");
		idCell.appendChild(idLabel);
		row.appendChild(idCell);

		// Column 2: Cheque count
		row.appendChild(new Listcell(String.valueOf(batch.getTotalCheques())));

		// Column 3: Total amount (right-aligned monospace)
		Listcell amtCell = new Listcell();
		amtCell.setSclass("amt-cell");
		amtCell.appendChild(new Label(formatAmt(batch.getTotalAmount())));
		row.appendChild(amtCell);

		// Column 4: Created date (DD/MM/YYYY)
		row.appendChild(new Listcell(formatBatchDate(batch.getCreatedAt())));

		// Column 5: Status chip
		Listcell statusCell = new Listcell();
		Label statusLabel = new Label(mapDisplayStatus(batch.getStatus()));
		statusLabel.setSclass(mbStatusChip(batch.getStatus()));
		statusCell.appendChild(statusLabel);
		row.appendChild(statusCell);

		// Column 6: Action link
		Listcell actionCell = new Listcell();
		Label actionLabel = new Label("View & Edit");
		actionLabel.setSclass("mb-action-link");
		actionCell.appendChild(actionLabel);
		row.appendChild(actionCell);

		// Row click → store batchId in session → navigate to batch-detail.zul
		row.addEventListener("onClick", e -> navigateToBatchDetail(batch));
		lbBatches.appendChild(row);
	}

	/**
	 * Maps the raw DB status string to a human-readable display label for the batch
	 * table status chip.
	 *
	 * <ul>
	 * <li>null / "Draft" → "Draft"</li>
	 * <li>"VerificationInProgressAtMaker" → "Pending"</li>
	 * </ul>
	 */
	private String mapDisplayStatus(String dbStatus) {
		if (dbStatus == null || "Draft".equalsIgnoreCase(dbStatus))
			return "Draft";
		if ("VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus))
			return "Pending";
		return dbStatus;
	}

	/**
	 * Returns the CSS sclass for the status chip in the batch table.
	 *
	 * <ul>
	 * <li>Draft → {@code "chip ch-amber"} (yellow)</li>
	 * <li>Pending → {@code "chip ch-pending"} (blue-grey)</li>
	 * <li>others → {@code "chip ch-slate"}</li>
	 * </ul>
	 */
	private String mbStatusChip(String dbStatus) {
		if (dbStatus == null || "Draft".equalsIgnoreCase(dbStatus))
			return "chip ch-amber";
		if ("VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus))
			return "chip ch-pending";
		return "chip ch-slate";
	}

	/**
	 * Formats a {@link java.time.LocalDateTime} as "DD/MM/YYYY" for the batch
	 * table.
	 *
	 * @param localDateTime the batch creation timestamp; null → "—"
	 */
	private String formatBatchDate(java.time.LocalDateTime localDateTime) {
		if (localDateTime == null)
			return "—";
		return String.format("%02d/%02d/%04d", localDateTime.getDayOfMonth(), localDateTime.getMonthValue(),
				localDateTime.getYear());
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH ROW CLICK — NAVIGATE TO BATCH DETAIL
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Stores the selected batch ID in the HTTP session and navigates to the Batch
	 * Detail screen via DashboardComposer.
	 *
	 * <p>
	 * Also sets {@code batchDetailBackPage} so the Back button in
	 * BatchDetailComposer returns to the scan module.
	 *
	 * <p>
	 * Called from: Listitem.onClick listener in
	 * {@link #appendBatchRow(BatchEntity)}.
	 *
	 * @param batch the batch whose detail page should be opened
	 */
	private void navigateToBatchDetail(BatchEntity batch) {
		Sessions.getCurrent().setAttribute("selectedBatchId", batch.getBatchId());
		Sessions.getCurrent().setAttribute("batchDetailBackPage", "/zul/outward/scanModule.zul");
		com.cts.composer.DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
	}

	// ══════════════════════════════════════════════════════════════════════
	// STAT CARDS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Recomputes and updates the three dashboard statistics card labels.
	 *
	 * <ul>
	 * <li>{@code lblStatBatches} — count of Draft + VerificationInProgressAtMaker
	 * batches</li>
	 * <li>{@code lblStatCheques} — sum of totalCheques across those batches</li>
	 * <li>{@code lblStatPending} — DB count of cheques with ver_status =
	 * 'Pending'</li>
	 * </ul>
	 *
	 * <p>
	 * Called from: doAfterCompose, finishSuccessfulImport, discardPendingBatch,
	 * onZipUpload, onCreateBatch.
	 */
	private void refreshStats() {
		List<BatchEntity> makerBatches = batches.stream().filter(batch -> {
			String dbStatus = batch.getStatus();
			return dbStatus == null || "Draft".equalsIgnoreCase(dbStatus)
					|| "VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus);
		}).collect(java.util.stream.Collectors.toList());

		int totalBatchCount = makerBatches.size();
		int totalChequeCount = makerBatches.stream().mapToInt(BatchEntity::getTotalCheques).sum();

		long pendingChequeCount;
		try {
			pendingChequeCount = chequeService.countPending();
		} catch (Exception ex) {
			pendingChequeCount = totalChequeCount; // fallback if DB is temporarily unreachable
		}

		setLabel(lblStatBatches, String.valueOf(totalBatchCount));
		setLabel(lblStatCheques, String.valueOf(totalChequeCount));
		setLabel(lblStatPending, String.valueOf(pendingChequeCount));
	}

	/**
	 * Updates the "N batches" badge in the batch table toolbar and syncs any
	 * client-side badge elements via the JS helper {@code bce_updateBatchLabel()}.
	 *
	 * @param batchCount the count of maker-visible batches to display
	 */
	private void updateBatchCountLabel(int batchCount) {
		String badgeText = batchCount + " batch" + (batchCount == 1 ? "" : "es");
		if (batchCountLabel != null)
			batchCountLabel.setValue(badgeText);
		Clients.evalJavaScript("bce_updateBatchLabel(" + batchCount + ");");
	}

	/**
	 * Overload: counts maker-visible batches from the in-memory list, then
	 * delegates to {@link #updateBatchCountLabel(int)}.
	 */
	private void updateBatchCountLabel() {
		long makerBatchCount = batches.stream().filter(batch -> {
			String dbStatus = batch.getStatus();
			return dbStatus == null || "Draft".equalsIgnoreCase(dbStatus)
					|| "VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus);
		}).count();
		updateBatchCountLabel((int) makerBatchCount);
	}

	// ══════════════════════════════════════════════════════════════════════
	// HELPERS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads cheques for a batch via ChequeService. Returns an empty list (never
	 * null) on error to keep callers safe.
	 *
	 * @param batchId the batch whose cheques to load
	 * @return list of ChequeEntity; empty on error or missing batch
	 */
	private List<ChequeEntity> loadChequesForBatch(String batchId) {
		try {
			return chequeService.getChequesForBatch(batchId);
		} catch (Exception ex) {
			Clients.showNotification("⚠ Could not load cheques: " + ex.getMessage(), "warning", null, "middle_center",
					3000);
			return new ArrayList<>();
		}
	}

	/**
	 * Deletes the pending (empty) Draft batch from the DB when the scan modal is
	 * closed before a ZIP is uploaded.
	 *
	 * <p>
	 * Also called by onMismatchDiscard() for the two-step path discard.
	 *
	 * <h3>Safety</h3> Clears {@link #pendingBatchId} BEFORE the DB call to prevent
	 * re-entry if an exception triggers a second discard attempt.
	 */
	private void discardPendingBatch() {
		if (pendingBatchId == null)
			return;

		String batchToDelete = pendingBatchId;
		pendingBatchId = null; // clear first to prevent re-entry

		try {
			batchService.discardBatch(batchToDelete);
			batches.removeIf(b -> b.getBatchId().equals(batchToDelete));
			renderBatches();
			refreshStats();
			updateBatchCountLabel();
			LOG.info("Discarded empty batch: " + batchToDelete);
		} catch (Exception ex) {
			LOG.warning("discardPendingBatch: " + ex.getMessage());
		}
	}

	/**
	 * Null-safe Label.setValue() — shows "—" for null/blank values.
	 *
	 * @param labelComponent the ZK Label
	 * @param value          the value to set; null/blank → "—"
	 */
	private void setLabel(Label labelComponent, String value) {
		if (labelComponent != null)
			labelComponent.setValue(nullSafe(value, "—"));
	}

	/**
	 * Formats a BigDecimal amount using Indian denomination shortcuts:
	 * <ul>
	 * <li>≥ 1 Crore (1,00,00,000) → "₹X.XX Cr"</li>
	 * <li>≥ 1 Lakh (1,00,000) → "₹X.XX L"</li>
	 * <li>Otherwise → "₹X,XXX.XX"</li>
	 * </ul>
	 *
	 * @param amount the amount to format; null / zero → "₹0.00"
	 */
	private String formatAmt(BigDecimal amount) {
		if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0)
			return "₹0.00";
		double val = amount.doubleValue();
		if (val >= 1_00_00_000)
			return String.format("₹%.2f Cr", val / 1_00_00_000.0);
		else if (val >= 1_00_000)
			return String.format("₹%.2f L", val / 1_00_000.0);
		else
			return String.format("₹%,.2f", val);
	}

	/**
	 * Returns a plain comma-formatted amount string (no ₹ symbol, no L/Cr suffix).
	 * Used in dialog labels where the ₹ symbol is added by the caller.
	 *
	 * @param amount the amount to format; null / zero → "0.00"
	 */
	private String formatAmtRaw(BigDecimal amount) {
		if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0)
			return "0.00";
		return String.format("%,.2f", amount);
	}

	/**
	 * Reads a String attribute from the current ZK session.
	 *
	 * @param attributeKey the session attribute name
	 * @param defaultValue fallback if the attribute is absent or null
	 * @return the attribute value, or {@code defaultValue}
	 */
	private String sessionStr(String attributeKey, String defaultValue) {
		com.cts.uam.model.User currentUser = com.cts.util.SecurityUtil.getCurrentUser();
		if (currentUser == null) return defaultValue;
		if (SESS_USER_NAME.equals(attributeKey)) {
			String fullName = currentUser.getFullName();
			return (fullName != null && !fullName.isBlank()) ? fullName : currentUser.getUsername();
		}
		if (SESS_USER_ROLE.equals(attributeKey)) {
			String roleLabel = currentUser.getRoleLabel();
			return roleLabel != null ? roleLabel : defaultValue;
		}
		// SESS_USER_BRANCH has no equivalent on the UAM User model yet; keep default.
		return defaultValue;
	}

	/**
	 * Returns {@code value} if non-null and non-blank, otherwise {@code fallback}.
	 *
	 * @param value    potentially null or blank string
	 * @param fallback string to return if value is absent
	 */
	private String nullSafe(String value, String fallback) {
		return (value != null && !value.isBlank()) ? value : fallback;
	}

	/**
	 * Reads all bytes from an InputStream into a byte array.
	 *
	 * <p>
	 * Used to convert the ZK UploadEvent media stream to a {@code byte[]} required
	 * by the ZIP parser. Buffer size of 8 KB per read is efficient for typical CTS
	 * ZIP files (1–50 cheques, ~5–20 MB).
	 *
	 * @param inputStream the stream from ZK Media.getStreamData()
	 * @return complete byte array of the stream content
	 * @throws Exception if an I/O error occurs reading the stream
	 */
	private byte[] readAllBytes(InputStream inputStream) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] readBlock = new byte[8192]; // 8 KB read block
		int bytesRead;
		while ((bytesRead = inputStream.read(readBlock)) != -1) {
			buffer.write(readBlock, 0, bytesRead);
		}
		return buffer.toByteArray();
	}
}