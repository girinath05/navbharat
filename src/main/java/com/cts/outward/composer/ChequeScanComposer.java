/**
 * File     : ChequeScanComposer.java
 * Package  : com.cts.outward.composer
 * Purpose  : ZK SelectorComposer for cheque-scan.zul — manages the full
 *            batch creation and ZIP import workflow for the Maker role.
 *            Handles batch listing, modal orchestration, ZIP import,
 *            duplicate detection, mismatch resolution, and success feedback.
 * Author   : Umesh M.
 * Date     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 * LAYER FLOW  (UI → Service → DAO → DB)
 * ──────────────────────────────────────────────────────────────
 *
 *  cheque-scan.zul
 *      │   (ZK events: onClick, onChange, onUpload, onTimer)
 *      ▼
 *  ChequeScanComposer       ← THIS FILE  (UI logic only, no SQL)
 *      │   calls ↓
 *      ├── BatchService / BatchServiceImpl
 *      │       │   calls ↓
 *      │       └── BatchDAO / BatchDAOImpl        → cts_batches (PostgreSQL)
 *      │
 *      ├── ChequeService / ChequeServiceImpl
 *      │       │   calls ↓
 *      │       └── ChequeDAO / ChequeDAOImpl      → cts_cheques (PostgreSQL)
 *      │
 *      └── ZipImportService / ZipImportServiceImpl
 *              │   calls ↓
 *              ├── CtsZipParserImpl               → ZIP / XML parsing
 *              ├── ChequeDAOImpl.findExistingNos  → dedup check
 *              ├── BatchDAOImpl.saveBatch         → persist new batch row
 *              └── ChequeDAOImpl.saveCheques      → persist cheque rows
 *
 * ──────────────────────────────────────────────────────────────
 * NAVIGATION ENTRY POINTS  (who calls this page)
 * ──────────────────────────────────────────────────────────────
 *  1. DashboardComposer.loadPage("/zul/outward/my-batches.zul")
 *       → called from sidebar "Scan Module" menu item
 *  2. MyBatchesComposer.onCreateBatch()
 *       → sets session["autoOpenBatchModal"] = true
 *       → DashboardComposer.loadPage(".../cheque-scan.zul")
 *
 * ──────────────────────────────────────────────────────────────
 * ZK LIFECYCLE  (call order guaranteed by ZK framework)
 * ──────────────────────────────────────────────────────────────
 *  doAfterCompose(comp)
 *      1. isSessionValid()          → redirect to index.zul if not logged in
 *      2. loadUserInfo()            → populate header labels from session
 *      3. loadBatchesFromService()  → BatchService → DB → in-memory list
 *      4. renderBatches()           → build Listitem rows in lbBatches
 *      5. refreshStats()            → update stat card labels
 *      6. updateBatchCountLabel()   → update "N batches" badge
 *      7. if session["autoOpenBatchModal"] → openBatchModal()
 *
 * ──────────────────────────────────────────────────────────────
 * SCANNING FLOW
 * ──────────────────────────────────────────────────────────────
 *  PATH A -Two-step (create batch first, then scan):
 *  
 *      Step 1: btnOpenBatchModal.onClick → openBatchModal()
 *      
 *      Step 1: btnCreateBatch.onClick   → onCreateBatch()
 *                  → BatchServiceImpl.createBatch() → status=Draft
 *                  → store pendingBatchId
 *                  → openScanModal(batchId)
 *                  
 *      Step 2: btnScanUploadZip.onUpload → onScanZipUpload(UploadEvent)
 *                  → ZipImportServiceImpl.importZip(bytes, name, branch, user, pendingBatchId)
 *                      Case 1: allDuplicates  → discardPendingBatch() → openDuplicateDialog()
 *                      Case 2: count mismatch → store pendingMismatchResult → openMismatchDialog()
 *                      Case 3: clean import   → finishSuccessfulImport()
 *      btnMismatchAccept.onClick  → onMismatchAccept()  → finishSuccessfulImport(pendingMismatchResult)
 *      btnMismatchDiscard.onClick → onMismatchDiscard() → BatchServiceImpl.discardBatch()
 *
 * ──────────────────────────────────────────────────────────────
 * KEY DESIGN DECISIONS
 * ──────────────────────────────────────────────────────────────
 *  • Pure ZK MVC — all overlay/modal visibility driven by setVisible().
 *    No JavaScript innerHTML rebuilds, no DOM manipulation.
 *  • pendingBatchId bridges Step 1 (create) and Step 2 (scan).
 *    Cleared on: successful import, discard, or scan modal close.
 *  • "Pending" in UI = "VerificationInProgressAtMaker" in DB.
 *  • Ghost batch guard: hides VerificationInProgressAtMaker batches
 *    with zero amount and no cheques (leftover from aborted manual creates).
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
 * ZK SelectorComposer for {@code cheque-scan.zul}.
 *
 * <p>
 * Manages the full batch creation and ZIP import workflow for the Maker role.
 * See class-level Javadoc above for the complete two-path import flow and layer
 * architecture diagram.
 *
 * <p>
 * FLOW SUMMARY: cheque-scan.zul → ChequeScanComposer →
 * BatchService/ChequeService/ZipImportService → DAO → DB
 *
 * @author Umesh M.
 * @see ZipImportService
 * @see BatchService
 */
public class ChequeScanComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;

	/** Rows shown per page in the batch table. */
	private static final int BATCH_PAGE_SIZE = 5;

	/**
	 * Logger for import errors, discard events, and session issues. Using class
	 * name so log entries are traceable to this composer.
	 */
	private static final Logger LOG = Logger.getLogger(ChequeScanComposer.class.getName());

	// ── Service layer (manual wiring — no DI framework) ──────────────────
	// FLOW: Composer → Service → DAO → DB (never Composer → DAO directly)
	// BatchService : batch CRUD + submit validation
	// ChequeService : cheque field save + pending count for stat card
	// ZipImportService : orchestrates ZIP parse → dedup → DB persist
	private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
	private final ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());
	private final ZipImportService zipImportService = new ZipImportServiceImpl();

	// ── Session attribute key constants ───────────────────────────────────
	// Set by LoginComposer on successful login; read here for user context
	// and for setting createdBy / branchCode on new batches.
