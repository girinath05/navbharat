/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchDetailComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for the Cheque Detail /
 *                Batch Detail screen.  Pure ZK MVC — all
 *                previous JS dependencies removed:
 *
 *                  REMOVED                      REPLACED BY
 *                  ──────────────────────────   ─────────────────────────────
 *                  bd_ensurePopupPortal()       ZK Div setVisible() — no
 *                                               stacking-context problem since
 *                                               popup is ZK div, not n:div
 *                  bce_renderImages()           ZK <image> setSrc() + servlet
 *                  bce_imagesLoading()          show/hide ZK image + empty div
 *                  validatePayeeAccount() JS    @Listen onClick btnValidateAcct
 *                  _setPayeeState() JS          setVisible on divAcctPending /
 *                                               divAcctFound / divAcctNotFound
 *                  confirmDeleteCheque() toast  Messagebox.show() + @Listen
 *                  resetPayeeLookup() JS        inline in openChequePopup()
 *                  Ctrl+S keybind JS            not re-implemented server-side
 *                  btnPopPrev2/Next2 n:button   ZK buttons btnPopPrev / btnPopNext
 *                  btnZkLookupAccount bridge    removed (direct @Listen)
 *                  btnZkDeleteCheque bridge     removed (direct @Listen)
 *                  hiddenPayeeAcct bridge       removed (txtPayeeAcct direct)
 *                  batch-detail.js              entire file removed from ZUL
 *                  bce.js                       entire file removed from ZUL
 * ============================================================
 */
package com.cts.outward.composer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.InputEvent;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;

import com.cts.composer.DashboardComposer;
import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;
import com.cts.outward.service.ChequeService;
import com.cts.outward.service.ChequeServiceImpl;

/**
 * Composer for {@code batch-detail.zul}.
 *
 * <p>
 * Handles the full lifecycle of the cheque-detail page: batch summary sidebar,
 * cheque listbox with filter/pagination, cheque-detail popup (images, MICR
 * entry, payee lookup, delete), and batch submission.
 *
 * <p>
 * All UI state transitions (popup open/close, payee panels, image visibility)
 * are managed via ZK's {@code setVisible()} — no JavaScript is required or
 * loaded.
 *
 * <h3>Architecture</h3>
 * 
 * <pre>
 *   batch-detail.zul
 *       └── BatchDetailComposer          (this class)
 *               ├── BatchService         → BatchServiceImpl → BatchDAOImpl
 *               └── ChequeService        → ChequeServiceImpl → ChequeDAOImpl
 *                                                            → CBSService (payee lookup)
 * </pre>
 *
 * <h3>Batch submit → cheque routing (Phase 2 in BatchServiceImpl)</h3>
 * 
 * <pre>
 *   All cheques READY
 *       │
 *       ▼  BatchServiceImpl.submitBatchForVerification()
 *       ├─ amount > HIGH_VALUE_THRESHOLD → ChequeStatus.V2_PENDING
 *       └─ amount ≤ HIGH_VALUE_THRESHOLD → ChequeStatus.V1_PENDING
 *       batch.status → BatchStatus.READY_FOR_VERIFICATION
 * </pre>
 *
 * <h3>JS exceptions retained (legitimate /caveman)</h3>
 * <ul>
 * <li>{@link #showImagePlaceholders()} — {@code evalJavaScript} targets native
 * {@code <n:img id="popSingleImg">}; unwireable via ZK Java APIs.</li>
 * <li>{@link #loadChequeImages(Long)} — sets
 * {@code data-front/rear/frontgray/reargray} attributes and {@code src} on the
 * same native img element; required for the client-side {@code bdSwitchImage()}
 * tab switcher.</li>
 * </ul>
 *
 * @author Umesh M.
 * @see BatchService
 * @see ChequeService
 * @see com.cts.outward.enums.BatchStatus
 * @see com.cts.outward.enums.ChequeStatus
 */
