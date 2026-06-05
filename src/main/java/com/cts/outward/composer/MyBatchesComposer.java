/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : MyBatchesComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for the "My Batches" list
 *                screen. Loads all batches from BatchDAO, binds
 *                them to a ZK Listbox, and provides row-level
 *                actions (open detail, discard). Refreshes the
 *                list after any mutation to keep the UI in sync.
 * ============================================================
 */

package com.cts.outward.composer;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.exception.BatchSubmitException;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;

public class MyBatchesComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(MyBatchesComposer.class.getName());
	private static final String SESS_LOGGED_USER = "loggedUser";
	private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	// ── Service layer — NO direct DAO calls in composer ───────────────
	private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());

	@Wire("#lblBatchCount")
	private Label lblBatchCount;
	@Wire("#txtSearch")
	private Textbox txtSearch;
	@Wire("#cmbStatus")
	private Combobox cmbStatus;
	@Wire("#lbBatches")
	private Listbox lbBatches;
	@Wire
	private Intbox txtChequeCount;
	@Wire
	private Decimalbox txtExpectedAmount;
	@Wire
	private Button btnCloseScanModal;
	@Wire
	private Button btnScanCancelDiscard;

	private List<BatchEntity> allBatches;
	private String pendingBatchId = null;

	// ════════════════════════════════════════════════════════════
	// LIFECYCLE
	// ════════════════════════════════════════════════════════════

	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);
		guardSession();
		loadAndRender();
	}

	// ════════════════════════════════════════════════════════════
	// EVENTS
	// ════════════════════════════════════════════════════════════

	@Listen("onClick = #btnCreateBatch")
	public void onCreateBatch() {
		Sessions.getCurrent().setAttribute("autoOpenBatchModal", true);
		com.cts.composer.DashboardComposer.getInstance().loadPage("/zul/outward/scanModule.zul");
	}

	@Listen("onChanging = #txtSearch")
	public void onSearch() {
		renderFiltered();
	}

	@Listen("onSelect = #cmbStatus")
	public void onStatusFilter() {
		renderFiltered();
	}

	@Listen("onClick = #btnSaveBatchModal")
	public void onSaveBatchModal() {
		int count = (txtChequeCount != null) ? txtChequeCount.getValue() : 0;
		BigDecimal expected = (txtExpectedAmount != null) ? txtExpectedAmount.getValue() : BigDecimal.ZERO;

		if (count <= 0) {
			Clients.showNotification("Total Cheques must be greater than 0.", "warning", null, "middle_center", 2500);
			return;
		}
		if (expected == null || expected.compareTo(BigDecimal.ZERO) <= 0) {
			Clients.showNotification("Control Amount must be greater than 0.", "warning", null, "middle_center", 2500);
			return;
		}

		Clients.evalJavaScript("bce_closeBatchModal();");

		String branch = Sessions.getCurrent().getAttribute("userBranch") != null
				? Sessions.getCurrent().getAttribute("userBranch").toString()
				: "BLR01";
		String createdBy = Sessions.getCurrent().getAttribute("userName") != null
				? Sessions.getCurrent().getAttribute("userName").toString()
				: "SYSTEM";

		try {
			// FIX: delegate to service — no inline batch construction or DAO calls
			BatchEntity batch = batchService.createBatch(branch, count, expected, createdBy);
			pendingBatchId = batch.getBatchId();
		} catch (Exception ex) {
			Clients.showNotification("❌ Could not create batch: " + ex.getMessage(), "error", null, "middle_center",
					5000);
			return;
		}

		loadAndRender();
		Clients.evalJavaScript("bce_openScanModal('" + pendingBatchId + "');");
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

	@Listen("onUpload = #btnScanUploadZip")
	public void onScanZipUpload(org.zkoss.zk.ui.event.UploadEvent event) {
		if (pendingBatchId != null)
			Sessions.getCurrent().setAttribute("selectedBatchId", pendingBatchId);
		com.cts.composer.DashboardComposer.getInstance().loadPage("/zul/outward/scanModule.zul");
	}

	@Listen("onClick = #btnMismatchAccept")
	public void onMismatchAccept() {
		/* handled by scanModule */
	}

	@Listen("onClick = #btnMismatchDiscard")
	public void onMismatchDiscard() {
		discardPendingBatch();
		Clients.evalJavaScript("bce_closeMismatchDialog();");
		loadAndRender();
	}

	// ════════════════════════════════════════════════════════════
	// LOAD & RENDER
	// ════════════════════════════════════════════════════════════

	private void loadAndRender() {
		try {
			// FIX: service call, not DAO
			allBatches = batchService.getAllBatches();
			allBatches.sort(Comparator.comparing(b -> b.getCreatedAt() != null ? b.getCreatedAt().toString() : "",
					Comparator.reverseOrder()));
		} catch (Exception ex) {
			LOG.severe("loadAllBatches error: " + ex.getMessage());
			allBatches = new java.util.ArrayList<>();
		}
		renderBatches(allBatches);
	}

	private void renderFiltered() {
		if (allBatches == null)
			return;
		String q = txtSearch != null && txtSearch.getValue() != null ? txtSearch.getValue().trim().toLowerCase() : "";
		String status = cmbStatus != null && cmbStatus.getValue() != null ? cmbStatus.getValue().trim() : "All Status";

		List<BatchEntity> filtered = allBatches.stream().filter(b -> {
			boolean matchQ = q.isEmpty() || (b.getBatchId() != null && b.getBatchId().toLowerCase().contains(q))
					|| (b.getBranchCode() != null && b.getBranchCode().toLowerCase().contains(q));
			boolean matchS = "All Status".equals(status)
					|| (b.getStatus() != null && b.getStatus().equalsIgnoreCase(status));
			return matchQ && matchS;
		}).collect(java.util.stream.Collectors.toList());

		renderBatches(filtered);
	}

	private void renderBatches(List<BatchEntity> list) {
		if (lbBatches == null)
			return;
		lbBatches.getItems().clear();

		if (lblBatchCount != null)
			lblBatchCount.setValue(list.size() + " batch" + (list.size() != 1 ? "es" : ""));

		for (BatchEntity b : list) {
			Listitem row = new Listitem();
			row.setSclass("mb-row");

			// Batch ID
			Listcell idCell = new Listcell();
			Label idLbl = new Label(b.getBatchId() != null ? b.getBatchId() : "—");
			idLbl.setSclass("mb-link");
			idCell.appendChild(idLbl);
			row.appendChild(idCell);

			// Cheque count
			row.appendChild(cell(String.valueOf(b.getTotalCheques())));

			// Total amount
			row.appendChild(amtCell(fmtAmt(b.getTotalAmount())));

			// Created date
			String dt = b.getCreatedAt() != null ? b.getCreatedAt().format(DTF) : "—";
			row.appendChild(cell(dt));

			// Status chip
			Listcell stCell = new Listcell();
			Label stLbl = new Label(b.getStatus() != null ? b.getStatus() : "—");
			stLbl.setSclass(statusChip(b.getStatus()));
			stCell.appendChild(stLbl);
			row.appendChild(stCell);

			// View & Edit
			Listcell actCell = new Listcell();
			Label editLbl = new Label("View & Edit");
			editLbl.setSclass("mb-action-link");
			editLbl.addEventListener("onClick", e -> openBatch(b.getBatchId()));
			actCell.appendChild(editLbl);
			row.appendChild(actCell);

			// Submit to Checker
			Listcell subCell = new Listcell();
			boolean submitted = "Submitted".equalsIgnoreCase(b.getStatus())
					|| "Ready_for_Verification".equalsIgnoreCase(b.getStatus());

			if (submitted) {
				Label doneLbl = new Label("✓ Submitted");
				doneLbl.setSclass("chip ch-submitted");
				subCell.appendChild(doneLbl);
			} else {
				Button subBtn = new Button("Submit");
				subBtn.setSclass("btn btn-primary btn-sm");
				final String bId = b.getBatchId();
				subBtn.addEventListener("onClick", e -> submitBatch(bId));
				subCell.appendChild(subBtn);
			}
			row.appendChild(subCell);

			// Row click → batch detail
			row.addEventListener("onClick", e -> openBatch(b.getBatchId()));

			lbBatches.appendChild(row);
		}
	}

	// ════════════════════════════════════════════════════════════
	// ACTIONS
	// ════════════════════════════════════════════════════════════

	private void openBatch(String batchId) {
		Sessions.getCurrent().setAttribute("selectedBatchId", batchId);
		com.cts.composer.DashboardComposer.getInstance().loadPage("/zul/outward/batch-detail.zul");
	}

	private void submitBatch(String batchId) {
		try {
			// FIX: all validation + status update delegated to service
			batchService.submitBatch(batchId);
			Clients.showNotification("✅ Batch " + batchId + " sent to Verification I.", "info", null, "middle_center",
					2500);
		} catch (BatchSubmitException ex) {
			Clients.showNotification("❌ Cannot submit — " + ex.getMessage(), "error", null, "middle_center", 3500);
		} catch (Exception ex) {
			LOG.warning("submitBatch error: " + ex.getMessage());
			Clients.showNotification("❌ Submit failed: " + ex.getMessage(), "error", null, "middle_center", 4000);
		}
		loadAndRender();
	}

	private void discardPendingBatch() {
		if (pendingBatchId == null)
			return;
		String toDelete = pendingBatchId;
		pendingBatchId = null;
		try {
			// FIX: service call, not DAO
			batchService.discardBatch(toDelete);
			loadAndRender();
		} catch (Exception ex) {
			LOG.warning("discardPendingBatch: " + ex.getMessage());
		}
	}

	// ════════════════════════════════════════════════════════════
	// HELPERS
	// ════════════════════════════════════════════════════════════

	private void guardSession() {
		Object u = Sessions.getCurrent().getAttribute(SESS_LOGGED_USER);
		if (u == null || u.toString().trim().isEmpty())
			Executions.sendRedirect("/zul/index.zul");
	}

	private Listcell cell(String text) {
		return new Listcell(text != null ? text : "—");
	}

	private Listcell amtCell(String text) {
		Listcell c = new Listcell(text);
		c.setSclass("amt-cell");
		return c;
	}

	private String fmtAmt(BigDecimal amt) {
		if (amt == null)
			return "₹0.00";
		return "₹" + String.format("%,.2f", amt);
	}

	private String statusChip(String s) {
		if (s == null)
			return "chip ch-pending";
		return switch (s.toLowerCase()) {
		case "submitted" -> "chip ch-submitted";
		case "ready_for_verification" -> "chip ch-approved";
		case "approved" -> "chip ch-approved";
		case "rejected" -> "chip ch-rejected";
		case "pending" -> "chip ch-pending";
		default -> "chip ch-pending";
		};
	}
}