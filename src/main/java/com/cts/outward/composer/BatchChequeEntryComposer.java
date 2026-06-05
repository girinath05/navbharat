/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchChequeEntryComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for the batch and cheque
 *                data-entry screen. Orchestrates ZIP upload,
 *                XML parsing, image extraction, MICR field
 *                binding, count-mismatch resolution, and
 *                batch submission to the DAO layer. All write
 *                operations are delegated through BatchService
 *                and ChequeService to keep UI logic separate
 *                from persistence concerns.
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
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.exception.BatchSubmitException;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;
import com.cts.outward.service.ChequeService;
import com.cts.outward.service.ChequeServiceImpl;
import com.cts.outward.service.ImportResult;
import com.cts.outward.service.ZipImportService;
import com.cts.outward.service.ZipImportServiceImpl;

public class BatchChequeEntryComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;
	private static final int PAGE_SIZE = 10;
	private static final Logger LOG = Logger.getLogger(BatchChequeEntryComposer.class.getName());

	// ── Service layer — NO direct DAO calls in composer ───────────────
	private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
	private final ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());
	private final ZipImportService zipImportService = new ZipImportServiceImpl();

	// ── Session keys ──────────────────────────────────────────────────
	private static final String SESS_LOGGED_USER = "loggedUser";
	private static final String SESS_USER_NAME = "userName";
	private static final String SESS_USER_ROLE = "userRole";
	private static final String SESS_USER_BRANCH = "userBranch";

	// ── In-memory state ───────────────────────────────────────────────
	private final List<BatchEntity> batches = new ArrayList<>();

	private String expandedBatchId = null;
	private int chequePage = 1;
	private String pendingBatchId = null;
	private ImportResult pendingMismatchResult = null;

	private static final int IMG_CHUNK_SIZE = 40_000;

	// ── Wired: header ─────────────────────────────────────────────────
	@Wire
	private Label lblHdrUser;
	@Wire
	private Label lblHdrRole;
	@Wire
	private Button btnLogout;
	@Wire
	private Button btnCreateBatch;
	@Wire
	private Button btnViewBatches;
	@Wire
	private Button btnCloseScanModal;
	@Wire
	private Button btnScanCancelDiscard;

	// ── Wired: stats ──────────────────────────────────────────────────
	@Wire
	private Label lblStatBatches;
	@Wire
	private Label lblStatCheques;
	@Wire
	private Label lblStatPending;

	// ── Wired: batch table ────────────────────────────────────────────
	@Wire
	private Listbox lbBatches;

	// ── Wired: create-batch modal ─────────────────────────────────────
	@Wire
	private Textbox txtBranch;
	@Wire
	private Textbox txtBatchType;
	@Wire
	private Intbox txtChequeCount;
	@Wire
	private Decimalbox txtExpectedAmount;

	// ── Wired: preview ────────────────────────────────────────────────
	@Wire
	private Button btnClosePreview;
	@Wire
	private Button btnScanUploadZip;
	@Wire
	private Label lblPreviewChequeNo;
	@Wire
	private Label lblPreviewAccountNo;
	@Wire
	private Label lblPreviewDrawer;
	@Wire
	private Label lblPreviewPayee;
	@Wire
	private Label lblPreviewAmount;
	@Wire
	private Label lblPreviewDate;
	@Wire
	private Label lblPreviewStatus;
	@Wire
	private Label lblPreviewIqa;

	// ── Wired: cheque popup ───────────────────────────────────────────
	@Wire
	private Label bcePopTitle;
	@Wire
	private Textbox bcePopCheckNo;
	@Wire
	private Textbox bcePopCity;
	@Wire
	private Textbox bcePopBank;
	@Wire
	private Textbox bcePopBranch;
	@Wire
	private Textbox bcePopBaseNo;
	@Wire
	private Textbox bcePopTc;
	@Wire
	private Textbox bcePopAccountNo;
	@Wire
	private Textbox bcePopDate;
	@Wire
	private Textbox bcePopAmount;
	@Wire
	private Textbox bcePopAmountWords;
	@Wire
	private Label bceIqaResult;
	@Wire
	private Label bceIqaDup;
	@Wire
	private Label bceIqaNoMicr;
	@Wire
	private Label bceIqaMicrMismatch;
	@Wire
	private Textbox bcePopCreditAccNo;
	@Wire
	private Button bceBtnAcctLookup;
	@Wire
	private Label bceLblAcctHolderName;
	@Wire
	private Label bceLblAcctStatus;
	@Wire
	private Label bceLblAcctSubcategory;
	@Wire
	private Label bceErrCheckNo;
	@Wire
	private Label bceErrCity;
	@Wire
	private Label bceErrBank;
	@Wire
	private Label bceErrBranch;
	@Wire
	private Label bceErrBaseNo;
	@Wire
	private Label bceErrTc;
	@Wire
	private Label bceErrAccountNo;
	@Wire
	private Label bceErrDate;
	@Wire
	private Label bceErrAmount;
	@Wire
	private Label bcePgInfo;
	@Wire
	private Button bceBtnPopClose;
	@Wire
	private Button bceBtnPopPrev;
	@Wire
	private Button bceBtnPopNext;
	@Wire
	private Button bceBtnPopSave;

	// ── Popup state ───────────────────────────────────────────────────
	private List<ChequeEntity> expandedCheques = new ArrayList<>();
	private int selectedIndex = -1;

	// ══════════════════════════════════════════════════════════════════
	// LIFECYCLE
	// ══════════════════════════════════════════════════════════════════

	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);

		if (!isSessionValid()) {
			Executions.sendRedirect("index.zul");
			return;
		}

		loadUserInfo();
		loadBatchesFromService();
		renderBatches();
		refreshStats();
		updateBatchCountLabel();

		Object autoOpen = Sessions.getCurrent().getAttribute("autoOpenBatchModal");
		if (Boolean.TRUE.equals(autoOpen)) {
			Sessions.getCurrent().removeAttribute("autoOpenBatchModal");
			Clients.evalJavaScript("bce_openBatchModal();");
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// SESSION
	// ══════════════════════════════════════════════════════════════════

	private boolean isSessionValid() {
		return Sessions.getCurrent().getAttribute(SESS_LOGGED_USER) != null;
	}

	// ══════════════════════════════════════════════════════════════════
	// HEADER
	// ══════════════════════════════════════════════════════════════════

	private void loadUserInfo() {
		if (lblHdrUser != null)
			lblHdrUser.setValue(sessionStr(SESS_USER_NAME, "USER").toUpperCase());
		if (lblHdrRole != null)
			lblHdrRole.setValue(sessionStr(SESS_USER_ROLE, "ADMINISTRATOR").toUpperCase());
	}

	// ══════════════════════════════════════════════════════════════════
	// LOAD FROM SERVICE
	// ══════════════════════════════════════════════════════════════════

	private void loadBatchesFromService() {
		batches.clear();
		try {
			batches.addAll(batchService.getAllBatches());
		} catch (Exception ex) {
			Clients.showNotification("⚠ Could not load batches: " + ex.getMessage(), "warning", null, "middle_center",
					4000);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// ZIP UPLOAD — direct (no pre-created batch)
	// ══════════════════════════════════════════════════════════════════

	@Listen("onUpload = #btnUploadZip")
	public void onZipUpload(UploadEvent event) {
		Media media = event.getMedia();
		if (media == null) {
			Clients.showNotification("No file received.", "error", null, "middle_center", 3000);
			return;
		}
		if (!media.getName().toLowerCase().endsWith(".zip")) {
			Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000);
			return;
		}
		Clients.showBusy("Processing ZIP — please wait…");
		try {
			byte[] zipBytes = readAllBytes(media.getStreamData());
			String branch = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");
			ImportResult result = zipImportService.importZip(zipBytes, media.getName(), branch, createdBy);

			batches.add(0, result.getBatch());
			renderBatches();
			refreshStats();
			updateBatchCountLabel();
			Clients.clearBusy();

			String zipAmt = result.getBatch().getTotalAmount() != null
					? String.format("%,.2f", result.getBatch().getTotalAmount())
					: "0.00";
			Clients.evalJavaScript("bce_showBatchSuccessToast('" + result.getBatch().getBatchId() + "',"
					+ result.getCheques().size() + ",'" + zipAmt + "');");
		} catch (Exception ex) {
			Clients.clearBusy();
			Clients.showNotification("❌ Error: " + ex.getMessage(), "error", null, "middle_center", 6000);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// CREATE MANUAL BATCH
	// ══════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnCreateBatch")
	public void onCreateBatch() {
		if (btnCreateBatch != null)
			btnCreateBatch.setDisabled(true);

		int count = (txtChequeCount != null) ? txtChequeCount.getValue() : 0;
		BigDecimal expected = (txtExpectedAmount != null) ? txtExpectedAmount.getValue() : BigDecimal.ZERO;

		if (count <= 0) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			Clients.showNotification("Total Cheques must be greater than 0.", "warning", null, "middle_center", 2500);
			return;
		}
		if (expected == null || expected.compareTo(BigDecimal.ZERO) <= 0) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			Clients.showNotification("Control Amount must be greater than 0.", "warning", null, "middle_center", 2500);
			return;
		}

		Clients.evalJavaScript("bce_closeBatchModal();");

		try {
			String branch = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");
			// ✅ BatchService handles ID generation + saveBatch
			BatchEntity batch = batchService.createBatch(branch, count, expected, createdBy);

			pendingBatchId = batch.getBatchId();
			batches.add(0, batch);
			renderBatches();
			refreshStats();
			updateBatchCountLabel();

			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			Clients.evalJavaScript("bce_openScanModal('" + batch.getBatchId() + "');");

		} catch (Exception ex) {
			if (btnCreateBatch != null)
				btnCreateBatch.setDisabled(false);
			Clients.showNotification("❌ Could not create batch: " + ex.getMessage(), "error", null, "middle_center",
					5000);
		}
	}

	@Listen("onClick = #btnViewBatches")
	public void onViewBatches() {
		com.cts.composer.DashboardComposer.getInstance().loadPage("/zul/outward/batchManagement.zul");
	}

	@Listen("onClick = #btnCloseScanModal")
	public void onCloseScanModal() {
		Clients.evalJavaScript("bce_closeScanModal();");
		discardPendingBatch();
	}

	@Listen("onClick = #btnScanCancelDiscard")
	public void onScanCancelDiscard() {
		discardPendingBatch();
	}

	// ══════════════════════════════════════════════════════════════════
	// SCAN MODAL — ZIP UPLOAD (Step 2)
	// ══════════════════════════════════════════════════════════════════

	@Listen("onUpload = #btnScanUploadZip")
	public void onScanZipUpload(UploadEvent event) {
		Media media = event.getMedia();
		if (media == null) {
			Clients.showNotification("No file received.", "error", null, "middle_center", 3000);
			return;
		}
		if (!media.getName().toLowerCase().endsWith(".zip")) {
			Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000);
			return;
		}

		Clients.evalJavaScript("bce_scanShowProgress('Processing ZIP — please wait…');");
		Clients.showBusy("Processing ZIP…");

		try {
			byte[] zipBytes = readAllBytes(media.getStreamData());
			String branch = sessionStr(SESS_USER_BRANCH, "MUM01");
			String createdBy = sessionStr(SESS_USER_NAME, "SYSTEM");
			ImportResult result = zipImportService.importZip(zipBytes, media.getName(), branch, createdBy,
					pendingBatchId);

			Clients.clearBusy();

			// ── Mismatch check ─────────────────────────────────────────
			int expected = batches.stream().filter(b -> b.getBatchId().equals(pendingBatchId))
					.mapToInt(BatchEntity::getExpectedCheques).findFirst().orElse(0);
			int actual = result.getCheques().size();

			if (expected > 0 && actual != expected) {
				pendingMismatchResult = result;
				Clients.evalJavaScript("bce_scanHideProgress();");
				Clients.evalJavaScript("bce_closeScanModal();");
				Clients.evalJavaScript("bce_openMismatchDialog();");
				return;
			}

			finishSuccessfulImport(result);

		} catch (Exception ex) {
			Clients.clearBusy();
			Clients.evalJavaScript("bce_scanHideProgress();");
			Clients.showNotification("Upload failed: " + ex.getMessage(), "error", null, "middle_center", 5000);
			pendingBatchId = null;
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// MISMATCH — ACCEPT / DISCARD
	// ══════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnMismatchAccept")
	public void onMismatchAccept() {
		Clients.evalJavaScript("bce_closeMismatchDialog();");
		if (pendingMismatchResult != null)
			finishSuccessfulImport(pendingMismatchResult);
		pendingMismatchResult = null;
	}

	@Listen("onClick = #btnMismatchDiscard")
	public void onMismatchDiscard() {
		Clients.evalJavaScript("bce_closeMismatchDialog();");
		String batchToDelete = pendingBatchId;
		pendingMismatchResult = null;
		pendingBatchId = null;

		if (batchToDelete != null) {
			try {
				// ✅ service handles deleteBatchAndCheques
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

	// ══════════════════════════════════════════════════════════════════
	// SHARED SUCCESS FINISH
	// ══════════════════════════════════════════════════════════════════

	private void finishSuccessfulImport(ImportResult result) {
		loadBatchesFromService();
		renderBatches();
		refreshStats();
		updateBatchCountLabel();
		pendingBatchId = null;

		String amtStr = result.getBatch().getTotalAmount() != null
				? String.format("%,.2f", result.getBatch().getTotalAmount())
				: "0.00";
		Clients.evalJavaScript("bce_showBatchSuccessToast('" + result.getBatch().getBatchId() + "',"
				+ result.getCheques().size() + ",'" + amtStr + "');");
	}

	// ══════════════════════════════════════════════════════════════════
	// MISC LISTENERS
	// ══════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnClosePreview")
	public void onClosePreview() {
		Clients.evalJavaScript("document.getElementById('chequeDetailPanel').style.display='none';");
	}

	@Listen("onClick = #btnLogout")
	public void onLogout(Event event) {
		Sessions.getCurrent().invalidate();
		Executions.sendRedirect("index.zul");
	}

	// ══════════════════════════════════════════════════════════════════
	// RENDER ACCORDION BATCH TABLE
	// ══════════════════════════════════════════════════════════════════

	private void renderBatches() {
		if (lbBatches == null)
			return;
		lbBatches.getItems().clear();

		// Filter orphan batches — Pending + no amount + no cheques
		List<BatchEntity> renderable = batches.stream()
				.filter(b -> !("Pending".equals(b.getStatus())
						&& (b.getTotalAmount() == null || b.getTotalAmount().compareTo(BigDecimal.ZERO) == 0)
						&& loadChequesForBatch(b.getBatchId()).isEmpty()))
				.collect(java.util.stream.Collectors.toList());

		if (renderable.isEmpty()) {
			Listitem empty = new Listitem();
			Listcell ec = new Listcell();
			ec.setClientAttribute("colspan", "8");
			Label lbl = new Label("No batches found. Click '+ Create Batch' to get started.");
			lbl.setStyle("display:block;text-align:center;color:#94a3b8;padding:32px;font-size:13px;");
			ec.appendChild(lbl);
			empty.appendChild(ec);
			lbBatches.appendChild(empty);
			return;
		}

		for (BatchEntity batch : renderable) {
			appendBatchRow(batch, batch.getBatchId().equals(expandedBatchId));
		}
	}

	private void appendBatchRow(BatchEntity batch, boolean expanded) {
		Listitem batchRow = new Listitem();
		batchRow.setValue(batch);
		batchRow.setSclass(expanded ? "batch-row batch-expanded" : "batch-row");

		Listcell idCell = new Listcell();
		Label idLbl = new Label((expanded ? "▼ " : "▶ ") + batch.getBatchId());
		idLbl.setStyle("font-weight:700;color:#0f2347;cursor:pointer;");
		idCell.appendChild(idLbl);
		batchRow.appendChild(idCell);

		batchRow.appendChild(cell(nullSafe(batch.getBranchCode(), "—")));
		batchRow.appendChild(cell(String.valueOf(batch.getTotalCheques())));
		batchRow.appendChild(amtCell(formatAmt(batch.getExpectedAmount())));
		batchRow.appendChild(amtCell(formatAmt(batch.getTotalAmount())));

		Listcell typeCell = new Listcell();
		Label typeLbl = new Label("—");
		typeLbl.setSclass("type-chip");
		typeCell.appendChild(typeLbl);
		batchRow.appendChild(typeCell);

		Listcell statusCell = new Listcell();
		Label statusLbl = new Label(nullSafe(batch.getStatus(), "—"));
		statusLbl.setSclass(batchStatusChip(batch.getStatus()));
		statusCell.appendChild(statusLbl);
		batchRow.appendChild(statusCell);

		batchRow.appendChild(new Listcell(""));
		batchRow.addEventListener("onClick", e -> {
			chequePage = 1;
			toggleBatchExpand(batch);
		});
		lbBatches.appendChild(batchRow);

		if (expanded) {
			Listitem expandRow = new Listitem();
			expandRow.setSclass("batch-expand-row");
			Listcell expandCell = new Listcell();
			expandCell.setClientAttribute("colspan", "8");
			expandCell.setStyle("padding:0;margin:0;border:none;width:100%;");

			Div wrapper = new Div();
			wrapper.setStyle("display:block;width:100%;overflow-x:auto;box-sizing:border-box;");
			wrapper.appendChild(buildSummaryBar(batch));

			List<ChequeEntity> cheques = loadChequesForBatch(batch.getBatchId());
			int total = cheques.size();
			int totPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
			int startIdx = (chequePage - 1) * PAGE_SIZE;
			int endIdx = Math.min(startIdx + PAGE_SIZE, total);
			List<ChequeEntity> pageItems = startIdx < total ? cheques.subList(startIdx, endIdx) : new ArrayList<>();

			wrapper.appendChild(buildChequeSubGrid(pageItems));
			if (total > 0)
				wrapper.appendChild(buildPaginationBar(total, totPages));

			expandCell.appendChild(wrapper);
			expandRow.appendChild(expandCell);
			lbBatches.appendChild(expandRow);
		}
	}

	private void toggleBatchExpand(BatchEntity batch) {
		expandedBatchId = batch.getBatchId().equals(expandedBatchId) ? null : batch.getBatchId();
		renderBatches();
		Clients.evalJavaScript(
				"setTimeout(function(){" + "  var rows=document.querySelectorAll('.z-listitem.batch-expanded');"
						+ "  if(rows.length) rows[0].scrollIntoView({behavior:'smooth',block:'nearest'});" + "},150);");
	}

	// ══════════════════════════════════════════════════════════════════
	// SUMMARY BAR
	// ══════════════════════════════════════════════════════════════════

	private Div buildSummaryBar(BatchEntity batch) {
		Div bar = new Div();
		bar.setSclass("batch-summary-bar");
		bar.setStyle("display:flex;align-items:center;gap:20px;background:#1A3A6B;color:#fff;"
				+ "padding:8px 16px;font-size:12px;font-weight:600;width:100%;box-sizing:border-box;");

		bar.appendChild(summaryItem("Cheques", String.valueOf(batch.getTotalCheques())));
		bar.appendChild(summaryItem("Control Amt", formatAmt(batch.getExpectedAmount())));
		bar.appendChild(summaryItem("Actual Amt", formatAmt(batch.getTotalAmount())));
		bar.appendChild(summaryItem("Branch", nullSafe(batch.getBranchCode(), "—")));

		Div sItem = new Div();
		sItem.setStyle("display:flex;align-items:center;gap:6px;");
		Div sLbl = new Div();
		sLbl.appendChild(new Label("Status:"));
		sLbl.setStyle("color:rgba(255,255,255,.65);font-size:11px;");
		Div sVal = new Div();
		sVal.appendChild(new Label(nullSafe(batch.getStatus(), "—")));
		sVal.setStyle("color:#4ade80;font-weight:700;");
		sItem.appendChild(sLbl);
		sItem.appendChild(sVal);
		bar.appendChild(sItem);
		return bar;
	}

	private Div summaryItem(String label, String value) {
		Div item = new Div();
		item.setStyle("display:flex;align-items:center;gap:5px;");
		Div lDiv = new Div();
		lDiv.appendChild(new Label(label + ":"));
		lDiv.setStyle("color:rgba(255,255,255,.65);font-size:11px;");
		Div vDiv = new Div();
		vDiv.appendChild(new Label(value));
		vDiv.setStyle("color:#fff;font-weight:700;");
		item.appendChild(lDiv);
		item.appendChild(vDiv);
		return item;
	}

	// ══════════════════════════════════════════════════════════════════
	// CHEQUE SUB-GRID
	// ══════════════════════════════════════════════════════════════════

	private Listbox buildChequeSubGrid(List<ChequeEntity> cheques) {
		expandedCheques = new ArrayList<>(cheques);
		Listbox lb = new Listbox();
		lb.setSclass("cheque-table");
		lb.setMold("default");
		lb.setStyle("width:100%;border:none;border-collapse:collapse;table-layout:fixed;");

		Listhead lh = new Listhead();
		lh.setSizable(true);
		lh.appendChild(lhdr("CHEQUE ID", "10%"));
		lh.appendChild(lhdr("CHEQUE NO", "12%"));
		lh.appendChild(lhdr("ACCOUNT NO", "18%"));
		lh.appendChild(lhdr("AMOUNT", "12%"));
		lh.appendChild(lhdr("DRAWER", "20%"));
		lh.appendChild(lhdr("DATE", "10%"));
		lh.appendChild(lhdr("IQA", "8%"));
		lh.appendChild(lhdr("STATUS", "10%"));
		lb.appendChild(lh);

		if (cheques.isEmpty()) {
			Listitem empty = new Listitem();
			Listcell ec = new Listcell();
			ec.setClientAttribute("colspan", "8");
			Label emLbl = new Label("No cheques found for this batch.");
			emLbl.setStyle("display:block;text-align:center;color:#94a3b8;padding:14px;font-size:12px;");
			ec.appendChild(emLbl);
			empty.appendChild(ec);
			lb.appendChild(empty);
			return lb;
		}

		for (ChequeEntity c : cheques) {
			Listitem item = new Listitem();
			item.setValue(c);
			item.setSclass("cheque-row");

			item.appendChild(cell(nullSafe(c.getChequeId(), "—")));
			item.appendChild(cell(nullSafe(c.getChequeNo(), "—")));
			item.appendChild(cell(nullSafe(c.getAccountNo(), "—")));
			item.appendChild(amtCell(formatAmt(c.getAmount())));
			item.appendChild(cell(nullSafe(c.getDrawerName(), "—")));
			item.appendChild(cell(nullSafe(c.getChequeDate(), "—")));

			Listcell iqaCell = new Listcell();
			Label iqaLbl = new Label(nullSafe(c.getIqaStatus(), "?"));
			iqaLbl.setSclass("Pass".equalsIgnoreCase(c.getIqaStatus()) ? "chip ch-pass" : "chip ch-fail");
			iqaCell.appendChild(iqaLbl);
			item.appendChild(iqaCell);

			Listcell stCell = new Listcell();
			Label stLbl = new Label(nullSafe(c.getStatus(), "—"));
			stLbl.setSclass(chequeStatusChip(c.getStatus()));
			stCell.appendChild(stLbl);
			item.appendChild(stCell);

			final ChequeEntity cheque = c;
			final String uuid = item.getUuid();
			item.addEventListener("onClick", e -> onChequeClick(cheque, uuid));
			lb.appendChild(item);
		}
		return lb;
	}

	// ══════════════════════════════════════════════════════════════════
	// PAGINATION BAR
	// ══════════════════════════════════════════════════════════════════

	private Div buildPaginationBar(int total, int totalPages) {
		Div bar = new Div();
		Div btnWrap = new Div();
		bar.setSclass("bce-pagination");
		bar.setStyle("width:100%;box-sizing:border-box;display:flex;align-items:center;"
				+ "justify-content:space-between;padding:8px 16px;background:#f8fafc;border-top:1px solid #e2e8f0;");
		btnWrap.setSclass("bce-page-btns");
		btnWrap.setStyle("display:flex;align-items:center;gap:4px;");

		Button prevBtn = new Button("◄");
		prevBtn.setSclass("bce-page-btn");
		prevBtn.setDisabled(chequePage <= 1);
		if (chequePage > 1)
			prevBtn.addEventListener("onClick", ev -> {
				chequePage--;
				renderBatches();
			});
		btnWrap.appendChild(prevBtn);

		for (int p = 1; p <= totalPages; p++) {
			final int pg = p;
			Button pgBtn = new Button(String.valueOf(p));
			pgBtn.setSclass(pg == chequePage ? "bce-page-btn active" : "bce-page-btn");
			if (pg != chequePage)
				pgBtn.addEventListener("onClick", ev -> {
					chequePage = pg;
					renderBatches();
				});
			btnWrap.appendChild(pgBtn);
		}

		Button nextBtn = new Button("►");
		nextBtn.setSclass("bce-page-btn");
		nextBtn.setDisabled(chequePage >= totalPages);
		if (chequePage < totalPages)
			nextBtn.addEventListener("onClick", ev -> {
				chequePage++;
				renderBatches();
			});
		btnWrap.appendChild(nextBtn);
		bar.appendChild(btnWrap);

		int startRow = Math.min((chequePage - 1) * PAGE_SIZE + 1, total);
		int endRow = Math.min(chequePage * PAGE_SIZE, total);
		Div info = new Div();
		info.setSclass("bce-page-info");
		info.setStyle("font-size:12px;color:#64748b;font-weight:500;");
		info.appendChild(new Label(
				total + " cheques · Page " + chequePage + "/" + totalPages + "  (" + startRow + "–" + endRow + ")"));
		bar.appendChild(info);
		return bar;
	}

	// ══════════════════════════════════════════════════════════════════
	// CHEQUE CLICK
	// ══════════════════════════════════════════════════════════════════

	private void onChequeClick(ChequeEntity cheque, String itemUuid) {
		for (int i = 0; i < expandedCheques.size(); i++) {
			if (expandedCheques.get(i).getId() != null && expandedCheques.get(i).getId().equals(cheque.getId())) {
				selectedIndex = i;
				break;
			}
		}
		openBcePopup(cheque);
		Clients.evalJavaScript("setTimeout(function(){"
				+ "  document.querySelectorAll('.cheque-row-sel').forEach(function(r){r.classList.remove('cheque-row-sel');});"
				+ "  var el=document.getElementById('" + itemUuid + "');"
				+ "  if(el) el.classList.add('cheque-row-sel');" + "},50);");
	}

	// ══════════════════════════════════════════════════════════════════
	// POPUP — OPEN / NAV / SAVE
	// ══════════════════════════════════════════════════════════════════

	private void openBcePopup(ChequeEntity c) {
		if (bcePopTitle != null)
			bcePopTitle.setValue("Cheque #" + nullSafe(c.getChequeNo(), "—"));

		String sc = c.getSortCode() != null ? c.getSortCode() : "";
		setTb(bcePopCheckNo, c.getChequeNo());
		setTb(bcePopCity, sc.length() >= 3 ? sc.substring(0, 3) : "");
		setTb(bcePopBank, sc.length() >= 6 ? sc.substring(3, 6) : "");
		setTb(bcePopBranch, sc.length() >= 9 ? sc.substring(6, 9) : "");
		setTb(bcePopBaseNo, "000000");
		setTb(bcePopTc, c.getTransactionCode());

		setTb(bcePopAccountNo, c.getAccountNo());
		setTb(bcePopDate, convertDateForDisplay(c.getChequeDate()));
		setTb(bcePopAmount, c.getAmount() != null ? c.getAmount().toPlainString() : "");
		setTb(bcePopAmountWords, "");

		setLabel(bceIqaResult, c.getIqaStatus());
		setLabel(bceIqaDup, c.isDuplicate() ? "Yes" : "No");
		setLabel(bceIqaNoMicr, sc.isBlank() ? "Yes" : "No");
		setLabel(bceIqaMicrMismatch, "NA");

		setTb(bcePopCreditAccNo, c.getAccountNo());
		setLabel(bceLblAcctHolderName, "—");
		setLabel(bceLblAcctStatus, "—");
		setLabel(bceLblAcctSubcategory, "—");

		int total = expandedCheques.size();
		if (bcePgInfo != null)
			bcePgInfo.setValue((selectedIndex + 1) + " / " + total);
		if (bceBtnPopPrev != null)
			bceBtnPopPrev.setDisabled(selectedIndex <= 0);
		if (bceBtnPopNext != null)
			bceBtnPopNext.setDisabled(selectedIndex >= total - 1);

		clearAllBcePopupErrors();
		Clients.evalJavaScript("bce_bceImagesLoading();");
		Long dbId = c.getId();
		Clients.evalJavaScript("bce_bceRenderImages(" + (dbId != null ? dbId : 0) + ");");
		Clients.evalJavaScript("bce_showChequePop();");
	}

	@Listen("onClick = #bceBtnPopClose")
	public void onBcePopClose() {
		Clients.evalJavaScript("bce_hideChequePop();");
	}

	@Listen("onClick = #bceBtnPopPrev")
	public void onBcePopPrev() {
		if (selectedIndex > 0) {
			selectedIndex--;
			openBcePopup(expandedCheques.get(selectedIndex));
		}
	}

	@Listen("onClick = #bceBtnPopNext")
	public void onBcePopNext() {
		if (selectedIndex < expandedCheques.size() - 1) {
			selectedIndex++;
			openBcePopup(expandedCheques.get(selectedIndex));
		}
	}

	@Listen("onClick = #bceBtnPopSave")
	public void onBcePopSave() {
		java.util.Map<String, String> errors = validateBcePopupMicr();
		if (!errors.isEmpty()) {
			markBceFieldsInvalid(errors);
			return;
		}

		ChequeEntity c = expandedCheques.get(selectedIndex);
		applyBcePopupEditsToEntity(c);

		try {
			// ✅ service sets status=Ready + calls updateChequeFields
			chequeService.saveChequeFields(c);
			Clients.showNotification("✔ Saved — status set to Ready", "info", null, "top_right", 2500);
		} catch (Exception ex) {
			Clients.showNotification("❌ Save failed: " + ex.getMessage(), "error", null, "middle_center", 4000);
			return;
		}

		if (selectedIndex < expandedCheques.size() - 1) {
			selectedIndex++;
			openBcePopup(expandedCheques.get(selectedIndex));
		} else {
			Clients.evalJavaScript("bce_hideChequePop();");
			loadBatchesFromService();
			renderBatches();
		}
	}

	@Listen("onClick = #bceBtnAcctLookup")
	public void onBceAcctLookup() {
		if (bcePopCreditAccNo == null)
			return;
		String acctNo = bcePopCreditAccNo.getValue() != null ? bcePopCreditAccNo.getValue().trim() : "";
		if (acctNo.isEmpty()) {
			Clients.showNotification("Enter an account number first.", "warning", null, "top_right", 2500);
			return;
		}
		// ✅ service handles account lookup — no direct Hibernate in composer
		String[] info = chequeService.lookupAccount(acctNo);
		setLabel(bceLblAcctHolderName, info[0]);
		setLabel(bceLblAcctStatus, info[1]);
		setLabel(bceLblAcctSubcategory, info[2]);
	}

	private void applyBcePopupEditsToEntity(ChequeEntity c) {
		String city = bcePopCity != null ? bcePopCity.getValue().trim() : "";
		String bank = bcePopBank != null ? bcePopBank.getValue().trim() : "";
		String branch = bcePopBranch != null ? bcePopBranch.getValue().trim() : "";
		if (!city.isEmpty() || !bank.isEmpty() || !branch.isEmpty())
			c.setSortCode(city + bank + branch);

		String tc = bcePopTc != null ? bcePopTc.getValue().trim() : "";
		if (!tc.isEmpty())
			c.setTransactionCode(tc);

		String acct = bcePopAccountNo != null ? bcePopAccountNo.getValue().trim() : "";
		if (!acct.isEmpty())
			c.setAccountNo(acct);

		String creditAccNo = bcePopCreditAccNo != null ? bcePopCreditAccNo.getValue().trim() : "";
		if (!creditAccNo.isEmpty())
			c.setAccountNo(creditAccNo); // payee lookup overrides

		String dt = bcePopDate != null ? bcePopDate.getValue().trim() : "";
		if (!dt.isEmpty())
			c.setChequeDate(dt);

		String amt = bcePopAmount != null ? bcePopAmount.getValue().trim().replace(",", "") : "";
		if (!amt.isEmpty()) {
			try {
				c.setAmount(new BigDecimal(amt));
			} catch (NumberFormatException ignored) {
			}
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// VALIDATION
	// ══════════════════════════════════════════════════════════════════

	private java.util.Map<String, String> validateBcePopupMicr() {
		java.util.Map<String, String> err = new java.util.LinkedHashMap<>();

		String chqNo = tbVal(bcePopCheckNo);
		if (chqNo.isEmpty())
			err.put("bcePopCheckNo", "Cheque No. is required.");
		else if (!chqNo.matches("\\d{6}"))
			err.put("bcePopCheckNo", "Must be exactly 6 numeric digits (got " + chqNo.length() + ").");

		String city = tbVal(bcePopCity);
		if (city.isEmpty())
			err.put("bcePopCity", "City code is required.");
		else if (!city.matches("\\d{3}"))
			err.put("bcePopCity", "Must be exactly 3 numeric digits.");

		String bank = tbVal(bcePopBank);
		if (bank.isEmpty())
			err.put("bcePopBank", "Bank code is required.");
		else if (!bank.matches("\\d{3}"))
			err.put("bcePopBank", "Must be exactly 3 numeric digits.");

		String branch = tbVal(bcePopBranch);
		if (branch.isEmpty())
			err.put("bcePopBranch", "Branch code is required.");
		else if (!branch.matches("\\d{3}"))
			err.put("bcePopBranch", "Must be exactly 3 numeric digits.");

		String baseNo = tbVal(bcePopBaseNo);
		if (!baseNo.isEmpty() && !baseNo.matches("\\d{6,7}"))
			err.put("bcePopBaseNo", "Base No. must be 6 or 7 numeric digits.");

		String tc = tbVal(bcePopTc);
		if (tc.isEmpty())
			err.put("bcePopTc", "TC is required.");
		else if (!tc.matches("\\d{2,3}"))
			err.put("bcePopTc", "TC must be 2 or 3 numeric digits.");

		String acct = tbVal(bcePopAccountNo);
		if (acct.isEmpty())
			err.put("bcePopAccountNo", "Drawee Account No. is required.");
		else if (acct.length() > 20)
			err.put("bcePopAccountNo", "Must not exceed 20 characters.");

		String dt = tbVal(bcePopDate);
		if (dt.isEmpty()) {
			err.put("bcePopDate", "Date is required.");
		} else if (!dt.matches("\\d{2}/\\d{2}/\\d{4}")) {
			err.put("bcePopDate", "Use DD/MM/YYYY format.");
		} else {
			try {
				int day = Integer.parseInt(dt.substring(0, 2));
				int mon = Integer.parseInt(dt.substring(3, 5));
				int yr = Integer.parseInt(dt.substring(6));
				if (day < 1 || day > 31 || mon < 1 || mon > 12 || yr < 2000 || yr > 2100)
					err.put("bcePopDate", "Date values out of range.");
			} catch (NumberFormatException ex) {
				err.put("bcePopDate", "Invalid date. Use DD/MM/YYYY.");
			}
		}

		String amt = tbVal(bcePopAmount);
		if (amt.isEmpty()) {
			err.put("bcePopAmount", "Amount is required.");
		} else {
			try {
				BigDecimal bd = new BigDecimal(amt.replace(",", ""));
				if (bd.compareTo(BigDecimal.ZERO) <= 0)
					err.put("bcePopAmount", "Amount must be greater than zero.");
			} catch (NumberFormatException ex) {
				err.put("bcePopAmount", "Enter a valid number (e.g. 2000000.00).");
			}
		}

		return err;
	}

	private void markBceFieldsInvalid(java.util.Map<String, String> errors) {
		clearAllBcePopupErrors();
		for (java.util.Map.Entry<String, String> e : errors.entrySet()) {
			switch (e.getKey()) {
			case "bcePopCheckNo" -> bceMark(bcePopCheckNo, bceErrCheckNo, e.getValue(), true);
			case "bcePopCity" -> bceMark(bcePopCity, bceErrCity, e.getValue(), true);
			case "bcePopBank" -> bceMark(bcePopBank, bceErrBank, e.getValue(), true);
			case "bcePopBranch" -> bceMark(bcePopBranch, bceErrBranch, e.getValue(), true);
			case "bcePopBaseNo" -> bceMark(bcePopBaseNo, bceErrBaseNo, e.getValue(), true);
			case "bcePopTc" -> bceMark(bcePopTc, bceErrTc, e.getValue(), true);
			case "bcePopAccountNo" -> bceMark(bcePopAccountNo, bceErrAccountNo, e.getValue(), true);
			case "bcePopDate" -> bceMark(bcePopDate, bceErrDate, e.getValue(), false);
			case "bcePopAmount" -> bceMark(bcePopAmount, bceErrAmount, e.getValue(), true);
			}
		}
	}

	private void bceMark(Textbox tb, Label errLbl, String msg, boolean mono) {
		if (tb != null)
			tb.setSclass(mono ? "pop-input pop-input-mono pop-input-error" : "pop-input pop-input-error");
		if (errLbl != null) {
			errLbl.setValue(msg);
			errLbl.setSclass("err-tip-show");
		}
	}

	private void bceClear(Textbox tb, Label errLbl, boolean mono) {
		if (tb != null)
			tb.setSclass(mono ? "pop-input pop-input-mono" : "pop-input");
		if (errLbl != null) {
			errLbl.setValue("");
			errLbl.setSclass("err-tip-hidden");
		}
	}

	private void clearAllBcePopupErrors() {
		bceClear(bcePopCheckNo, bceErrCheckNo, true);
		bceClear(bcePopCity, bceErrCity, true);
		bceClear(bcePopBank, bceErrBank, true);
		bceClear(bcePopBranch, bceErrBranch, true);
		bceClear(bcePopBaseNo, bceErrBaseNo, true);
		bceClear(bcePopTc, bceErrTc, true);
		bceClear(bcePopAccountNo, bceErrAccountNo, true);
		bceClear(bcePopDate, bceErrDate, false);
		bceClear(bcePopAmount, bceErrAmount, true);
	}

	// ══════════════════════════════════════════════════════════════════
	// STATS
	// ══════════════════════════════════════════════════════════════════

	private void refreshStats() {
		int totalBatches = batches.size();
		int totalCheques = batches.stream().mapToInt(BatchEntity::getTotalCheques).sum();
		long pendingCount;
		try {
			pendingCount = chequeService.countPending();
		} catch (Exception ex) {
			pendingCount = totalCheques;
		}
		setLabel(lblStatBatches, String.valueOf(totalBatches));
		setLabel(lblStatCheques, String.valueOf(totalCheques));
		setLabel(lblStatPending, String.valueOf(pendingCount));
	}

	private void updateBatchCountLabel() {
		Clients.evalJavaScript("bce_updateBatchLabel(" + batches.size() + ");");
	}

	// ══════════════════════════════════════════════════════════════════
	// HELPERS
	// ══════════════════════════════════════════════════════════════════

	private List<ChequeEntity> loadChequesForBatch(String batchId) {
		try {
			return chequeService.getChequesForBatch(batchId);
		} catch (Exception ex) {
			Clients.showNotification("⚠ Could not load cheques: " + ex.getMessage(), "warning", null, "middle_center",
					3000);
			return new ArrayList<>();
		}
	}

	private void discardPendingBatch() {
		if (pendingBatchId == null)
			return;
		String toDelete = pendingBatchId;
		pendingBatchId = null;
		try {
			batchService.discardBatch(toDelete);
			batches.removeIf(b -> b.getBatchId().equals(toDelete));
			renderBatches();
			refreshStats();
			updateBatchCountLabel();
			LOG.info("Discarded empty batch: " + toDelete);
		} catch (Exception ex) {
			LOG.warning("discardPendingBatch: " + ex.getMessage());
		}
	}

	private String convertDateForDisplay(String d) {
		if (d == null || d.isBlank())
			return "";
		if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
			String[] p = d.split("-");
			return p[2] + "/" + p[1] + "/" + p[0];
		}
		return d;
	}

	private Listcell cell(String value) {
		return new Listcell(value != null ? value : "—");
	}

	private Listcell amtCell(String value) {
		Listcell lc = new Listcell();
		Label lbl = new Label(value != null ? value : "₹0.00");
		lbl.setStyle("font-family:'Roboto Mono',monospace;font-weight:600;color:#1a2f52;");
		lc.appendChild(lbl);
		return lc;
	}

	private Listheader lhdr(String label, String width) {
		Listheader lh = new Listheader(label);
		lh.setWidth(width);
		lh.setSclass("bce-lh");
		return lh;
	}

	private void setLabel(Label lbl, String value) {
		if (lbl != null)
			lbl.setValue(nullSafe(value, "—"));
	}

	private void setTb(Textbox tb, String value) {
		if (tb != null)
			tb.setValue(value != null ? value : "");
	}

	private String tbVal(Textbox tb) {
		return (tb != null && tb.getValue() != null) ? tb.getValue().trim() : "";
	}

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

	private String batchStatusChip(String status) {
		if (status == null)
			return "chip bs-default";
		return switch (status) {
		case "Balanced" -> "chip bs-balanced";
		case "Submitted", "Submitted for Verification" -> "chip bs-submitted";
		case "CXF_Generated", "CXF Generated" -> "chip bs-cxf";
		case "Pending Amount Entry", "Created", "Processing" -> "chip bs-pending";
		default -> "chip bs-default";
		};
	}

	private String chequeStatusChip(String status) {
		if (status == null)
			return "chip ch-slate";
		return switch (status) {
		case "Ready" -> "chip ch-ready";
		case "MICR_Repair" -> "chip ch-amber";
		case "Verified" -> "chip ch-green";
		case "Rejected" -> "chip ch-red";
		default -> "chip ch-slate";
		};
	}

	private String sessionStr(String key, String def) {
		Object v = Sessions.getCurrent().getAttribute(key);
		return v != null ? v.toString() : def;
	}

	private String nullSafe(String v, String fallback) {
		return (v != null && !v.isBlank()) ? v : fallback;
	}

	private byte[] readAllBytes(InputStream is) throws Exception {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] block = new byte[8192];
		int read;
		while ((read = is.read(block)) != -1)
			buf.write(block, 0, read);
		return buf.toByteArray();
	}
}