public class BatchDetailComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(BatchDetailComposer.class.getName());
	private static final int PAGE_SIZE = 5;

	// ── Session attribute keys ─────────────────────────────────────────
	private static final String SESS_LOGGED_USER = "loggedUser";
	private static final String SESS_USER_NAME = "userName";
	private static final String SESS_USER_ROLE = "userRole";

	// ── CSS constants ──────────────────────────────────────────────────
	private static final String CSS_INPUT_NORM = "pop-input pop-input-mono";
	private static final String CSS_INPUT_ERR = "pop-input pop-input-mono pop-input-error";
	private static final String CSS_INPUT_PLAIN = "pop-input";
	private static final String CSS_INPUT_PLAIN_ERR = "pop-input pop-input-error";
	private static final String CSS_ERR_HIDDEN = "err-tip-hidden";
	private static final String CSS_ERR_SHOW = "err-tip-show";

	// ── Per-field base sclass (keeps width class through mark/clear cycles) ──
	private static final String CSS_CHQNO = "pop-input pop-input-mono pop-input-chqno";
	private static final String CSS_CITY = "pop-input pop-input-mono pop-input-city";
	private static final String CSS_BANK = "pop-input pop-input-mono pop-input-bank";
	private static final String CSS_BRANCH = "pop-input pop-input-mono pop-input-branch";
	private static final String CSS_TC = "pop-input pop-input-mono pop-input-tc";
	private static final String CSS_ACCTNO = "pop-input pop-input-mono pop-input-acctno";

	// ── Image servlet base URL ─────────────────────────────────────────
	/**
	 * Servlet path served by {@link com.cts.outward.servlet.ChequeImageServlet}.
	 * NOTE: ZK's Image.setSrc() prepends the context path automatically — do NOT
	 * include /navbharat here or the URL will be doubled.
	 */
	private static final String IMG_SERVLET = "/chequeImage";

	// ── Services ──────────────────────────────────────────────────────
	private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
	private final ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());

	// ── State ─────────────────────────────────────────────────────────
	private String batchId;
	private List<ChequeEntity> allCheques = new ArrayList<>();
	private List<ChequeEntity> filtered = new ArrayList<>();
	private int currentPage = 1;
	private int totalPages = 1;
	private ChequeEntity selectedCheque;
	private int selectedIndex = -1;
	/**
	 * True once the batch has been submitted (status ≥ READY_FOR_VERIFICATION).
	 * Gates popup read-only mode and blocks further Save Batch clicks.
	 */
	private boolean batchSubmitted;

	// ══════════════════════════════════════════════════════════════════
	// WIRED COMPONENTS — PAGE HEADER
	// ══════════════════════════════════════════════════════════════════

	@Wire
	private Label lblBatchId;
	@Wire
	private Label lblBatchIdTitle;
	@Wire
	private Label lblBranchCode;
	@Wire
	private Label lblTotalCheques;
	@Wire
	private Label lblTotalAmount;
	@Wire
	private Label lblVerifiedCheques;
	@Wire
	private Label lblPendingCheques;
	@Wire
	private Button btnBack;
	@Wire
	private Button btnSaveBatch;

	// ══════════════════════════════════════════════════════════════════
	// WIRED COMPONENTS — LIST / FILTER
	// ══════════════════════════════════════════════════════════════════

	@Wire
	private Textbox txtSearch;
	@Wire
	private Datebox dtDateFilter;
	@Wire
	private Datebox dtDateTo;
	@Wire
	private Button btnClearFilter;
	@Wire
	private Combobox cmbStatusFilter;
	@Wire
	private Label lblFilterCount;

	@Wire
	private Listbox lbCheques;
	@Wire
	private Label lblChequeRange;
	@Wire
	private Button btnFirst;
	@Wire
	private Button btnPrev;
	@Wire
	private Button btnNext;
	@Wire
	private Button btnLast;
	@Wire
	private Label lblPageInfo;

	// ══════════════════════════════════════════════════════════════════
	// WIRED COMPONENTS — POPUP OUTER
	// ══════════════════════════════════════════════════════════════════

	/** The popup overlay Div — toggled via setVisible(). */
	@Wire
	private Div chequePopup;
	@Wire
	private Label popChequeNo;
	@Wire
	private Button btnPopClose;

	// ── Popup images (single-viewer with F/R/G toggle) ─────────────────
	/** Empty-state placeholder shown before any image loads. */
	@Wire
	private Div imgEmptyState;

	// ── MICR row ───────────────────────────────────────────────────────
	@Wire
	private Textbox popCheckNo;
	@Wire
	private Textbox popCity;
	@Wire
	private Textbox popBank;
	@Wire
	private Textbox popBranch;
	@Wire
	private Textbox popTc;

	// ── MICR error labels ──────────────────────────────────────────────
	@Wire
	private Label errCheckNo;
	@Wire
	private Label errCity;
	@Wire
	private Label errBank;
	@Wire
	private Label errBranch;
	@Wire
	private Label errTc;

	// ── Face row ───────────────────────────────────────────────────────
	@Wire
	private Textbox popAccountNo;
	@Wire
	private Textbox popChequeDate;
	@Wire
	private Textbox popAmount;
	@Wire
	private Textbox popAmountWords;

	// ── Face error labels ──────────────────────────────────────────────
	@Wire
	private Label errAccountNo;
	@Wire
	private Label errChequeDate;
	@Wire
	private Label errAmount;

	// ── Payee lookup ───────────────────────────────────────────────────
	@Wire
	private Textbox txtPayeeAcct;
	@Wire
	private Button btnValidateAcct;
	/** Shown before first Validate click. */
	@Wire
	private Div divAcctPending;
	/** Shown when account is found. */
	@Wire
	private Div divAcctFound;
	/** Shown when account is not found. */
	@Wire
	private Div divAcctNotFound;
	@Wire
	private Label lblAcctHolderName;
	@Wire
	private Label lblAcctStatus;
	@Wire
	private Label lblAcctSubcategory;

	// ── Popup footer ───────────────────────────────────────────────────
	@Wire
	private Button btnPopPrev;
	@Wire
	private Label popNavInfo;
	@Wire
	private Button btnPopNext;
	@Wire
	private Button btnDeleteCheque;
	@Wire
	private Button btnPopSave;

	// ══════════════════════════════════════════════════════════════════
	// LIFECYCLE
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Entry point — called by ZK after all {@code @Wire} fields are injected.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   doAfterCompose()
	 *     ├── guardSession()           — redirect to login if unauthenticated
	 *     ├── resolve batchId          — from request param or session attribute
	 *     ├── loadBatchSummary()       — populate sidebar labels
	 *     ├── lock check               — set batchSubmitted + disable Save Batch
	 *     ├── loadCheques()            — fetch all cheques from DB
	 *     ├── applyFilter()            — initialise filtered list
	 *     └── renderPage()             — draw first page of listbox
	 * </pre>
	 *
	 * <p>
	 * batchSubmitted is set to {@code true} if the batch status is any post-maker
	 * state (READY_FOR_VERIFICATION through DISPATCHED), which locks the popup into
	 * read-only mode and disables the Save Batch button.
	 */
	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);
		guardSession();

		// Resolve batch ID — prefer request param, fall back to session
		batchId = Executions.getCurrent().getParameter("batchId");
		if (batchId == null || batchId.isBlank()) {
			Object s = Sessions.getCurrent().getAttribute("selectedBatchId");
			batchId = (s != null) ? s.toString() : null;
		}
		if (batchId == null) {
			Executions.sendRedirect("my-batches.zul");
			return;
		}

		if (cmbStatusFilter != null)
			cmbStatusFilter.setValue("All");

		loadBatchSummary();

		// Lock if batch is already submitted (any state beyond Maker's PENDING)
		BatchEntity batch = batchService.getBatchById(batchId);
		if (batch != null) {
			BatchStatus bs = BatchStatus.fromDb(batch.getStatus());
			batchSubmitted = (bs == BatchStatus.READY_FOR_VERIFICATION || bs == BatchStatus.VERIFICATION_IN_PROGRESS
					|| bs == BatchStatus.VERIFIED || bs == BatchStatus.CXF_CIBF_GENERATED
					|| bs == BatchStatus.DISPATCHED);
			if (batchSubmitted && btnSaveBatch != null)
				btnSaveBatch.setDisabled(true);
		}

		loadCheques();
		applyFilter();
		renderPage();
	}

	// ══════════════════════════════════════════════════════════════════
	// PAGE LISTENERS
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Navigate back.
	 *
	 * <p>
	 * Respects the {@code batchDetailBackPage} session attribute set by the calling
	 * page (e.g. Verification I/II). Falls back to
	 * {@code /zul/outward/batchManagement.zul} (My Batches) if absent.
	 */
	@Listen("onClick = #btnBack")
	public void onBack() {
	    Object back = Sessions.getCurrent().getAttribute("batchDetailBackPage");
	    String backPage = (back != null && !back.toString().isBlank())
	            ? back.toString()
	            : "/zul/outward/my-batches.zul";   // was: batchManagement.zul
	    Sessions.getCurrent().removeAttribute("batchDetailBackPage");
	    DashboardComposer.navigateTo(backPage);
	}

	/**
	 * Submit all cheques in the batch for Verification.
	 *
	 * <p>
	 * Pre-conditions checked server-side:
	 * <ol>
	 * <li>No cheques still in PENDING status.</li>
	 * <li>Batch not already submitted ({@link #batchSubmitted} flag).</li>
	 * </ol>
	 *
	 * <p>
	 * On success: delegates to
	 * {@link BatchService#submitBatchForVerification(String)} which routes each
	 * cheque to V1_PENDING or V2_PENDING based on amount threshold, then sets batch
	 * status to READY_FOR_VERIFICATION. Navigates to My Batches after.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   onSaveBatch()
	 *     └── BatchServiceImpl.submitBatchForVerification(batchId)
	 *           ├── chequeDao.updateVerRouting(id, V1_PENDING / V2_PENDING, ...)
	 *           └── batchDao.updateBatchStatus(batchId, ReadyForVerification)
	 * </pre>
	 */
	@Listen("onClick = #btnSaveBatch")
	public void onSaveBatch() {
		if (batchSubmitted)
			return;

		loadCheques();
		long pending = allCheques.stream().filter(c -> !chequeIsReady(c.getStatus())).count();

		if (pending > 0) {
			Clients.showNotification("❌ Cannot submit — " + pending + " cheque(s) still Pending.", "error", null,
					"middle_center", 4000);
			return;
		}

		try {
			batchService.submitBatchForVerification(batchId);
			batchSubmitted = true;
			if (btnSaveBatch != null)
				btnSaveBatch.setDisabled(true);

			Clients.showNotification("✅ Batch " + batchId + " submitted for Verification.", "info", null,
					"middle_center", 3000);
			DashboardComposer.navigateTo("/zul/outward/batchManagement.zul");
		} catch (Exception ex) {
			Clients.showNotification("❌ Submit failed: " + ex.getMessage(), "error", null, "middle_center", 5000);
		}
	}

	// ── Filter listeners ───────────────────────────────────────────────

	/** Instant search while typing in the search box. */
	@Listen("onChanging = #txtSearch")
	public void onSearchChanging(InputEvent e) {
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	/** Final search value after the user leaves the field. */
	@Listen("onChange = #txtSearch")
	public void onSearchChange() {
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	@Listen("onChange = #dtDateFilter")
	public void onDateFilterChange() {
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	@Listen("onChange = #dtDateTo")
	public void onDateToChange() {
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	@Listen("onClick = #btnClearFilter")
	public void onClearFilter() {
		if (txtSearch != null)
			txtSearch.setValue("");
		if (dtDateFilter != null)
			dtDateFilter.setValue(null);
		if (dtDateTo != null)
			dtDateTo.setValue(null);
		if (cmbStatusFilter != null)
			cmbStatusFilter.setValue("All");
		if (lblFilterCount != null)
			lblFilterCount.setValue("");
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	@Listen("onSelect = #cmbStatusFilter")
	public void onStatusFilter() {
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	// ── Pagination listeners ───────────────────────────────────────────

	@Listen("onClick = #btnFirst")
	public void onFirst() {
		currentPage = 1;
		renderPage();
	}

	@Listen("onClick = #btnPrev")
	public void onPrev() {
		if (currentPage > 1) {
			currentPage--;
			renderPage();
		}
	}

	@Listen("onClick = #btnNext")
	public void onNext() {
		if (currentPage < totalPages) {
			currentPage++;
			renderPage();
		}
	}

	@Listen("onClick = #btnLast")
	public void onLast() {
		currentPage = totalPages;
		renderPage();
	}

	// ══════════════════════════════════════════════════════════════════
	// POPUP LISTENERS
	// ══════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnPopClose")
	public void onPopClose() {
		closePopup();
	}

	/** Navigate to the previous cheque in the current filtered list. */
	@Listen("onClick = #btnPopPrev")
	public void onPopPrev() {
		if (selectedIndex > 0)
			openChequePopup(filtered.get(selectedIndex - 1), selectedIndex - 1);
	}

	/** Navigate to the next cheque in the current filtered list. */
	@Listen("onClick = #btnPopNext")
	public void onPopNext() {
		if (selectedIndex < filtered.size() - 1)
			openChequePopup(filtered.get(selectedIndex + 1), selectedIndex + 1);
	}

	/**
	 * Save current cheque fields and advance to the next one, or close the popup if
	 * there are no more cheques in the filtered list.
	 *
	 * <p>
	 * Pre-conditions:
	 * <ul>
	 * <li>Batch not submitted ({@link #batchSubmitted} == false).</li>
	 * <li>All MICR/face fields pass {@link #validateMicr()}.</li>
	 * <li>Payee account validated (lblAcctHolderName is populated and not
	 * "—").</li>
	 * </ul>
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   onPopSave()
	 *     ├── validateMicr()
	 *     ├── saveSelectedCheque()
	 *     │     ├── applyPopupEditsToEntity()    — copies textbox values → entity
	 *     │     ├── chequeService.saveChequeFields(entity)
	 *     │     └── updateBatchProgressStatus()  — DRAFT / PENDING based on ready count
	 *     └── openChequePopup(next) or closePopup()
	 * </pre>
	 */
	@Listen("onClick = #btnPopSave")
	public void onPopSave() {
		if (batchSubmitted || selectedCheque == null)
			return;

		Map<String, String> errors = validateMicr();
		if (!errors.isEmpty()) {
			markFieldsInvalid(errors);
			return;
		}

		// Payee account must have been validated before saving
		String holderName = lblAcctHolderName != null ? lblAcctHolderName.getValue() : "";
		if (holderName.isBlank() || "—".equals(holderName) || "\u2014".equals(holderName)) {
			Clients.showNotification("⚠ Please enter the payee account number and click Validate before saving.",
					"warning", null, "middle_center", 5000);
			return;
		}
		if ("Not found".equalsIgnoreCase(holderName)) {
			Clients.showNotification("❌ Account not found in our CBS. Please delete this cheque.", "error", null,
					"middle_center", 3000);
			return;
		}

		clearAllFieldErrors();
		saveSelectedCheque();

		loadCheques();
		applyFilter();
		renderPage();

		int next = selectedIndex + 1;
		if (next < filtered.size()) {
			openChequePopup(filtered.get(next), next);
		} else {
			closePopup();
			Clients.showNotification("✔ All cheques processed.", "info", null, "middle_center", 2500);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// PAYEE ACCOUNT LOOKUP LISTENER (replaces validatePayeeAccount() JS)
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Fires when the user clicks the ZK "Validate" button in the payee lookup
	 * section.
	 *
	 * <p>
	 * Replaces JS function {@code validatePayeeAccount()} entirely: reads
	 * {@code txtPayeeAcct} directly (no JS bridge), calls
	 * {@link ChequeService#lookupAccount(String)} via CBS/Firebase, and shows the
	 * result by toggling ZK Div panels via {@link #setPayeeState(PayeeState)}.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   onValidatePayeeAcct()
	 *     └── chequeService.lookupAccount(acctNo)
	 *           └── CBSService.lookupAccount(acctNo)
	 *                 └── Firebase Firestore REST call
	 *     └── setPayeeState(FOUND | NOT_FOUND)
	 * </pre>
	 */
	@Listen("onClick = #btnValidateAcct")
	public void onValidatePayeeAcct() {
		String acctNo = txt(txtPayeeAcct);
		if (acctNo.isEmpty()) {
			Clients.showNotification("Please enter an account number.", "warning", null, "middle_center", 2500);
			return;
		}

		setPayeeState(PayeeState.PENDING);

		try {
			String[] info = chequeService.lookupAccount(acctNo);
			boolean found = info[0] != null && !info[0].isBlank() && !"Not found".equalsIgnoreCase(info[0]);

			safe(lblAcctHolderName, found ? info[0] : "Not found");
			safe(lblAcctStatus, info[1] != null ? info[1] : "\u2014");
			safe(lblAcctSubcategory, info[2] != null ? info[2] : "\u2014");

			if (selectedCheque != null && found) {
				selectedCheque.setPayeeAccountNo(acctNo);
			}

			setPayeeState(found ? PayeeState.FOUND : PayeeState.NOT_FOUND);

		} catch (Exception ex) {
			LOG.warning("CBS lookup failed: " + ex.getMessage());
			safe(lblAcctHolderName, "Not found");
			safe(lblAcctStatus, "\u2014");
			safe(lblAcctSubcategory, "\u2014");
			setPayeeState(PayeeState.NOT_FOUND);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// DELETE CHEQUE LISTENER (replaces confirmDeleteCheque() JS toast)
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Shows a ZK {@link Messagebox} confirmation dialog, then deletes the cheque on
	 * YES.
	 *
	 * <p>
	 * Replaces JS inline-toast {@code confirmDeleteCheque()}. The entity reference
	 * is captured into a local {@code toDelete} before the lambda to avoid NPE if
	 * {@link #closePopup()} nulls {@link #selectedCheque} between the click and the
	 * callback execution.
	 */
	@Listen("onClick = #btnDeleteCheque")
	public void onDeleteCheque() {
		if (batchSubmitted || selectedCheque == null)
			return;

		final ChequeEntity toDelete = selectedCheque;
		Messagebox.show(
				"Delete cheque " + nvl(toDelete.getChequeNo(), "") + "?\n\n"
						+ "This cheque will be permanently removed. " + "Batch count and total amount will be updated.",
				"Confirm Delete", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION, event -> {
					if (Messagebox.ON_YES.equals(event.getName())) {
						doDeleteCheque(toDelete);
					}
				});
	}

	/**
	 * Performs the physical delete of {@code cheque} and refreshes the list.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   doDeleteCheque(cheque)
	 *     └── chequeService.deleteCheque(chequeId)
	 *           └── ChequeDAOImpl.deleteCheque(id)   [DELETE FROM cts_cheques]
	 * </pre>
	 *
	 * @param cheque the cheque entity to delete (captured before Messagebox
	 *               callback)
	 */
	private void doDeleteCheque(ChequeEntity cheque) {
		try {
			long chequeId = cheque.getId();
			chequeService.deleteCheque(chequeId);

			allCheques.removeIf(c -> chequeId == c.getId());
			filtered.removeIf(c -> chequeId == c.getId());

			closePopup();
			applyFilter();
			renderPage();

			safe(lblTotalCheques, String.valueOf(allCheques.size()));
			long ready = allCheques.stream().filter(c -> chequeIsReady(c.getStatus())).count();
			long pending = allCheques.size() - ready;
			safe(lblVerifiedCheques, String.valueOf(ready));
			safe(lblPendingCheques, String.valueOf(pending));

			Clients.showNotification("Cheque deleted.", "info", null, "top_center", 3000);
		} catch (Exception ex) {
			Clients.showNotification("Delete failed: " + ex.getMessage(), "error", null, "top_center", 4000);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// POPUP OPEN / CLOSE
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Opens the cheque-detail popup for {@code c} at position {@code idx} within
	 * the current filtered list.
	 *
	 * <p>
	 * Replaces JS functions:
	 * <ul>
	 * <li>{@code bd_ensurePopupPortal()} — no longer needed; popup is a ZK Div
	 * without stacking-context issues.</li>
	 * <li>{@code bce_imagesLoading()} — replaced by
	 * {@link #showImagePlaceholders()}.</li>
	 * <li>{@code bce_renderImages()} — replaced by
	 * {@link #loadChequeImages(Long)}.</li>
	 * <li>{@code resetPayeeLookup()} — payee state reset inline here.</li>
	 * </ul>
	 *
	 * <p>
	 * Read-only mode ({@link #applyReadOnly(boolean)}) is applied when
	 * {@link #batchSubmitted} is true — locks all inputs and hides action buttons.
	 *
	 * @param c   the cheque entity to display
	 * @param idx index within {@link #filtered}
	 */
	private void openChequePopup(ChequeEntity c, int idx) {
		selectedCheque = c;
		selectedIndex = idx;

		// ── Header ────────────────────────────────────────────────────
		safe(popChequeNo, "Cheque #" + nvl(c.getChequeNo(), "—"));

		// ── MICR fields ───────────────────────────────────────────────
		String sc = nvl(c.getSortCode(), "");
		setTxt(popCheckNo, nvl(c.getChequeNo(), ""));
		setTxt(popCity, sc.length() >= 3 ? sc.substring(0, 3) : "");
		setTxt(popBank, sc.length() >= 6 ? sc.substring(3, 6) : "");
		setTxt(popBranch, sc.length() >= 9 ? sc.substring(6, 9) : "");
		setTxt(popTc, nvl(c.getTransactionCode(), ""));

		// ── Face fields ───────────────────────────────────────────────
		setTxt(popAccountNo, nvl(c.getAccountNo(), ""));
		setTxt(popChequeDate, convertDateForDisplay(nvl(c.getChequeDate(), "")));
		setTxt(popAmount, c.getAmount() != null ? c.getAmount().toPlainString() : "");
		setTxt(popAmountWords, nvl(c.getAmountInWords(), ""));

		// ── Payee account lookup ──────────────────────────────────────
		// Replaces: resetPayeeLookup() + _setPayeeState() JS
		String savedPayeeAcct = nvl(c.getPayeeAccountNo(), "");
		setTxt(txtPayeeAcct, savedPayeeAcct);

		if (!savedPayeeAcct.isEmpty()) {
			try {
				String[] info = chequeService.lookupAccount(savedPayeeAcct);
				boolean found = info[0] != null && !info[0].isBlank() && !"Not found".equalsIgnoreCase(info[0]);
				safe(lblAcctHolderName, found ? info[0] : "\u2014");
				safe(lblAcctStatus, info[1] != null ? info[1] : "\u2014");
				safe(lblAcctSubcategory, info[2] != null ? info[2] : "\u2014");
				setPayeeState(found ? PayeeState.FOUND : PayeeState.NOT_FOUND);
			} catch (Exception ex) {
				safe(lblAcctHolderName, "\u2014");
				safe(lblAcctStatus, "\u2014");
				safe(lblAcctSubcategory, "\u2014");
				setPayeeState(PayeeState.PENDING);
			}
		} else {
			safe(lblAcctHolderName, "\u2014");
			safe(lblAcctStatus, "\u2014");
			safe(lblAcctSubcategory, "\u2014");
			setPayeeState(PayeeState.PENDING);
		}

		// ── Images ────────────────────────────────────────────────────
		// evalJavaScript used here: targets native <n:img id="popSingleImg">
		// which is unwireable via ZK Java APIs. See class-level JS exceptions note.
		showImagePlaceholders();
		if (c.getId() != null) {
			loadChequeImages(c.getId());
		}

		// ── Read-only mode when batch already submitted ───────────────
		applyReadOnly(batchSubmitted);

		// ── Validation state ──────────────────────────────────────────
		clearAllFieldErrors();
		if (c.isAmountWordsMismatch()) {
			markField(popAmount, errAmount, "⚠ Amount in Words mismatch with Amount in Digits. Please verify.",
					CSS_INPUT_NORM);
			if (popAmountWords != null)
				popAmountWords.setSclass(CSS_INPUT_PLAIN + " pop-input-error");
		}

		// ── Navigation info ───────────────────────────────────────────
		safe(popNavInfo, (idx + 1) + " / " + filtered.size());
		if (btnPopPrev != null)
			btnPopPrev.setDisabled(idx <= 0);
		if (btnPopNext != null)
			btnPopNext.setDisabled(idx >= filtered.size() - 1);

		// ── Show popup via ZK setVisible (replaces JS display:'flex') ─
		if (chequePopup != null)
			chequePopup.setVisible(true);

		renderPage();
	}

	/**
	 * Hides the popup and resets selection state.
	 *
	 * <p>
	 * Replaces JS:
	 * {@code document.getElementById('chequePopup').style.display='none';}.
	 */
	private void closePopup() {
		selectedCheque = null;
		selectedIndex = -1;
		clearAllFieldErrors();
		if (chequePopup != null)
			chequePopup.setVisible(false);
		renderPage();
	}

	// ── Image helpers (replace bce_imagesLoading / bce_renderImages) ──

	/**
	 * Shows the empty-state placeholder and hides the native img element. Called
	 * before loading a new cheque's images.
	 *
	 * <p>
	 * <b>JS exception (legitimate):</b> targets {@code <n:img id="popSingleImg">} —
	 * a native HTML element that cannot be wired via {@code @Wire} or controlled
	 * via ZK Java APIs. {@code evalJavaScript} is the only option here.
	 */
	private void showImagePlaceholders() {
		if (imgEmptyState != null)
			imgEmptyState.setVisible(true);
		Clients.evalJavaScript(
				"var el=document.getElementById('popSingleImg');" + "if(el){el.style.display='none';el.src='';}");
	}

	/**
	 * Pushes all 4 image URLs to the native {@code <n:img id="popSingleImg">} via
	 * {@code evalJavaScript}. Sets {@code data-front/rear/frontgray/reargray}
	 * attributes for the client-side {@code bdSwitchImage()} tab switcher, then
	 * displays the front image by default.
	 *
	 * <p>
	 * <b>JS exception (legitimate):</b> same native element constraint as
	 * {@link #showImagePlaceholders()}. The {@code data-*} attributes are consumed
	 * by {@code bdSwitchImage()} in {@code batch-detail.js} (the one remaining JS
	 * function for the image tab switcher).
	 *
	 * @param chequeDbId the database primary-key of the cheque
	 */
	private void loadChequeImages(Long chequeDbId) {
		String ctx = Executions.getCurrent().getContextPath();
		String frontUrl = ctx + IMG_SERVLET + "?id=" + chequeDbId + "&side=front";
		String rearUrl = ctx + IMG_SERVLET + "?id=" + chequeDbId + "&side=rear";
		String frontGrayUrl = ctx + IMG_SERVLET + "?id=" + chequeDbId + "&side=frontgray";
		String rearGrayUrl = ctx + IMG_SERVLET + "?id=" + chequeDbId + "&side=reargray";

		Clients.evalJavaScript("var el=document.getElementById('popSingleImg');" + "if(el){"
				+ "el.setAttribute('data-front','" + frontUrl + "');" + "el.setAttribute('data-rear','" + rearUrl
				+ "');" + "el.setAttribute('data-frontgray','" + frontGrayUrl + "');"
				+ "el.setAttribute('data-reargray','" + rearGrayUrl + "');" + "el.src='" + frontUrl + "';"
				+ "el.style.display='block';" + "}");
		if (imgEmptyState != null)
			imgEmptyState.setVisible(false);
	}

	// ── Payee state helper (replaces _setPayeeState() JS) ─────────────

	/**
	 * Enum representing the three possible payee-lookup result states. Drives
	 * {@link #setPayeeState(PayeeState)}.
	 */
	private enum PayeeState {
		PENDING, FOUND, NOT_FOUND
	}

	/**
	 * Toggles visibility of the three payee-lookup result Divs.
	 *
	 * <p>
	 * Replaces JS function {@code _setPayeeState(state)}. Only one Div is visible
	 * at a time.
	 *
	 * @param state the state to reflect in the UI
	 */
	private void setPayeeState(PayeeState state) {
		if (divAcctPending != null)
			divAcctPending.setVisible(state == PayeeState.PENDING);
		if (divAcctFound != null)
			divAcctFound.setVisible(state == PayeeState.FOUND);
		if (divAcctNotFound != null)
			divAcctNotFound.setVisible(state == PayeeState.NOT_FOUND);
	}

	// ── Read-only helper ───────────────────────────────────────────────

	/**
	 * Applies or removes read-only mode on all popup input fields and action
	 * buttons.
	 *
	 * <p>
	 * Called with {@code readOnly=true} when the batch is in any post-maker state
	 * (READY_FOR_VERIFICATION or beyond), preventing any further Maker edits on
	 * already-submitted cheques.
	 *
	 * @param readOnly {@code true} to lock all fields and disable action buttons
	 */
	private void applyReadOnly(boolean readOnly) {
		if (popCheckNo != null)
			popCheckNo.setReadonly(readOnly);
		if (popCity != null)
			popCity.setReadonly(readOnly);
		if (popBank != null)
			popBank.setReadonly(readOnly);
		if (popBranch != null)
			popBranch.setReadonly(readOnly);
		if (popTc != null)
			popTc.setReadonly(readOnly);
		if (popAccountNo != null)
			popAccountNo.setReadonly(readOnly);
		if (popChequeDate != null)
			popChequeDate.setReadonly(readOnly);
		if (popAmount != null)
			popAmount.setReadonly(readOnly);
		if (popAmountWords != null)
			popAmountWords.setReadonly(readOnly);
		if (txtPayeeAcct != null)
			txtPayeeAcct.setReadonly(readOnly);
		if (btnPopSave != null)
			btnPopSave.setDisabled(readOnly);
		if (btnDeleteCheque != null)
			btnDeleteCheque.setDisabled(readOnly);
		if (btnValidateAcct != null)
			btnValidateAcct.setDisabled(readOnly);
	}

	// ══════════════════════════════════════════════════════════════════
	// VALIDATION ENGINE
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Validates all MICR and face fields in the popup.
	 *
	 * <p>
	 * Validates in field order: Cheque No → City → Bank → Branch → TC → Account No
	 * → Date → Amount → Amount in Words (cross-check).
	 *
	 * @return map of field-id → error message; empty map if all fields are valid
	 */
	private Map<String, String> validateMicr() {
		Map<String, String> err = new LinkedHashMap<>();

		String chqNo = txt(popCheckNo);
		if (chqNo.isEmpty())
			err.put("popCheckNo", "Cheque No. is required.");
		else if (!chqNo.matches("\\d{6}"))
			err.put("popCheckNo", "Must be exactly 6 numeric digits (got " + chqNo.length() + ").");

		String city = txt(popCity);
		if (city.isEmpty())
			err.put("popCity", "City code is required.");
		else if (!city.matches("\\d{3}"))
			err.put("popCity", "Must be exactly 3 numeric digits.");

		String bank = txt(popBank);
		if (bank.isEmpty())
			err.put("popBank", "Bank code is required.");
		else if (!bank.matches("\\d{3}"))
			err.put("popBank", "Must be exactly 3 numeric digits.");

		String branch = txt(popBranch);
		if (branch.isEmpty())
			err.put("popBranch", "Branch code is required.");
		else if (!branch.matches("\\d{3}"))
			err.put("popBranch", "Must be exactly 3 numeric digits.");

		String tc = txt(popTc);
		if (tc.isEmpty())
			err.put("popTc", "TC is required.");
		else if (!tc.matches("\\d{2,3}"))
			err.put("popTc", "TC must be 2 or 3 numeric digits (e.g. 11, 31).");

		String acct = txt(popAccountNo);
		if (acct.isEmpty())
			err.put("popAccountNo", "Drawee Account No. is required.");
		else if (acct.length() > 20)
			err.put("popAccountNo", "Must not exceed 20 characters.");

		String dt = txt(popChequeDate);
		if (dt.isEmpty()) {
			err.put("popChequeDate", "Date is required.");
		} else if (!dt.matches("\\d{2}/\\d{2}/\\d{4}")) {
			err.put("popChequeDate", "Use DD/MM/YYYY format (e.g. 05/05/2026).");
		} else {
			try {
				int day = Integer.parseInt(dt.substring(0, 2));
				int mon = Integer.parseInt(dt.substring(3, 5));
				int yr = Integer.parseInt(dt.substring(6));
				if (day < 1 || day > 31 || mon < 1 || mon > 12 || yr < 2000 || yr > 2100)
					err.put("popChequeDate", "Date values out of range.");
			} catch (NumberFormatException ex) {
				err.put("popChequeDate", "Invalid date. Use DD/MM/YYYY.");
			}
		}

		String amt = txt(popAmount);
		if (amt.isEmpty()) {
			err.put("popAmount", "Amount is required.");
		} else {
			try {
				BigDecimal bd = new BigDecimal(amt.replace(",", ""));
				if (bd.compareTo(BigDecimal.ZERO) <= 0)
					err.put("popAmount", "Amount must be greater than zero.");
			} catch (NumberFormatException ex) {
				err.put("popAmount", "Enter a valid number (e.g. 2000000.00).");
			}
		}

		// Amount-in-words cross-check (skipped if amount itself is invalid)
		String words = txt(popAmountWords).trim();
		if (!amt.isEmpty() && !words.isEmpty() && !err.containsKey("popAmount")) {
			try {
				String expected = com.cts.outward.util.AmountToWords
						.convert(new java.math.BigDecimal(amt.replace(",", "")));
				String expectedNorm = expected == null ? null
						: expected.replaceAll("(?i)\\bRupees\\s*", "").replaceAll("(?i)\\band\\b\\s*", "")
								.replaceAll("\\s{2,}", " ").trim();
				String wordsNorm = words.replaceAll("(?i)\\bRupees\\s*", "").replaceAll("(?i)\\band\\b\\s*", "")
						.replaceAll("\\s{2,}", " ").trim();
				if (!wordsNorm.equalsIgnoreCase(expectedNorm)) {
					err.put("popAmountWords", "⚠ Mismatch — Digits say \"" + expectedNorm + "\" but Words say \""
							+ words + "\". Fix before saving.");
				}
			} catch (NumberFormatException ignored) {
			}
		}

		return err;
	}

	private void markFieldsInvalid(Map<String, String> errors) {
		clearAllFieldErrors();
		for (Map.Entry<String, String> e : errors.entrySet()) {
			switch (e.getKey()) {
			case "popCheckNo" -> markField(popCheckNo, errCheckNo, e.getValue(), CSS_CHQNO);
			case "popCity" -> markField(popCity, errCity, e.getValue(), CSS_CITY);
			case "popBank" -> markField(popBank, errBank, e.getValue(), CSS_BANK);
			case "popBranch" -> markField(popBranch, errBranch, e.getValue(), CSS_BRANCH);
			case "popTc" -> markField(popTc, errTc, e.getValue(), CSS_TC);
			case "popAccountNo" -> markField(popAccountNo, errAccountNo, e.getValue(), CSS_ACCTNO);
			case "popChequeDate" -> markField(popChequeDate, errChequeDate, e.getValue(), CSS_INPUT_PLAIN);
			case "popAmount" -> markField(popAmount, errAmount, e.getValue(), CSS_INPUT_NORM);
			case "popAmountWords" -> markField(popAmountWords, errAmount, e.getValue(), CSS_INPUT_PLAIN);
			}
		}
	}

	private void markField(Textbox tb, Label errLbl, String msg, String baseSclass) {
		if (tb != null)
			tb.setSclass(baseSclass + " pop-input-error");
		if (errLbl != null) {
			errLbl.setValue(msg);
			errLbl.setSclass(CSS_ERR_SHOW);
		}
	}

	private void clearAllFieldErrors() {
		clearField(popCheckNo, errCheckNo, CSS_CHQNO);
		clearField(popCity, errCity, CSS_CITY);
		clearField(popBank, errBank, CSS_BANK);
		clearField(popBranch, errBranch, CSS_BRANCH);
		clearField(popTc, errTc, CSS_TC);
		clearField(popAccountNo, errAccountNo, CSS_ACCTNO);
		clearField(popChequeDate, errChequeDate, CSS_INPUT_PLAIN);
		clearField(popAmount, errAmount, CSS_INPUT_NORM);
		if (popAmountWords != null)
			popAmountWords.setSclass(CSS_INPUT_PLAIN);
	}

	private void clearField(Textbox tb, Label errLbl, String baseSclass) {
		if (tb != null)
			tb.setSclass(baseSclass);
		if (errLbl != null) {
			errLbl.setValue("");
			errLbl.setSclass(CSS_ERR_HIDDEN);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// SAVE
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Persists the popup field values back to the selected cheque entity.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   saveSelectedCheque()
	 *     ├── applyPopupEditsToEntity()
	 *     │     └── selectedCheque.setStatus(ChequeStatus.READY.db())
	 *     ├── chequeService.saveChequeFields(selectedCheque)
	 *     │     └── ChequeDAOImpl.updateChequeFields(entity)  [UPDATE cts_cheques]
	 *     └── updateBatchProgressStatus()
	 * </pre>
	 */
	private void saveSelectedCheque() {
		if (selectedCheque == null)
			return;
		try {
			applyPopupEditsToEntity();
			chequeService.saveChequeFields(selectedCheque);
			updateBatchProgressStatus();
			Clients.showNotification("✔ Cheque " + nvl(selectedCheque.getChequeNo(), "") + " saved.", "info", null,
					"top_right", 2000);
		} catch (Exception ex) {
			Clients.showNotification("Save failed: " + ex.getMessage(), "error", null, "middle_center", 4000);
		}
	}

	/**
	 * Incrementally updates batch status as the Maker saves cheques.
	 *
	 * <p>
	 * Logic:
	 * <ul>
	 * <li>0 ready → DRAFT</li>
	 * <li>some ready, not all → PENDING
	 * ({@code VerificationInProgressAtMaker})</li>
	 * <li>all ready → no change here; READY_FOR_VERIFICATION is set only when the
	 * Maker clicks Save Batch ({@link #onSaveBatch()}).</li>
	 * </ul>
	 *
	 * <p>
	 * Guards against regressing an already-submitted batch — returns early if
	 * {@link #batchSubmitted} is true or the DB status is already post-maker.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   updateBatchProgressStatus()
	 *     └── batchService.updateBatchStatus(batchId, DRAFT | PENDING)
	 *           └── BatchDAOImpl.updateBatchStatus(batchId, status)
	 * </pre>
	 */
	private void updateBatchProgressStatus() {
		if (batchSubmitted)
			return;
		try {
			BatchEntity current = batchService.getBatchById(batchId);
			if (current != null) {
				BatchStatus bs = BatchStatus.fromDb(current.getStatus());
				if (bs == BatchStatus.READY_FOR_VERIFICATION || bs == BatchStatus.VERIFICATION_IN_PROGRESS
						|| bs == BatchStatus.VERIFIED || bs == BatchStatus.CXF_CIBF_GENERATED
						|| bs == BatchStatus.DISPATCHED) {
					batchSubmitted = true;
					return;
				}
			}
			List<ChequeEntity> all = chequeService.getChequesForBatch(batchId);
			long total = all.size();
			long ready = all.stream().filter(c -> ChequeStatus.READY.db().equalsIgnoreCase(c.getStatus())).count();
			if (total == 0)
				return;
			if (ready == 0) {
				batchService.updateBatchStatus(batchId, BatchStatus.DRAFT.db());
			} else if (ready < total) {
				batchService.updateBatchStatus(batchId, BatchStatus.PENDING.db());
			}
			// ready == total: Save Batch click (onSaveBatch) sets READY_FOR_VERIFICATION
		} catch (Exception ex) {
			LOG.warning("updateBatchProgressStatus: " + ex.getMessage());
		}
	}

	/**
	 * Copies all popup textbox values into the {@link #selectedCheque} entity
	 * fields, assembles the sort code from City+Bank+Branch segments, and marks the
	 * cheque status as {@link ChequeStatus#READY}.
	 */
	private void applyPopupEditsToEntity() {
		if (selectedCheque == null)
			return;

		String city = txt(popCity);
		String bank = txt(popBank);
		String branch = txt(popBranch);
		if (!city.isEmpty() || !bank.isEmpty() || !branch.isEmpty())
			selectedCheque.setSortCode(city + bank + branch);

		String tc = txt(popTc);
		if (!tc.isEmpty())
			selectedCheque.setTransactionCode(tc);

		String acct = txt(popAccountNo);
		if (!acct.isEmpty())
			selectedCheque.setAccountNo(acct);

		String dt = txt(popChequeDate);
		if (!dt.isEmpty())
			selectedCheque.setChequeDate(dt);

		String amt = txt(popAmount).replace(",", "");
		if (!amt.isEmpty()) {
			try {
				selectedCheque.setAmount(new BigDecimal(amt));
			} catch (NumberFormatException ignored) {
			}
		}

		String words = txt(popAmountWords);
		selectedCheque.setAmountInWords(words.isEmpty() ? null : words);

		selectedCheque.setAmountWordsMismatch(false);
		selectedCheque.setStatus(ChequeStatus.READY.db());
	}

	// ══════════════════════════════════════════════════════════════════
	// DATA LOAD
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Populates the batch summary sidebar labels from the {@link BatchEntity}.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   loadBatchSummary()
	 *     └── batchService.getBatchById(batchId)
	 *           └── BatchDAOImpl.getBatchById(batchId)  [SELECT cts_batches]
	 * </pre>
	 */
	private void loadBatchSummary() {
		try {
			BatchEntity batch = batchService.getBatchById(batchId);
			if (batch != null) {
				safe(lblBatchId, batch.getBatchId());
				safe(lblBatchIdTitle, batch.getBatchId());
				safe(lblBranchCode, nvl(batch.getBranchCode(), "—"));
				safe(lblTotalAmount, fmtAmt(batch.getTotalAmount()));
			}
		} catch (Exception ex) {
			LOG.warning("loadBatchSummary: " + ex.getMessage());
			safe(lblBatchId, batchId);
			safe(lblBatchIdTitle, batchId);
		}
	}

	/**
	 * Loads all cheques for this batch and updates the summary counters.
	 *
	 * <p>
	 * Call chain:
	 * 
	 * <pre>
	 *   loadCheques()
	 *     └── chequeService.getChequesForBatch(batchId)
	 *           └── ChequeDAOImpl.getChequesForBatch(batchId)  [SELECT cts_cheques]
	 * </pre>
	 */
	private void loadCheques() {
		try {
			allCheques = chequeService.getChequesForBatch(batchId);
		} catch (Exception ex) {
			LOG.severe("loadCheques: " + ex.getMessage());
			allCheques = new ArrayList<>();
		}

		safe(lblTotalCheques, String.valueOf(allCheques.size()));
		long ready = allCheques.stream().filter(c -> chequeIsReady(c.getStatus())).count();
		long pending = allCheques.size() - ready;
		safe(lblVerifiedCheques, String.valueOf(ready));
		safe(lblPendingCheques, String.valueOf(pending));
	}

	// ══════════════════════════════════════════════════════════════════
	// FILTER + RENDER
	// ══════════════════════════════════════════════════════════════════

	/**
	 * Applies all active filters (search text, date range, status) to
	 * {@link #allCheques} and populates {@link #filtered}. Resets to first page and
	 * updates the filter-count label.
	 */
	private void applyFilter() {
		String q = txt(txtSearch).toLowerCase();
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy");

		java.util.Date fromDate = (dtDateFilter != null) ? dtDateFilter.getValue() : null;
		java.util.Date toDate = (dtDateTo != null) ? dtDateTo.getValue() : null;

		// Normalize toDate to end-of-day so same-day range works correctly
		final java.util.Date toDateInc;
		if (toDate != null) {
			java.util.Calendar cal = java.util.Calendar.getInstance();
			cal.setTime(toDate);
			cal.set(java.util.Calendar.HOUR_OF_DAY, 23);
			cal.set(java.util.Calendar.MINUTE, 59);
			cal.set(java.util.Calendar.SECOND, 59);
			toDateInc = cal.getTime();
		} else {
			toDateInc = null;
		}

		String st = cmbStatusFilter != null && cmbStatusFilter.getValue() != null ? cmbStatusFilter.getValue().trim()
				: "All";

		filtered = allCheques.stream().filter(c -> {
			boolean mQ = q.isEmpty() || (c.getChequeNo() != null && c.getChequeNo().toLowerCase().contains(q))
					|| (c.getDrawerName() != null && c.getDrawerName().toLowerCase().contains(q));
			boolean mS = "All".equals(st) || chequeDisplayLabel(c.getStatus()).equalsIgnoreCase(st);

			boolean mD = true;
			String raw = convertDateForDisplay(nvl(c.getChequeDate(), ""));
			if (!raw.isEmpty() && raw.matches("\\d{2}/\\d{2}/\\d{4}")) {
				try {
					java.util.Date cd = sdf.parse(raw);
					if (fromDate != null && cd.before(fromDate))
						mD = false;
					if (toDateInc != null && cd.after(toDateInc))
						mD = false;
				} catch (java.text.ParseException ignored) {
				}
			} else if (fromDate != null || toDateInc != null) {
				mD = false;
			}

			return mQ && mS && mD;
		}).collect(Collectors.toList());

		totalPages = filtered.isEmpty() ? 1 : (int) Math.ceil((double) filtered.size() / PAGE_SIZE);
		if (currentPage > totalPages)
			currentPage = totalPages;

		if (lblFilterCount != null) {
			boolean def = ("All".equals(st) || st.isBlank()) && q.isEmpty() && fromDate == null && toDate == null;
			lblFilterCount.setValue(def ? "" : filtered.size() + " match" + (filtered.size() != 1 ? "es" : ""));
		}
	}

	/**
	 * Clears and re-renders the listbox for the current page of {@link #filtered}.
	 * Updates pagination controls and range label.
	 */
	private void renderPage() {
		if (lbCheques == null)
			return;
		lbCheques.getItems().clear();

		int total = filtered.size();
		int startIdx = (currentPage - 1) * PAGE_SIZE;
		int endIdx = Math.min(startIdx + PAGE_SIZE, total);

		safe(lblChequeRange,
				total == 0 ? "No cheques match filter" : (startIdx + 1) + "–" + endIdx + " of " + total + " cheques");
		safe(lblPageInfo, "Page " + currentPage + " of " + totalPages);
		if (btnFirst != null)
			btnFirst.setDisabled(currentPage <= 1);
		if (btnPrev != null)
			btnPrev.setDisabled(currentPage <= 1);
		if (btnNext != null)
			btnNext.setDisabled(currentPage >= totalPages);
		if (btnLast != null)
			btnLast.setDisabled(currentPage >= totalPages);

		if (total == 0) {
			Listitem em = new Listitem();
			Listcell ec = new Listcell();
			ec.setClientAttribute("colspan", "6");
			Label ml = new Label("No cheques found — try a different filter.");
			ml.setStyle("display:block;text-align:center;color:#94a3b8;padding:24px;font-size:13px;");
			ec.appendChild(ml);
			em.appendChild(ec);
			lbCheques.appendChild(em);
			return;
		}

		List<ChequeEntity> page = filtered.subList(startIdx, endIdx);
		int rowNo = startIdx + 1;

		for (int i = 0; i < page.size(); i++) {
			ChequeEntity c = page.get(i);
			final int gIdx = startIdx + i;

			Listitem row = new Listitem();
			row.setSclass(selectedCheque != null && c.getId() != null && c.getId().equals(selectedCheque.getId())
					? "chq-row chq-row-sel"
					: "chq-row");

			Listcell numC = new Listcell(String.valueOf(rowNo++));
			numC.setSclass("chq-num-cell");
			row.appendChild(numC);

			Listcell noCell = new Listcell();
			Label arrowL = new Label("▶");
			arrowL.setSclass("chq-arrow");
			Label noLbl = new Label(nvl(c.getChequeNo(), "—"));
			noLbl.setSclass("chq-no-link");
			noCell.appendChild(arrowL);
			noCell.appendChild(noLbl);
			row.appendChild(noCell);

			row.appendChild(cell(nvl(c.getPayeeName(), "—")));

			Listcell amtC = new Listcell(fmtAmt(c.getAmount()));
			amtC.setSclass("amt-cell");
			row.appendChild(amtC);

			Listcell dtC = new Listcell(convertDateForDisplay(nvl(c.getChequeDate(), "—")));
			dtC.setSclass("chq-date-cell");
			row.appendChild(dtC);

			Listcell stC = new Listcell();
			stC.setSclass("chq-status-cell");
			Label stL = new Label(chequeDisplayLabel(c.getStatus()));
			stL.setSclass(chequeStatusChip(c.getStatus()));
			stC.appendChild(stL);
			row.appendChild(stC);

			row.addEventListener("onClick", e -> openChequePopup(c, gIdx));
			lbCheques.appendChild(row);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// HELPERS
	// ══════════════════════════════════════════════════════════════════

	/** Redirects to login if no active session. */
	private void guardSession() {
		if (!com.cts.util.SecurityUtil.isLoggedIn())
			Executions.sendRedirect("/zul/login.zul");
	}

	/**
	 * Converts YYYY-MM-DD (DB storage format) to DD/MM/YYYY (display format).
	 * Returns the input unchanged if it does not match YYYY-MM-DD.
	 */
	private String convertDateForDisplay(String d) {
		if (d == null || d.isBlank())
			return "";
		if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
			String[] p = d.split("-");
			return p[2] + "/" + p[1] + "/" + p[0];
		}
		return d;
	}

	private void safe(Label l, String v) {
		if (l != null)
			l.setValue(v != null ? v : "—");
	}

	private void setTxt(Textbox tb, String v) {
		if (tb != null)
			tb.setValue(v != null ? v : "");
	}

	private String txt(Textbox tb) {
		return (tb != null && tb.getValue() != null) ? tb.getValue().trim() : "";
	}

	private Listcell cell(String t) {
		return new Listcell(t != null ? t : "—");
	}

	private String fmtAmt(BigDecimal a) {
		if (a == null)
			return "₹0.00";
		return "₹" + String.format("%,.2f", a);
	}

	private String nvl(String v, String fb) {
		return (v != null && !v.isBlank()) ? v : fb;
	}

	/**
	 * Maps a cheque DB status to the Maker-facing display label shown in the
	 * listbox status chip.
	 *
	 * <p>
	 * Three labels used in this screen:
	 * <ul>
	 * <li>{@code "Ready"} — Maker saved all required fields.</li>
	 * <li>{@code "Submitted"} — batch submitted; cheque routed to V1 or V2
	 * queue.</li>
	 * <li>{@code "Pending"} — default; cheque not yet fully entered by Maker.</li>
	 * </ul>
	 *
	 * <p>
	 * Note: {@link ChequeStatus#SUBMITTED} is not used in live routing —
	 * {@link BatchServiceImpl#submitBatchForVerification} transitions cheques
	 * directly from READY to V1_PENDING or V2_PENDING.
	 */
	private String chequeDisplayLabel(String s) {
		ChequeStatus cs = ChequeStatus.fromDb(s);
		return switch (cs) {
		case READY -> "Ready";
		case V1_PENDING, V2_PENDING -> "Submitted";
		case VERIFIED, REJECTED, REFERRED -> "Submitted"; // post-verification
		default -> "Pending";
		};
	}

	/**
	 * Maps a cheque DB status to the CSS class for the listbox status chip.
	 *
	 * @see #chequeDisplayLabel(String)
	 */
	private String chequeStatusChip(String s) {
		ChequeStatus cs = ChequeStatus.fromDb(s);
		return switch (cs) {
		case READY -> "chip ch-pass";
		case V1_PENDING, V2_PENDING, VERIFIED, REJECTED, REFERRED -> "chip ch-submitted";
		default -> "chip ch-pending";
		};
	}

	/**
	 * Returns {@code true} if the cheque counts toward the "ready" tally used to
	 * gate the Save Batch button and summary counters.
	 *
	 * <p>
	 * READY, V1_PENDING, and V2_PENDING all count as "done" from the Maker's
	 * perspective — the cheque has been actioned and will not block submission.
	 */

	private boolean chequeIsReady(String s) {
		ChequeStatus cs = ChequeStatus.fromDb(s);
		return cs == ChequeStatus.READY || cs == ChequeStatus.V1_PENDING || cs == ChequeStatus.V2_PENDING
				|| cs == ChequeStatus.VERIFIED || cs == ChequeStatus.REJECTED || cs == ChequeStatus.REFERRED;
	}
}