//	private static final String SESS_LOGGED_USER = "loggedUser";
	
	private static final String SESS_USER_NAME = "userName";
	private static final String SESS_USER_ROLE = "userRole";
	private static final String SESS_USER_BRANCH = "userBranch";

	// ── In-memory state ───────────────────────────────────────────────────

	/**
	 * Master batch list — loaded from DB at startup and refreshed after every
	 * import / create / discard operation. Filtered in-memory by
	 * {@link #getFilteredBatches()}.
	 *
	 * FLOW: loadBatchesFromService() → fills this list → getFilteredBatches()
	 * applies UI filters → renderBatches() draws rows
	 */
	private final List<BatchEntity> batches = new ArrayList<>();

	/**
	 * Current page index (1-based) for the batch table pagination controls. Reset
	 * to 1 on every filter change so the user always sees results.
	 */
	private int batchPage = 1;

	/**
	 * Batch ID of the Draft batch created in Step 1 (onCreateBatch), waiting for a
	 * ZIP upload in Step 2 (onScanZipUpload).
	 *
	 * FLOW: onCreateBatch() sets this → onScanZipUpload() reads this →
	 * finishSuccessfulImport() clears this Cleared on: successful import, user
	 * discards, or scan modal closed without upload.
	 */
	private String pendingBatchId = null;

	/**
	 * Holds the ImportResult from onScanZipUpload() when the cheque count does not
	 * match the Maker's declared count.
	 *
	 * FLOW: onScanZipUpload() sets this → openMismatchDialog() shows dialog →
	 * onMismatchAccept() calls finishSuccessfulImport(this) OR onMismatchDiscard()
	 * clears this
	 */
	private ImportResult pendingMismatchResult = null;

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — HEADER + TOP-LEVEL BUTTONS
	// All @Wire fields are injected by ZK after doAfterCompose() is called.
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
	private Button btnScanCancelDiscard;// Legacy discard fallback button

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
	// Shown when ZIP cheque count ≠ declared count from Step 1
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
	// Shown when every cheque in the ZIP already exists in cts_cheques
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
	// Auto-dismissed after 5 seconds via toastTimer (pure ZK MVC, no JS)
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
	// Updated after every import/create/discard operation
	// ══════════════════════════════════════════════════════════════════════

	@Wire
	private Label lblStatBatches; // Count of maker-visible batches
	@Wire
	private Label lblStatCheques; // Sum of totalCheques across those batches
	@Wire
	private Label lblStatPending; // Count of cheques still in Pending status

	// ══════════════════════════════════════════════════════════════════════
	// WIRED ZK COMPONENTS — BATCH TABLE + TOOLBAR
	// lbBatches rows are built dynamically by renderBatches() / appendBatchRow()
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
	 * total). Used in Step 2 to detect ZIP count mismatch.
	 *
	 * FLOW: Maker enters this → onCreateBatch() reads it → BatchServiceImpl stores
	 * it → onScanZipUpload() compares ZIP count against it
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
	// Entry point: ZK calls this once after the ZUL component tree is ready
	// and all @Wire fields are injected.
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * ZK lifecycle entry point — called once after the ZUL component tree is parsed
	 * and all {@code @Wire} fields are injected.
	 *
	 * <h3>Initialisation Order (FLOW)</h3>
	 * <ol>
	 * <li>isSessionValid() → bounce to index.zul if not logged in</li>
	 * <li>loadUserInfo() → read userName/userRole from session → set header
	 * labels</li>
	 * <li>loadBatchesFromService() → BatchService.getAllBatches() → fill in-memory
	 * list</li>
	 * <li>renderBatches() → apply filters → build Listitem rows in lbBatches</li>
	 * <li>refreshStats() → count batches/cheques/pending → update stat cards</li>
	 * <li>updateBatchCountLabel() → update "N batches" badge label</li>
	 * <li>autoOpenBatchModal check → if session flag set, open Create Batch
	 * modal</li>
	 * </ol>
	 *
	 * @param comp root ZK component of cheque-scan.zul
	 */
	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);

		// Step 1: Session guard — redirect immediately if not authenticated
		if (!isSessionValid()) {
			Executions.sendRedirect("index.zul");
			return;
		}

		// Step 2–6: Load data and render UI
		loadUserInfo();
		loadBatchesFromService();
		renderBatches();
		refreshStats();
		updateBatchCountLabel();

		// Step 7: Auto-open create modal when navigated here from My Batches
		// "Create Batch" button (MyBatchesComposer.onCreateBatch() sets this flag).
		Object autoOpenFlag = Sessions.getCurrent().getAttribute("autoOpenBatchModal");
		if (Boolean.TRUE.equals(autoOpenFlag)) {
			Sessions.getCurrent().removeAttribute("autoOpenBatchModal");
			openBatchModal();
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// SESSION — isSessionValid()
	// FLOW: doAfterCompose() → isSessionValid() → if false → redirect
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Returns true if the current HTTP session has an authenticated user.
	 *
	 * FLOW: doAfterCompose() calls this first. The {@code loggedUser} attribute is
	 * set by {@code LoginComposer} on successful login and cleared on logout or
	 * session timeout.
	 *
	 * @return true if loggedUser session attribute is present
	 */
	private boolean isSessionValid() {
	    return com.cts.util.SecurityUtil.getCurrentUser() != null;
	}

	// ══════════════════════════════════════════════════════════════════════
	// HEADER — loadUserInfo()
	// FLOW: doAfterCompose() → loadUserInfo() → reads session → sets header labels
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Reads {@code userName} and {@code userRole} from session and sets the page
	 * header labels. Values are uppercased to match the design spec.
	 *
	 * FLOW: Called from doAfterCompose() during init sequence (Step 2).
	 */
	private void loadUserInfo() {
		if (lblHdrUser != null)
			lblHdrUser.setValue(sessionStr(SESS_USER_NAME, "USER").toUpperCase());
		if (lblHdrRole != null)
			lblHdrRole.setValue(sessionStr(SESS_USER_ROLE, "ADMINISTRATOR").toUpperCase());
	}

	// ══════════════════════════════════════════════════════════════════════
	// DATA LOAD FROM SERVICE
	// FLOW: Composer → BatchService.getAllBatches() → BatchDAO → DB → in-memory
	// list
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Clears the in-memory batch list and reloads all batches from the DB.
	 *
	 * FLOW: loadBatchesFromService() → BatchService.getAllBatches() [Service layer
	 * — no SQL here] → BatchDAOImpl.getAllBatches() [DAO layer — PostgreSQL query]
	 * → cts_batches table [DB] → results stored in this.batches [in-memory,
	 * filtered at render time]
	 *
	 * Called from: doAfterCompose, finishSuccessfulImport, discardPendingBatch,
	 * onMismatchDiscard, onBatchSearch, onBatchStatusFilter, onBatchDateFilter.
	 *
	 * Note: getAllBatches() returns ALL statuses intentionally — Maker-only filter
	 * is applied in getFilteredBatches() at render time.
	 */
	private void loadBatchesFromService() {
		batches.clear();
		try {
			batches.addAll(batchService.getAllBatches());
		} catch (Exception ex) {
			// Show warning but don't crash — user can still see empty table
			Clients.showNotification("⚠ Could not load batches: " + ex.getMessage(), "warning", null, "middle_center",
					4000);
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// PATH A — DIRECT ZIP UPLOAD (no pre-created batch)
	// FLOW: btnUploadZip.onUpload → onZipUpload() → ZipImportService → result
	// handling
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Handles ZIP file upload from the main page upload button (Path A).
	 *
	 * FLOW (Path A — no pre-created batch): onZipUpload(UploadEvent) → validate
	 * file (null check, .zip extension) → readAllBytes(media.getStreamData())
	 * [convert stream to byte[]] → ZipImportServiceImpl.importZip(bytes, name,
	 * branch, user) → CtsZipParserImpl.parse() [extract XML + images from ZIP] →
	 * ChequeDAOImpl.findExistingChequeNos() [dedup check against DB] →
	 * BatchDAOImpl.saveBatch() [create new batch row, status=Draft] →
	 * ChequeDAOImpl.saveCheques() [insert non-duplicate cheque rows] → if
	 * allDuplicates → openDuplicateDialog() [nothing was saved] → else → update
	 * in-memory list → renderBatches() → showSuccessToast()
	 *
	 * Triggered by: {@code onUpload = #btnUploadZip} in cheque-scan.zul
	 *
	 * @param event ZK upload event containing the uploaded media
	 */
	@Listen("onUpload = #btnUploadZip")
	public void onZipUpload(UploadEvent event) {
		Media uploadedMedia = event.getMedia();

		// Validate: null media means upload was cancelled or failed silently
		if (uploadedMedia == null) {
			Clients.showNotification("No file received.", "error", null, "middle_center", 3000);
			return;
		}
		// Validate: only .zip files accepted — reject everything else
		if (!uploadedMedia.getName().toLowerCase().endsWith(".zip")) {
			Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000);
			return;
		}

		Clients.showBusy("Processing ZIP — please wait…");
		try {
			// Read the entire upload stream into a byte array for the parser
			byte[] zipBytes = readAllBytes(uploadedMedia.getStreamData());
			String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");

			// FLOW: Delegate to service layer — full parse → dedup → persist pipeline
			// Path A: no existingBatchId, so service creates a new batch row
			ImportResult importResult = zipImportService.importZip(zipBytes, uploadedMedia.getName(), branchCode,
					createdBy);
			Clients.clearBusy();

			// Case: all cheques already exist — nothing was saved to DB
			if (importResult.isAllDuplicates()) {
				openDuplicateDialog(importResult.getParsedTotal(), importResult.getParsedTotalAmount());
				return;
			}

			// Success: update in-memory list and refresh the UI
			batches.add(0, importResult.getBatch()); // prepend so newest appears first
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
	// FLOW: btnOpenBatchModal.onClick → openBatchModal() → Maker fills form
	// → btnCreateBatch.onClick → onCreateBatch() → service creates Draft batch
	// → openScanModal(batchId) → Step 2 begins
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Clears the cheque-count error highlight when the user modifies the field.
	 *
	 * FLOW: onChange event fires → clear error state so user sees clean input.
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
	 *
	 * FLOW: onChange event fires → clear error state so user sees clean input.
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
	 * FLOW (Path B Step 1): onCreateBatch() → disable button (prevent double-click
	 * during DB call) → clear previous error state → read txtChequeCount and
	 * txtExpectedAmount values → validate both fields (collect ALL errors before
	 * returning) → if invalid → show errors → re-enable button → return →
	 * closeBatchModal() → BatchServiceImpl.createBatch(branch, count, expectedAmt,
	 * user) → BatchDAOImpl.loadMaxBatchSeq() [generate next BATCH{n} ID] →
	 * BatchDAOImpl.saveBatch() [status=Draft, no cheques yet] → pendingBatchId =
	 * batch.getBatchId() [bridge to Step 2] → add to in-memory list →
	 * renderBatches() → refreshStats() → openScanModal(batchId) [Step 2 begins]
	 *
	 * Called from: {@code onClick = #btnCreateBatch}
	 */
	@Listen("onClick = #btnCreateBatch")
	public void onCreateBatch() {
		// Disable immediately to prevent double-click during DB call
		if (btnCreateBatch != null)
			btnCreateBatch.setDisabled(true);

		// Clear all previous validation error state before re-validating
		if (errChequeCount != null)
			errChequeCount.setValue("");
		if (errExpectedAmount != null)
			errExpectedAmount.setValue("");
		if (txtChequeCount != null)
			txtChequeCount.setSclass("mf-input");
		if (txtExpectedAmount != null)
			txtExpectedAmount.setSclass("mf-input");

		// Read field values (null means the user left the field empty)
		Integer rawChequeCount = (txtChequeCount != null) ? txtChequeCount.getValue() : null;
		BigDecimal rawAmount = (txtExpectedAmount != null) ? txtExpectedAmount.getValue() : null;
		int chequeCount = (rawChequeCount != null) ? rawChequeCount : 0;
		BigDecimal controlAmt = (rawAmount != null) ? rawAmount : BigDecimal.ZERO;

		// Validate both fields — collect ALL errors before returning (UX: show all at
		// once)
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

		// Stop here if validation failed — re-enable button so user can fix and retry
		if (hasValidationError) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			return;
		}

		closeBatchModal();

		try {
			String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");

			// FLOW: Composer → BatchService.createBatch() → BatchDAO → DB
			// Returns entity with auto-generated batchId (e.g. BATCH0123)
			BatchEntity newBatch = batchService.createBatch(branchCode, chequeCount, controlAmt, createdBy);

			// Store batchId as the bridge between Step 1 and Step 2
			pendingBatchId = newBatch.getBatchId();

			// Optimistically prepend to in-memory list (avoids full DB reload)
			batches.add(0, newBatch);
			renderBatches();
			refreshStats();
			updateBatchCountLabel();

			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);

			// FLOW: Proceed to Step 2 — scan modal waits for ZIP upload
			openScanModal(newBatch.getBatchId());

		} catch (Exception ex) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			Clients.showNotification("❌ Could not create batch: " + ex.getMessage(), "error", null, "middle_center",
					5000);
		}
	}

	/**
	 * Navigates to the My Batches page via DashboardComposer.
	 *
	 * FLOW: onClick → DashboardComposer.loadPage() → my-batches.zul renders in
	 * content area Called from: {@code onClick = #btnViewBatches}
	 */
	@Listen("onClick = #btnViewBatches")
	public void onViewBatches() {
		com.cts.composer.DashboardComposer.navigateTo("/zul/outward/my-batches.zul");
	}

	/**
	 * Closes the scan modal and discards the pending (empty) Draft batch.
	 *
	 * FLOW: × button clicked → closeScanModal() → discardPendingBatch()
	 * discardPendingBatch() → BatchService.discardBatch() →
	 * BatchDAO.deleteBatchAndCheques() Called when the user exits the scan modal
	 * without uploading a ZIP. Called from: {@code onClick = #btnCloseScanModal}
	 */
	@Listen("onClick = #btnCloseScanModal")
	public void onCloseScanModal() {
		closeScanModal();
		discardPendingBatch(); // deletes the empty Draft batch row created in Step 1
	}

	/**
	 * Legacy fallback discard button — discards the pending batch. Retained for
	 * backward compatibility with existing ZUL event wiring.
	 *
	 * FLOW: onClick → discardPendingBatch() → BatchService → DAO → DB delete Called
	 * from: {@code onClick = #btnScanCancelDiscard}
	 */
	@Listen("onClick = #btnScanCancelDiscard")
	public void onScanCancelDiscard() {
		discardPendingBatch();
	}

	// ══════════════════════════════════════════════════════════════════════
	// CREATE BATCH MODAL — OPEN / CLOSE
	// FLOW: button clicks → these methods → batchModal.setVisible(true/false)
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Opens the Create Batch modal (Step 1). FLOW: onClick → openBatchModal() →
	 * clears inputs → modal visible Called from:
	 * {@code onClick = #btnOpenBatchModal}
	 */
	@Listen("onClick = #btnOpenBatchModal")
	public void onOpenBatchModal() {
		openBatchModal();
	}

	/**
	 * Closes the Create Batch modal via the header × button. FLOW: onClick →
	 * closeBatchModal() → modal hidden Called from:
	 * {@code onClick = #btnCloseBatchModal}
	 */
	@Listen("onClick = #btnCloseBatchModal")
	public void onCloseBatchModal() {
		closeBatchModal();
	}

	/**
	 * Closes the Create Batch modal via the footer Cancel button. FLOW: onClick →
	 * closeBatchModal() → modal hidden Called from:
	 * {@code onClick = #btnCancelBatchModal}
	 */
	@Listen("onClick = #btnCancelBatchModal")
	public void onCancelBatchModal() {
		closeBatchModal();
	}

	/**
	 * Resets all modal inputs to blank state and shows the Create Batch modal.
	 *
	 * FLOW: reset txtChequeCount + txtExpectedAmount + error labels →
	 * batchModal.setVisible(true) → ZK re-renders overlay on client
	 *
	 * Called by: onOpenBatchModal(), doAfterCompose() (autoOpenBatchModal flag).
	 */
	private void openBatchModal() {
		// Reset inputs so Maker always sees a clean form (not stale from last use)
		if (txtChequeCount != null) {
			txtChequeCount.setValue((Integer) null);
			txtChequeCount.setSclass("mf-input");
		}
		if (txtExpectedAmount != null) {
			txtExpectedAmount.setValue((java.math.BigDecimal) null);
			txtExpectedAmount.setSclass("mf-input");
		}
		if (errChequeCount != null)
			errChequeCount.setValue("");
		if (errExpectedAmount != null)
			errExpectedAmount.setValue("");
		if (batchModal != null)
			batchModal.setVisible(true);
	}

	/**
	 * Hides the Create Batch modal. FLOW: batchModal.setVisible(false) → ZK hides
	 * overlay on client
	 */
	private void closeBatchModal() {
		if (batchModal != null)
			batchModal.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// SCAN MODAL — OPEN / CLOSE / PROGRESS
	// FLOW: openScanModal() → visible=true → Maker uploads ZIP → onScanZipUpload()
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Shows the scan modal (Step 2) and displays the pending batch ID in the
	 * header.
	 *
	 * FLOW: onCreateBatch() → openScanModal(batchId) → modal visible with batch ID
	 * label
	 *
	 * @param batchId the batch ID created in Step 1 (displayed in modal header so
	 *                the Maker knows which batch they are scanning into)
	 */
	private void openScanModal(String batchId) {
		if (scanBatchIdLabel != null)
			scanBatchIdLabel.setValue(batchId != null ? batchId : "—");
		hideScanProgress(); // always reset progress bar before showing modal
		if (scanModal != null)
			scanModal.setVisible(true);
	}

	/**
	 * Hides the scan modal and resets the progress bar to zero. FLOW:
	 * closeScanModal() → modal hidden → hideScanProgress() resets bar
	 */
	private void closeScanModal() {
		if (scanModal != null)
			scanModal.setVisible(false);
		hideScanProgress();
	}

	/**
	 * Shows the progress bar at ~90% fill with a status message.
	 *
	 * FLOW: Called immediately after receiving the ZIP upload to give visual
	 * feedback during the synchronous parse + DB persist operation. (90% fill, not
	 * 100%, because we don't know actual progress — set to 100% on completion)
	 *
	 * @param statusMessage text to show in the progress label (e.g. "Processing
	 *                      ZIP…")
	 */
	private void showScanProgress(String statusMessage) {
		if (scanProgress != null)
			scanProgress.setVisible(true);
		if (scanProgressText != null)
			scanProgressText.setValue(statusMessage != null ? statusMessage : "Scanning…");
		if (scanProgressFill != null)
			scanProgressFill.setStyle("width:90%;"); // 90% = "almost done" placeholder
	}

	/**
	 * Hides the progress bar and resets its fill width to 0%. FLOW: Called after
	 * import completes (success or error) to clean up UI state.
	 */
	private void hideScanProgress() {
		if (scanProgress != null)
			scanProgress.setVisible(false);
		if (scanProgressFill != null)
			scanProgressFill.setStyle("width:0%;"); // reset for next use
	}

	// ══════════════════════════════════════════════════════════════════════
	// MISMATCH DIALOG — OPEN / CLOSE
	// FLOW: onScanZipUpload() detects mismatch → openMismatchDialog() → user
	// decides
	// → btnMismatchAccept → finishSuccessfulImport()
	// → btnMismatchDiscard → discardBatch()
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Populates and shows the cheque-count mismatch dialog.
	 *
	 * FLOW: onScanZipUpload() (Case 2 — count mismatch) → openMismatchDialog() →
	 * populate all label fields with expected vs actual counts → conditionally
	 * show/hide duplicate row → mismatchDialog.setVisible(true)
	 *
	 * @param expectedChequeCount cheque count entered by Maker in Step 1
	 * @param controlAmount       control amount entered by Maker in Step 1
	 * @param parsedChequeCount   total cheques found inside the ZIP
	 * @param parsedTotalAmount   total amount parsed from ZIP cheque records
	 * @param savedChequeCount    new cheques actually saved (after duplicate
	 *                            removal)
	 * @param skippedDuplicates   cheques skipped because they already exist in
	 *                            system
	 */
	private void openMismatchDialog(int expectedChequeCount, BigDecimal controlAmount, int parsedChequeCount,
			BigDecimal parsedTotalAmount, int savedChequeCount, int skippedDuplicates) {

		// Populate all comparison fields in the dialog
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

		// Only show duplicate count row when there were actually skipped cheques
		// (hiding empty rows keeps the dialog clean and unambiguous)
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

	/**
	 * Hides the mismatch dialog. FLOW: Called by onMismatchAccept() or
	 * onMismatchDiscard() after user decides.
	 */
	private void closeMismatchDialog() {
		if (mismatchDialog != null)
			mismatchDialog.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// ALL-DUPLICATES DIALOG — OPEN / CLOSE
	// FLOW: importZip() returns allDuplicates=true → openDuplicateDialog() → user
	// clicks OK
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Dismisses the "All Cheques Already Present" dialog.
	 *
	 * FLOW: btnDuplicateOk.onClick → closeDuplicateDialog() → dialog hidden Called
	 * from: {@code onClick = #btnDuplicateOk}
	 */
	@Listen("onClick = #btnDuplicateOk")
	public void onDuplicateOk() {
		closeDuplicateDialog();
	}

	/**
	 * Populates and shows the "All Cheques Already Present" dialog.
	 *
	 * FLOW: onZipUpload() or onScanZipUpload() (Case 1: allDuplicates) →
	 * openDuplicateDialog(count, amount) → populate label fields →
	 * duplicateDialog.setVisible(true)
	 *
	 * This dialog informs the Maker that nothing was saved — no batch row was
	 * created/modified because every cheque in the ZIP already exists in
	 * cts_cheques.
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

	/**
	 * Hides the all-duplicates dialog. FLOW: Called by onDuplicateOk() after user
	 * acknowledges.
	 */
	private void closeDuplicateDialog() {
		if (duplicateDialog != null)
			duplicateDialog.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// SUCCESS TOAST — OPEN / CLOSE
	// FLOW: finishSuccessfulImport() → showSuccessToast() → toast visible
	// → user clicks × or 5s timer fires → closeSuccessToast() → hidden
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * User clicks "✕ Close" inside the toast body. FLOW: onClick →
	 * closeSuccessToast() → stop timer → toast hidden Called from:
	 * {@code onClick = #btnToastDismiss}
	 */
	@Listen("onClick = #btnToastDismiss")
	public void onToastDismiss() {
		closeSuccessToast();
	}

	/**
	 * User clicks the small × corner button on the toast. FLOW: onClick →
	 * closeSuccessToast() → stop timer → toast hidden Called from:
	 * {@code onClick = #btnToastClose}
	 */
	@Listen("onClick = #btnToastClose")
	public void onToastClose() {
		closeSuccessToast();
	}

	/**
	 * Auto-dismiss timer fires 5 seconds after the toast was shown. FLOW:
	 * toastTimer fires (delay=5000ms) → onToastTimer() → closeSuccessToast() Called
	 * from: {@code onTimer = #toastTimer}
	 */
	@Listen("onTimer = #toastTimer")
	public void onToastTimer() {
		closeSuccessToast();
	}

	/**
	 * Populates and shows the green "Batch Created Successfully" toast.
	 *
	 * FLOW: finishSuccessfulImport() → showSuccessToast(batchId, count, amount) →
	 * force-close any lingering overlays (prevents z-index conflicts) → populate
	 * toast labels → fire ZK built-in notification (guaranteed fallback if custom
	 * toast fails) → batchSuccessToast.setVisible(true) →
	 * toastTimer.setRunning(true) → auto-dismiss after 5s
	 *
	 * @param batchId     the created / updated batch ID
	 * @param chequeCount number of cheques in the batch
	 * @param amountStr   pre-formatted amount string without ₹ symbol
	 */
	private void showSuccessToast(String batchId, int chequeCount, String amountStr) {
		// Force-close any overlay that may still be lingering from the import flow
		if (scanModal != null)
			scanModal.setVisible(false);
		if (batchModal != null)
			batchModal.setVisible(false);
		if (mismatchDialog != null)
			mismatchDialog.setVisible(false);
		if (duplicateDialog != null)
			duplicateDialog.setVisible(false);

		// Populate toast content
		if (lblToastBatchId != null)
			lblToastBatchId.setValue(nullSafe(batchId, "—"));
		if (lblToastChequeCount != null)
			lblToastChequeCount.setValue(chequeCount + " cheque" + (chequeCount != 1 ? "s" : ""));
		if (lblToastAmount != null)
			lblToastAmount.setValue("₹" + amountStr);

		LOG.info("showSuccessToast: batchId=" + batchId + " count=" + chequeCount + " amt=" + amountStr + " toastDiv="
				+ (batchSuccessToast != null));

		// ZK built-in notification — guaranteed visible even if custom toast Div fails
		// to render
		Clients.showNotification("✅ Batch " + nullSafe(batchId, "—") + " created — " + chequeCount + " cheque"
				+ (chequeCount != 1 ? "s" : "") + " · ₹" + amountStr, "info", null, "top_center", 4000);

		if (batchSuccessToast != null)
			batchSuccessToast.setVisible(true);

		// Restart the auto-dismiss timer (pure ZK MVC — no JS setTimeout needed)
		if (toastTimer != null) {
			toastTimer.setRunning(false); // stop first in case it was already running
			toastTimer.setRunning(true); // start fresh 5s countdown
		}
	}

	/**
	 * Hides the success toast and stops the auto-dismiss timer. FLOW: called by
	 * dismiss buttons or toastTimer → stop timer → hide toast
	 */
	private void closeSuccessToast() {
		if (toastTimer != null)
			toastTimer.setRunning(false);
		if (batchSuccessToast != null)
			batchSuccessToast.setVisible(false);
	}

	// ══════════════════════════════════════════════════════════════════════
	// PATH B STEP 2 — SCAN MODAL ZIP UPLOAD
	// FLOW: Maker uploads ZIP in scan modal → onScanZipUpload() → 3 possible
	// outcomes
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Handles ZIP upload inside the scan modal (Path B Step 2).
	 *
	 * FLOW (Path B Step 2 — has pre-created batch via pendingBatchId):
	 * onScanZipUpload(UploadEvent) → validate file (null check, .zip extension) →
	 * showScanProgress("Processing ZIP…") → readAllBytes(media.getStreamData()) →
	 * ZipImportServiceImpl.importZip(bytes, name, branch, user, pendingBatchId)
	 * [Unlike Path A: cheques are linked to pendingBatchId, no new batch row
	 * created] → CtsZipParserImpl.parse() [extract XML + images from ZIP] →
	 * ChequeDAOImpl.findExistingChequeNos() [dedup check] →
	 * BatchDAOImpl.updateBatch() [set totalCheques + totalAmount on existing row] →
	 * ChequeDAOImpl.saveCheques() [insert non-duplicate cheque rows]
	 *
	 * Case 1 — All Duplicates: → hideScanProgress() → closeScanModal() →
	 * discardPendingBatch() [delete empty Draft batch from DB] →
	 * openDuplicateDialog() [inform Maker nothing was saved]
	 *
	 * Case 2 — Count Mismatch (ZIP count ≠ expectedChequeCount): →
	 * pendingMismatchResult = importResult [stash for Accept/Discard] →
	 * hideScanProgress() → closeScanModal() → openMismatchDialog() [Maker chooses
	 * Accept or Discard]
	 *
	 * Case 3 — Clean Import (counts match): → hideScanProgress() → closeScanModal()
	 * → finishSuccessfulImport(importResult) [all 3 paths converge here]
	 *
	 * Called from: {@code onUpload = #btnScanUploadZip} in cheque-scan.zul
	 *
	 * @param event ZK upload event containing the uploaded ZIP media
	 */
	@Listen("onUpload = #btnScanUploadZip")
	public void onScanZipUpload(UploadEvent event) {
		Media uploadedMedia = event.getMedia();

		// Validate: null media means upload was cancelled or failed silently
		if (uploadedMedia == null) {
			Clients.showNotification("No file received.", "error", null, "middle_center", 3000);
			return;
		}
		// Validate: only .zip files accepted
		if (!uploadedMedia.getName().toLowerCase().endsWith(".zip")) {
			Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000);
			return;
		}

		// Show progress immediately — parse+persist is synchronous and can take 2–10s
		showScanProgress("Processing ZIP — please wait…");
		Clients.showBusy("Processing ZIP…");

		try {
			byte[] zipBytes = readAllBytes(uploadedMedia.getStreamData());
			String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");

			// FLOW: Pass pendingBatchId so service links cheques to existing batch row
			// (instead of creating a new batch row like Path A does)
			ImportResult importResult = zipImportService.importZip(zipBytes, uploadedMedia.getName(), branchCode,
					createdBy, pendingBatchId);
			Clients.clearBusy();

			// ── Case 1: ALL cheques were duplicates ──────────────────────
			// Service returned without saving anything. Clean up the empty Draft batch.
			if (importResult.isAllDuplicates()) {
				hideScanProgress();
				closeScanModal();
				discardPendingBatch(); // delete the now-useless Draft batch row from DB
				openDuplicateDialog(importResult.getParsedTotal(), importResult.getParsedTotalAmount());
				return;
			}

			// ── Case 2: Cheque count mismatch ───────────────────────────
			// ZIP contained a different number of valid cheques than the Maker declared.
			// Cheques are already saved at this point — Maker decides Accept or Discard.
			BatchEntity pendingBatchEntity = batches.stream().filter(b -> b.getBatchId().equals(pendingBatchId))
					.findFirst().orElse(null);

			int expectedChequeCount = (pendingBatchEntity != null) ? pendingBatchEntity.getExpectedCheques() : 0;
			BigDecimal declaredControlAmt = (pendingBatchEntity != null
					&& pendingBatchEntity.getExpectedAmount() != null) ? pendingBatchEntity.getExpectedAmount()
							: BigDecimal.ZERO;

			int actualSavedCount = importResult.getCheques().size(); // non-duplicate cheques persisted to DB
			int totalParsedCount = importResult.getParsedTotal(); // total cheques found in ZIP (including dups)

			if (expectedChequeCount > 0 && actualSavedCount != expectedChequeCount) {
				// Stash result so onMismatchAccept() can call finishSuccessfulImport() with it
				pendingMismatchResult = importResult;
				hideScanProgress();
				closeScanModal();
				openMismatchDialog(expectedChequeCount, declaredControlAmt, totalParsedCount,
						importResult.getParsedTotalAmount(), actualSavedCount, importResult.getSkippedDuplicates());
				return;
			}

			// ── Case 3: Clean import — all counts match ──────────────────
			hideScanProgress();
			closeScanModal();
			finishSuccessfulImport(importResult); // all paths converge at finishSuccessfulImport

		} catch (Exception ex) {
			Clients.clearBusy();
			hideScanProgress();
			closeScanModal();
			String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			LOG.severe("onScanZipUpload error: " + errorMsg);
			Clients.showNotification("❌ Upload failed: " + errorMsg, "error", null, "middle_center", 6000);
			pendingBatchId = null; // clear so next Create Batch attempt starts clean
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// MISMATCH DIALOG — ACCEPT / DISCARD
	// FLOW: onMismatchAccept() → finishSuccessfulImport(pendingMismatchResult)
	// onMismatchDiscard() → BatchService.discardBatch() → DB delete → UI refresh
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * User accepts the mismatch — proceeds with the actual imported cheque count.
	 *
	 * FLOW: btnMismatchAccept.onClick → onMismatchAccept() → closeMismatchDialog()
	 * → finishSuccessfulImport(pendingMismatchResult) [Cheques already saved in DB
	 * at this point — just update UI + show toast] → pendingMismatchResult = null
	 * [clear bridge state]
	 *
	 * Note: No additional DB write needed here — importZip() already persisted the
	 * cheques during onScanZipUpload(). Accept just approves the UI flow.
	 *
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
	 * FLOW: btnMismatchDiscard.onClick → onMismatchDiscard() →
	 * closeMismatchDialog() → BatchServiceImpl.discardBatch(pendingBatchId) →
	 * BatchDAOImpl.deleteBatchAndCheques() [CASCADE DELETE in DB] → remove from
	 * in-memory batches list → renderBatches() → refreshStats() →
	 * updateBatchCountLabel() → show warning notification
	 *
	 * Note: pendingBatchId is cleared BEFORE the DB call to prevent re-entry if an
	 * exception triggers a second discard attempt.
	 *
	 * Called from: {@code onClick = #btnMismatchDiscard}
	 */
	@Listen("onClick = #btnMismatchDiscard")
	public void onMismatchDiscard() {
		closeMismatchDialog();

		String batchToDelete = pendingBatchId;
		pendingMismatchResult = null;
		pendingBatchId = null; // clear BEFORE DB call to prevent re-entry on exception

		if (batchToDelete != null) {
			try {
				// FLOW: Composer → BatchService.discardBatch() →
				// BatchDAO.deleteBatchAndCheques() → DB
				batchService.discardBatch(batchToDelete);
				batches.removeIf(b -> b.getBatchId().equals(batchToDelete)); // sync in-memory list
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
	// SHARED SUCCESS FINISH — all import paths converge here
	// FLOW: Path A (direct) / Path B Case 3 (clean) / Path B Accept (mismatch)
	// → finishSuccessfulImport(ImportResult) → reload DB → refresh UI → toast
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Common end-state for all successful import paths:
	 * <ul>
	 * <li>Path A direct upload (no mismatch)</li>
	 * <li>Path B scan modal (clean import, Case 3)</li>
	 * <li>Path B mismatch accepted (onMismatchAccept)</li>
	 * </ul>
	 *
	 * FLOW: finishSuccessfulImport(ImportResult) → loadBatchesFromService() [full
	 * DB reload for authoritative totals] → renderBatches() [rebuild batch table
	 * rows] → refreshStats() [update stat card numbers] → updateBatchCountLabel()
	 * [update "N batches" badge] → pendingBatchId = null [clear bridge state] →
	 * showSuccessToast(...) [green success toast + ZK notification]
	 *
	 * Full DB reload (not optimistic update) is used here because importZip() may
	 * have updated totalCheques/totalAmount on the batch row, and we need the
	 * authoritative DB values.
	 *
	 * @param importResult the result returned by ZipImportServiceImpl
	 */
	private void finishSuccessfulImport(ImportResult importResult) {
		// Full reload from DB — ensures totalCheques, totalAmount, status are
		// up-to-date
		loadBatchesFromService();
		renderBatches();
		refreshStats();
		updateBatchCountLabel();
		pendingBatchId = null; // clear the Step 1 → Step 2 bridge

		String formattedAmount = importResult.getBatch().getTotalAmount() != null
				? String.format("%,.2f", importResult.getBatch().getTotalAmount())
				: "0.00";
		showSuccessToast(importResult.getBatch().getBatchId(), importResult.getCheques().size(), formattedAmount);
	}

	// ══════════════════════════════════════════════════════════════════════
	// MISC LISTENERS
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Logout: invalidates the ZK session and redirects to the login page.
	 *
	 * FLOW: btnLogout.onClick → Sessions.invalidate() →
	 * Executions.sendRedirect("index.zul") Called from:
	 * {@code onClick = #btnLogout}
	 *
	 * @param event ZK click event (unused, required by @Listen signature)
	 */
	@Listen("onClick = #btnLogout")
	public void onLogout(Event event) {
		Sessions.getCurrent().invalidate();
		Executions.sendRedirect("index.zul");
	}

	// ══════════════════════════════════════════════════════════════════════
	// FILTER / SEARCH / PAGINATION LISTENERS
	// FLOW: user interaction → listener → reset batchPage = 1 → renderBatches()
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Live search on batch ID or branch code. Both onChange (blur) and onChanging
	 * (keystroke) fire so search is instant.
	 *
	 * FLOW: keystroke/blur → onBatchSearch() → batchPage=1 → renderBatches() →
	 * getFilteredBatches() applies text filter on in-memory list (no DB call)
	 * Called from: {@code onChange = #txtBatchSearch; onChanging = #txtBatchSearch}
	 */
	@Listen("onChange = #txtBatchSearch; onChanging = #txtBatchSearch")
	public void onBatchSearch() {
		batchPage = 1; // always reset to page 1 so user sees results, not empty last page
		renderBatches();
	}

	/**
	 * Status combo filter (All Status / Draft / Pending).
	 *
	 * FLOW: user selects → onBatchStatusFilter() → batchPage=1 → renderBatches()
	 * Called from: {@code onSelect = #cmbBatchStatus}
	 */
	@Listen("onSelect = #cmbBatchStatus")
	public void onBatchStatusFilter() {
		batchPage = 1;
		renderBatches();
	}

	/**
	 * Date-range filter on batch createdAt date.
	 *
	 * FLOW: date selected → onBatchDateFilter() → batchPage=1 → renderBatches()
	 * Called from: {@code onChange = #dtBatchFrom; onChange = #dtBatchTo}
	 */
	@Listen("onChange = #dtBatchFrom; onChange = #dtBatchTo")
	public void onBatchDateFilter() {
		batchPage = 1;
		renderBatches();
	}

	/**
	 * Clears both date pickers and re-renders the table.
	 *
	 * FLOW: onClick → clear dtBatchFrom + dtBatchTo → batchPage=1 → renderBatches()
	 * Called from: {@code onClick = #btnBatchClearDate}
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

	/** Navigate to first page of batch table. */
	@Listen("onClick = #btnBatchPgFirst")
	public void onBatchPgFirst() {
		batchPage = 1;
		renderBatches();
	}

	/** Navigate to previous page (clamps at page 1). */
	@Listen("onClick = #btnBatchPgPrev")
	public void onBatchPgPrev() {
		if (batchPage > 1) {
			batchPage--;
			renderBatches();
		}
	}

	/** Navigate to next page. */
	@Listen("onClick = #btnBatchPgNext")
	public void onBatchPgNext() {
		batchPage++;
		renderBatches();
	}

	/** Navigate to last page. */
	@Listen("onClick = #btnBatchPgLast")
	public void onBatchPgLast() {
		List<BatchEntity> filteredBatches = getFilteredBatches();
		int totalPages = Math.max(1, (int) Math.ceil((double) filteredBatches.size() / BATCH_PAGE_SIZE));
		batchPage = totalPages;
		renderBatches();
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH TABLE RENDER
	// FLOW: renderBatches() → getFilteredBatches() → paginate → appendBatchRow()
	// per batch
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Applies all active filters to the in-memory {@link #batches} list and returns
	 * only the batches the Maker should see.
	 *
	 * FLOW: renderBatches() → getFilteredBatches() → Filter 1: Maker status gate
	 * [Draft + VerificationInProgressAtMaker only] → Filter 2: Ghost batch removal
	 * [exclude empty VerificationInProgressAtMaker rows] → Filter 3: Text search
	 * [ILIKE on batchId and branchCode, in-memory] → Filter 4: Status combo
	 * ["Pending" UI = "VerificationInProgressAtMaker" DB] → Filter 5: Date range
	 * [on batch.createdAt]
	 *
	 * Note: All filtering is done in-memory on the cached list — no DB call per
	 * filter. DB is only reloaded by loadBatchesFromService() on explicit refresh
	 * triggers.
	 *
	 * @return filtered list of batches for the current UI filter state
	 */
	private List<BatchEntity> getFilteredBatches() {

		// ── Filter 1: Maker status gate ────────────────────────────────────
		// Only show Draft and VerificationInProgressAtMaker — hide all others
		// (e.g. VerifiedByMaker, SentToVerifier1 etc. are not Maker's concern here)
		List<BatchEntity> makerVisibleBatches = batches.stream().filter(batch -> {
			String dbStatus = batch.getStatus();
			boolean isMakerVisible = (dbStatus == null) || "Draft".equalsIgnoreCase(dbStatus)
					|| "VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus);
			if (!isMakerVisible)
				return false;

			// ── Filter 2: Ghost batch guard ────────────────────────────────
			// VerificationInProgressAtMaker with zero amount and no cheques =
			// aborted manual create (Step 1 done, scan modal closed without upload).
			// Hide these to prevent Maker confusion.
			return !("VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus)
					&& (batch.getTotalAmount() == null || batch.getTotalAmount().compareTo(BigDecimal.ZERO) == 0)
					&& loadChequesForBatch(batch.getBatchId()).isEmpty());
		}).collect(java.util.stream.Collectors.toList());

		// ── Filter 3: Text search ─────────────────────────────────────────
		// Case-insensitive contains match on batchId or branchCode
		String searchTerm = (txtBatchSearch != null && txtBatchSearch.getValue() != null)
				? txtBatchSearch.getValue().trim().toLowerCase()
				: "";
		if (!searchTerm.isEmpty()) {
			makerVisibleBatches = makerVisibleBatches.stream()
					.filter(b -> (b.getBatchId() != null && b.getBatchId().toLowerCase().contains(searchTerm))
							|| (b.getBranchCode() != null && b.getBranchCode().toLowerCase().contains(searchTerm)))
					.collect(java.util.stream.Collectors.toList());
		}

		// ── Filter 4: Status combo ────────────────────────────────────────
		// "Pending" in UI maps to "VerificationInProgressAtMaker" in DB
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

		// ── Filter 5: Date range ──────────────────────────────────────────
		// Compare batch.createdAt (LocalDateTime) against Datebox values
		// (java.util.Date)
		java.util.Date fromDate = (dtBatchFrom != null) ? dtBatchFrom.getValue() : null;
		java.util.Date toDate = (dtBatchTo != null) ? dtBatchTo.getValue() : null;
		if (fromDate != null || toDate != null) {
			makerVisibleBatches = makerVisibleBatches.stream().filter(batch -> {
				java.util.Date batchCreatedDate = parseBatchDate(batch.getCreatedAt());
				if (batchCreatedDate == null)
					return true; // null dates pass through (no data = no filter)
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
	 * FLOW: getFilteredBatches() Filter 5 → parseBatchDate() → java.util.Date
	 *
	 * @param localDateTime the batch creation timestamp from BatchEntity
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
	 * FLOW: renderBatches() → lbBatches.getItems().clear() [wipe existing rows] →
	 * getFilteredBatches() [apply all active filters] → paginate (batchPage ×
	 * BATCH_PAGE_SIZE) → updateBatchCountLabel(total) [update "N batches" badge] →
	 * updateBatchPagination(total, pages) [enable/disable nav buttons] → if empty →
	 * append empty-state row → else → appendBatchRow(batch) for each batch on
	 * current page
	 */
	private void renderBatches() {
		if (lbBatches == null)
			return;
		lbBatches.getItems().clear();

		List<BatchEntity> filteredBatches = getFilteredBatches();
		int totalBatchCount = filteredBatches.size();
		int totalPages = Math.max(1, (int) Math.ceil((double) totalBatchCount / BATCH_PAGE_SIZE));

		// Clamp current page to valid range — filter changes can make current page
		// invalid
		batchPage = Math.max(1, Math.min(batchPage, totalPages));

		int startIndex = (batchPage - 1) * BATCH_PAGE_SIZE;
		int endIndex = Math.min(startIndex + BATCH_PAGE_SIZE, totalBatchCount);
		List<BatchEntity> currentPage = (startIndex < totalBatchCount) ? filteredBatches.subList(startIndex, endIndex)
				: new ArrayList<>();

		updateBatchCountLabel(totalBatchCount);
		updateBatchPagination(totalBatchCount, totalPages);

		// Empty state — single row spanning all columns with descriptive message
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

		// Render one row per batch on current page
		for (BatchEntity batch : currentPage) {
			appendBatchRow(batch);
		}
	}

	/**
	 * Updates the pagination bar: "Page X of Y" label and First/Prev/Next/Last
	 * button states.
	 *
	 * FLOW: renderBatches() → updateBatchPagination() → enable/disable nav buttons
	 *
	 * @param totalBatchCount total matching batches (used for display calculation)
	 * @param totalPages      total page count (minimum 1)
	 */
	private void updateBatchPagination(int totalBatchCount, int totalPages) {
		if (lblBatchPgInfo != null)
			lblBatchPgInfo.setValue("Page " + batchPage + " of " + totalPages);
		// Disable First/Prev on page 1; disable Next/Last on last page
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
	 * FLOW: renderBatches() → appendBatchRow(batch) per filtered+paginated batch →
	 * create Listitem → add 6 Listcell columns → attach onClick listener →
	 * navigateToBatchDetail(batch) → lbBatches.appendChild(row)
	 *
	 * Columns: Batch ID (link) | Cheque Count | Total Amount | Created Date |
	 * Status chip | Action link
	 *
	 * @param batch the BatchEntity to render as a table row
	 */
	private void appendBatchRow(BatchEntity batch) {
		Listitem row = new Listitem();
		row.setValue(batch); // store entity so onClick can retrieve it
		row.setSclass("mb-row");

		// Column 1: Batch ID — styled as a clickable link (visual affordance for row
		// click)
		Listcell idCell = new Listcell();
		Label idLabel = new Label(nullSafe(batch.getBatchId(), "—"));
		idLabel.setSclass("mb-link");
		idCell.appendChild(idLabel);
		row.appendChild(idCell);

		// Column 2: Cheque count (plain number)
		row.appendChild(new Listcell(String.valueOf(batch.getTotalCheques())));

		// Column 3: Total amount (right-aligned monospace via amt-cell CSS class)
		Listcell amtCell = new Listcell();
		amtCell.setSclass("amt-cell");
		amtCell.appendChild(new Label(formatAmt(batch.getTotalAmount())));
		row.appendChild(amtCell);

		// Column 4: Created date (DD/MM/YYYY format)
		row.appendChild(new Listcell(formatBatchDate(batch.getCreatedAt())));

		// Column 5: Status chip (colored badge — Draft=amber, Pending=blue-grey)
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
	 * FLOW: appendBatchRow() → mapDisplayStatus() → displayed in status chip Label
	 *
	 * Mapping: null / "Draft" → "Draft" "VerificationInProgressAtMaker" → "Pending"
	 * anything else → returned as-is
	 *
	 * @param dbStatus raw status string from cts_batches.status column
	 * @return display label for the UI status chip
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
	 * FLOW: appendBatchRow() → mbStatusChip() → applied as Label sclass
	 *
	 * Mapping: Draft → "chip ch-amber" (yellow) VerificationInProgressAtMaker →
	 * "chip ch-pending" (blue-grey) others → "chip ch-slate" (grey)
	 *
	 * @param dbStatus raw status string from cts_batches.status column
	 * @return CSS sclass string for the chip Label
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
	 * FLOW: appendBatchRow() → formatBatchDate() → displayed in Created Date column
	 *
	 * @param localDateTime the batch creation timestamp; null → "—"
	 * @return formatted date string or "—" for null
	 */
	private String formatBatchDate(java.time.LocalDateTime localDateTime) {
		if (localDateTime == null)
			return "—";
		return String.format("%02d/%02d/%04d", localDateTime.getDayOfMonth(), localDateTime.getMonthValue(),
				localDateTime.getYear());
	}

	// ══════════════════════════════════════════════════════════════════════
	// BATCH ROW CLICK — NAVIGATE TO BATCH DETAIL
	// FLOW: appendBatchRow() onClick → navigateToBatchDetail() → batch-detail.zul
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Stores the selected batch ID in the HTTP session and navigates to the Batch
	 * Detail screen via DashboardComposer.
	 *
	 * FLOW: Listitem.onClick → navigateToBatchDetail(batch) →
	 * Sessions.setAttribute("selectedBatchId", batchId) →
	 * Sessions.setAttribute("batchDetailBackPage", "/zul/outward/cheque-scan.zul")
	 * [so BatchDetailComposer's Back button returns here] →
	 * DashboardComposer.getInstance().loadPage("/zul/outward/batch-detail.zul")
	 *
	 * @param batch the batch whose detail page should be opened
	 */
	private void navigateToBatchDetail(BatchEntity batch) {
		Sessions.getCurrent().setAttribute("selectedBatchId", batch.getBatchId());
		// Tell BatchDetailComposer where to return when Back is clicked
		Sessions.getCurrent().setAttribute("batchDetailBackPage", "/zul/outward/cheque-scan.zul");
		com.cts.composer.DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
	}

	// ══════════════════════════════════════════════════════════════════════
	// STAT CARDS
	// FLOW: refreshStats() → count maker batches/cheques →
	// chequeService.countPending() → set labels
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Recomputes and updates the three dashboard statistics card labels.
	 *
	 * FLOW: refreshStats() → filter in-memory batches for maker-visible statuses →
	 * count totalBatches + sum totalCheques from in-memory list (fast, no DB) →
	 * ChequeService.countPending() [DB count — separate query for accuracy] →
	 * ChequeDAOImpl.countByVerStatus("Pending") → cts_cheques table →
	 * setLabel(lblStatBatches, count) → setLabel(lblStatCheques, count) →
	 * setLabel(lblStatPending, count)
	 *
	 * Called from: doAfterCompose, finishSuccessfulImport, discardPendingBatch,
	 * onZipUpload, onCreateBatch.
	 */
	private void refreshStats() {
		// Count only maker-visible statuses (same gate as getFilteredBatches Filter 1)
		List<BatchEntity> makerBatches = batches.stream().filter(batch -> {
			String dbStatus = batch.getStatus();
			return dbStatus == null || "Draft".equalsIgnoreCase(dbStatus)
					|| "VerificationInProgressAtMaker".equalsIgnoreCase(dbStatus);
		}).collect(java.util.stream.Collectors.toList());

		int totalBatchCount = makerBatches.size();
		int totalChequeCount = makerBatches.stream().mapToInt(BatchEntity::getTotalCheques).sum();

		long pendingChequeCount;
		try {
			// DB call — cheques pending verification (separate from batch status)
			pendingChequeCount = chequeService.countPending();
		} catch (Exception ex) {
			// Fallback: show totalCheques if DB is temporarily unreachable
			pendingChequeCount = totalChequeCount;
		}

		setLabel(lblStatBatches, String.valueOf(totalBatchCount));
		setLabel(lblStatCheques, String.valueOf(totalChequeCount));
		setLabel(lblStatPending, String.valueOf(pendingChequeCount));
	}

	/**
	 * Updates the "N batches" badge in the batch table toolbar and syncs any
	 * client-side badge elements via the JS helper {@code bce_updateBatchLabel()}.
	 *
	 * FLOW: renderBatches() or updateBatchCountLabel() → updateBatchCountLabel(int)
	 * → set batchCountLabel text →
	 * Clients.evalJavaScript("bce_updateBatchLabel(N)") → updates JS-rendered badge
	 *
	 * @param batchCount the count of maker-visible batches to display
	 */
	private void updateBatchCountLabel(int batchCount) {
		String badgeText = batchCount + " batch" + (batchCount == 1 ? "" : "es");
		if (batchCountLabel != null)
			batchCountLabel.setValue(badgeText);
		// Sync JS-rendered badge (outside ZK tree) — JS function defined in
		// batch-cheque-entry.js
		Clients.evalJavaScript("bce_updateBatchLabel(" + batchCount + ");");
	}

	/**
	 * Overload: counts maker-visible batches from the in-memory list, then
	 * delegates to {@link #updateBatchCountLabel(int)}.
	 *
	 * FLOW: doAfterCompose / renderBatches (no-arg) → count from in-memory list →
	 * updateBatchCountLabel(int)
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
	// Private utility methods used across multiple sections above
	// ══════════════════════════════════════════════════════════════════════

	/**
	 * Loads cheques for a batch via ChequeService. Returns an empty list (never
	 * null) on error to keep callers null-safe.
	 *
	 * FLOW: getFilteredBatches() Ghost batch guard → loadChequesForBatch() →
	 * ChequeService.getChequesForBatch(batchId) →
	 * ChequeDAOImpl.findByBatchId(batchId) → cts_cheques table
	 *
	 * @param batchId the batch whose cheques to load
	 * @return list of ChequeEntity; empty on error or no cheques found
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
	 * FLOW: onCloseScanModal() / onScanCancelDiscard() → discardPendingBatch() →
	 * pendingBatchId check (skip if already null) → pendingBatchId = null [clear
	 * BEFORE DB call — prevents re-entry on exception] →
	 * BatchService.discardBatch(batchToDelete) → BatchDAO.deleteBatchAndCheques()
	 * [CASCADE DELETE] → batches.removeIf(...) [sync in-memory list] →
	 * renderBatches() → refreshStats() → updateBatchCountLabel()
	 */
	private void discardPendingBatch() {
		if (pendingBatchId == null)
			return; // nothing to discard — idempotent guard

		String batchToDelete = pendingBatchId;
		pendingBatchId = null; // clear FIRST to prevent re-entry if exception fires a second discard

		try {
			batchService.discardBatch(batchToDelete);
			batches.removeIf(b -> b.getBatchId().equals(batchToDelete));
			renderBatches();
			refreshStats();
			updateBatchCountLabel();
			LOG.info("Discarded empty batch: " + batchToDelete);
		} catch (Exception ex) {
			// Log but don't show user error — batch discard is silent cleanup
			LOG.warning("discardPendingBatch: " + ex.getMessage());
		}
	}

	/**
	 * Null-safe Label.setValue() — shows "—" for null or blank values. Prevents
	 * NullPointerException and shows a consistent placeholder.
	 *
	 * @param labelComponent the ZK Label to update
	 * @param value          the value to set; null or blank → "—"
	 */
	private void setLabel(Label labelComponent, String value) {
		if (labelComponent != null)
			labelComponent.setValue(nullSafe(value, "—"));
	}

	/**
	 * Formats a BigDecimal amount using Indian denomination shortcuts.
	 *
	 * FLOW: appendBatchRow() → formatAmt() → displayed in Total Amount column
	 *
	 * Thresholds (Indian notation): ≥ 1 Crore (1,00,00,000) → "₹X.XX Cr" ≥ 1 Lakh
	 * (1,00,000) → "₹X.XX L" Otherwise → "₹X,XXX.XX"
	 *
	 * @param amount the amount to format; null or zero → "₹0.00"
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
	 * FLOW: openMismatchDialog() / openDuplicateDialog() → formatAmtRaw()
	 *
	 * @param amount the amount to format; null or zero → "0.00"
	 */
	private String formatAmtRaw(BigDecimal amount) {
		if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0)
			return "0.00";
		return String.format("%,.2f", amount);
	}

	/**
	 * Reads a String attribute from the current ZK session.
	 *
	 * @param attributeKey the session attribute name (use SESS_* constants)
	 * @param defaultValue fallback if the attribute is absent or null
	 * @return the attribute value as String, or {@code defaultValue}
	 */
	private String sessionStr(String attributeKey, String defaultValue) {
		Object attributeValue = Sessions.getCurrent().getAttribute(attributeKey);
		return attributeValue != null ? attributeValue.toString() : defaultValue;
	}

	/**
	 * Returns {@code value} if non-null and non-blank, otherwise {@code fallback}.
	 * Used for null-safe display in labels and toast messages.
	 *
	 * @param value    potentially null or blank string
	 * @param fallback string to return when value is absent or blank
	 * @return value if present, fallback otherwise
	 */
	private String nullSafe(String value, String fallback) {
		return (value != null && !value.isBlank()) ? value : fallback;
	}

	/**
	 * Reads all bytes from an InputStream into a byte array.
	 *
	 * FLOW: onZipUpload() / onScanZipUpload() → readAllBytes(media.getStreamData())
	 * → buffers stream in 8 KB blocks → returns complete byte[] → byte[] passed to
	 * ZipImportServiceImpl.importZip()
	 *
	 * Buffer size of 8 KB per read is efficient for typical CTS ZIP files (1–50
	 * cheques, approximately 5–20 MB total).
	 *
	 * @param inputStream the stream from ZK Media.getStreamData()
	 * @return complete byte array of the stream content
	 * @throws Exception if an I/O error occurs reading the stream
	 */
	private byte[] readAllBytes(InputStream inputStream) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] readBlock = new byte[8192]; // 8 KB read block — good balance for CTS ZIP sizes
		int bytesRead;
		while ((bytesRead = inputStream.read(readBlock)) != -1) {
			buffer.write(readBlock, 0, bytesRead);
		}
		return buffer.toByteArray();
	}
}