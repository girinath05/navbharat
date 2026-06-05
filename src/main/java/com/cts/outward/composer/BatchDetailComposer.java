/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchDetailComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for the batch detail /
 *                cheque review screen. Loads a single batch with
 *                its full cheque list, renders front/back cheque
 *                images, supports HV (high-value) verification,
 *                MICR repair, CXF file generation, and status
 *                transitions. Read-heavy; writes routed through
 *                service layer.
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
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;

import com.cts.composer.DashboardComposer;
import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;
import com.cts.outward.service.ChequeService;
import com.cts.outward.service.ChequeServiceImpl;

/**
 * BatchDetailComposer =================== Cheque processing popup for a single
 * batch. All data access delegated to BatchService / ChequeService — zero
 * direct DAO or Hibernate calls in this composer.
 */
public class BatchDetailComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(BatchDetailComposer.class.getName());
	private static final int PAGE_SIZE = 10;

	private static final String SESS_LOGGED_USER = "loggedUser";
	private static final String SESS_USER_NAME = "userName";
	private static final String SESS_USER_ROLE = "userRole";

	// ── CSS class constants ────────────────────────────────────────────────
	private static final String CSS_INPUT_NORM = "pop-input pop-input-mono";
	private static final String CSS_INPUT_ERR = "pop-input pop-input-mono pop-input-error";
	private static final String CSS_INPUT_PLAIN = "pop-input";
	private static final String CSS_INPUT_PLAIN_ERR = "pop-input pop-input-error";
	private static final String CSS_ERR_HIDDEN = "err-tip-hidden";
	private static final String CSS_ERR_SHOW = "err-tip-show";

	// ── Service layer — NO direct DAO calls in composer ────────────────────
	private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
	private final ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());

	// ── State ──────────────────────────────────────────────────────────────
	private String batchId;
	private List<ChequeEntity> allCheques = new ArrayList<>();
	private List<ChequeEntity> filtered = new ArrayList<>();
	private int currentPage = 1;
	private int totalPages = 1;
	private ChequeEntity selectedCheque;
	private int selectedIndex = -1;

	// ── Wired: page header ─────────────────────────────────────────────────
	@Wire
	private Label lblHdrUser;
	@Wire
	private Label lblHdrRole;
	@Wire
	private Label lblBatchId;
	@Wire
	private Label lblBatchIdBc;
	@Wire
	private Label lblBatchIdTitle;
	@Wire
	private Button btnLogout;
	@Wire
	private Label lblBranchCode;
	@Wire
	private Label lblTotalCheques;
	@Wire
	private Label lblTotalAmount;
	@Wire
	private Label lblVerifiedCheques;
	@Wire
	private Label lblMicrCheques;
	@Wire
	private Label lblPendingCheques;
	@Wire
	private Button btnBack;
	@Wire
	private Button btnSaveBatch;

	// ── Wired: list / filter ───────────────────────────────────────────────
	@Wire
	private Textbox txtSearch;
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

	// ── Wired: popup header ────────────────────────────────────────────────
	@Wire
	private Label popChequeNo;
	@Wire
	private Button btnPopClose;

	// ── Wired: MICR row ────────────────────────────────────────────────────
	@Wire
	private Textbox popCheckNo;
	@Wire
	private Textbox popCity;
	@Wire
	private Textbox popBank;
	@Wire
	private Textbox popBranch;
	@Wire
	private Textbox popBaseNo;
	@Wire
	private Textbox popTc;

	// ── Wired: MICR error labels ───────────────────────────────────────────
	@Wire
	private Label errCheckNo;
	@Wire
	private Label errCity;
	@Wire
	private Label errBank;
	@Wire
	private Label errBranch;
	@Wire
	private Label errBaseNo;
	@Wire
	private Label errTc;

	// ── Wired: face row ────────────────────────────────────────────────────
	@Wire
	private Textbox popAccountNo;
	@Wire
	private Textbox popChequeDate;
	@Wire
	private Textbox popAmount;
	@Wire
	private Textbox popAmountWords;

	// ── Wired: face error labels ───────────────────────────────────────────
	@Wire
	private Label errAccountNo;
	@Wire
	private Label errChequeDate;
	@Wire
	private Label errAmount;

	// ── Wired: IQA flags ──────────────────────────────────────────────────
	@Wire
	private Label popIqa;
	@Wire
	private Label popDuplicate;
	@Wire
	private Label popNoMicr;
	@Wire
	private Label popMicrMismatch;

	// ── Wired: payee account lookup ────────────────────────────────────────
	@Wire
	private Textbox popCreditAccNo;
	@Wire
	private Button btnAcctLookup;
	@Wire
	private Label lblAcctHolderName;
	@Wire
	private Label lblAcctStatus;
	@Wire
	private Label lblAcctSubcategory;

	// ── Wired: popup footer ────────────────────────────────────────────────
	@Wire
	private Button btnPopPrev2;
	@Wire
	private Button btnPopNext2;
	@Wire
	private Label popNavInfo2;
	@Wire
	private Button btnPopSave;

	// ══════════════════════════════════════════════════════════════════════
	// LIFECYCLE
	// ══════════════════════════════════════════════════════════════════════

	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);
		guardSession();
		populateHeader();

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
		loadCheques();
		applyFilter();
		renderPage();
	}

	// ══════════════════════════════════════════════════════════════════════
	// PAGE LISTENERS
	// ══════════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnBack")
	public void onBack() {
		DashboardComposer.getInstance().loadPage("/zul/outward/batchManagement.zul");
	}

	@Listen("onClick = #btnLogout")
	public void onLogout() {
		Sessions.getCurrent().invalidate();
		Executions.sendRedirect("index.zul");
	}

	@Listen("onClick = #btnSaveBatch")
	public void onSaveBatch() {
		loadCheques();

		long pending = allCheques.stream()
				.filter(c -> !"Ready".equalsIgnoreCase(c.getStatus()) && !"MICR_Repair".equalsIgnoreCase(c.getStatus()))
				.count();
		long micrRepair = allCheques.stream().filter(c -> "MICR_Repair".equalsIgnoreCase(c.getStatus())).count();

		if (pending > 0) {
			Clients.showNotification("❌ Cannot submit — " + pending + " cheque(s) still Pending.", "error", null,
					"middle_center", 4000);
			return;
		}
		if (micrRepair > 0) {
			Clients.showNotification("❌ Cannot submit — " + micrRepair + " cheque(s) need MICR Repair.", "error", null,
					"middle_center", 4000);
			return;
		}

		try {
			// ✅ service handles status update — no direct DAO
			batchService.submitBatchForVerification(batchId);
			Clients.showNotification("✅ Batch " + batchId + " submitted for Verification I.", "info", null,
					"middle_center", 3000);
			DashboardComposer.getInstance().loadPage("/zul/outward/batchManagement.zul");
		} catch (Exception ex) {
			Clients.showNotification("❌ Submit failed: " + ex.getMessage(), "error", null, "middle_center", 5000);
		}
	}

	@Listen("onChanging = #txtSearch")
	public void onSearchChanging(InputEvent e) {
		currentPage = 1;
		applyFilter();
		renderPage();
	}

	@Listen("onChange = #txtSearch")
	public void onSearchChange() {
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

	// ══════════════════════════════════════════════════════════════════════
	// POPUP LISTENERS
	// ══════════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnPopClose")
	public void onPopClose() {
		closePopup();
	}

	@Listen("onClick = #btnPopPrev2")
	public void onPopPrev2() {
		if (selectedIndex > 0)
			openChequePopup(filtered.get(selectedIndex - 1), selectedIndex - 1);
	}

	@Listen("onClick = #btnPopNext2")
	public void onPopNext2() {
		if (selectedIndex < filtered.size() - 1)
			openChequePopup(filtered.get(selectedIndex + 1), selectedIndex + 1);
	}

	@Listen("onClick = #btnPopSave")
	public void onPopSave() {
		if (selectedCheque == null)
			return;

		Map<String, String> errors = validateMicr();
		if (!errors.isEmpty()) {
			markFieldsInvalid(errors);
			return;
		}

		clearAllFieldErrors();
		saveSelectedCheque();

		int next = selectedIndex + 1;
		if (next < filtered.size()) {
			openChequePopup(filtered.get(next), next);
		} else {
			closePopup();
			Clients.showNotification("✔ All cheques processed.", "info", null, "middle_center", 2500);
		}
		loadCheques();
		applyFilter();
		renderPage();
	}

	// ══════════════════════════════════════════════════════════════════════
	// PAYEE ACCOUNT LOOKUP
	// ══════════════════════════════════════════════════════════════════════

	@Listen("onClick = #btnAcctLookup")
	public void onAcctLookup() {
		if (selectedCheque == null)
			return;
		String acctNo = txt(popCreditAccNo);
		if (acctNo.isEmpty()) {
			Clients.showNotification("Enter an account number first.", "warning", null, "top_right", 2000);
			return;
		}
		selectedCheque.setAccountNo(acctNo);

		// ✅ service handles account lookup — no Hibernate in composer
		String[] info = chequeService.lookupAccount(acctNo);
		safe(lblAcctHolderName, info[0]);
		safe(lblAcctStatus, info[1]);
		safe(lblAcctSubcategory, info[2]);
		Clients.showNotification("Account saved.", "info", null, "top_right", 1500);
	}

	// ══════════════════════════════════════════════════════════════════════
	// VALIDATION ENGINE — Pure Java, Zero JavaScript
	// ══════════════════════════════════════════════════════════════════════

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

		String baseNo = txt(popBaseNo);
		if (!baseNo.isEmpty() && !baseNo.matches("\\d{6,7}"))
			err.put("popBaseNo", "Base No. must be 6 or 7 numeric digits.");

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

		return err;
	}

	private void markFieldsInvalid(Map<String, String> errors) {
		clearAllFieldErrors();
		for (Map.Entry<String, String> e : errors.entrySet()) {
			switch (e.getKey()) {
			case "popCheckNo" -> markField(popCheckNo, errCheckNo, e.getValue(), true);
			case "popCity" -> markField(popCity, errCity, e.getValue(), true);
			case "popBank" -> markField(popBank, errBank, e.getValue(), true);
			case "popBranch" -> markField(popBranch, errBranch, e.getValue(), true);
			case "popBaseNo" -> markField(popBaseNo, errBaseNo, e.getValue(), true);
			case "popTc" -> markField(popTc, errTc, e.getValue(), true);
			case "popAccountNo" -> markField(popAccountNo, errAccountNo, e.getValue(), true);
			case "popChequeDate" -> markField(popChequeDate, errChequeDate, e.getValue(), false);
			case "popAmount" -> markField(popAmount, errAmount, e.getValue(), true);
			}
		}
	}

	private void markField(Textbox tb, Label errLbl, String msg, boolean mono) {
		if (tb != null)
			tb.setSclass(mono ? CSS_INPUT_ERR : CSS_INPUT_PLAIN_ERR);
		if (errLbl != null) {
			errLbl.setValue(msg);
			errLbl.setSclass(CSS_ERR_SHOW);
		}
	}

	private void clearAllFieldErrors() {
		clearField(popCheckNo, errCheckNo, true);
		clearField(popCity, errCity, true);
		clearField(popBank, errBank, true);
		clearField(popBranch, errBranch, true);
		clearField(popBaseNo, errBaseNo, true);
		clearField(popTc, errTc, true);
		clearField(popAccountNo, errAccountNo, true);
		clearField(popChequeDate, errChequeDate, false);
		clearField(popAmount, errAmount, true);
	}

	private void clearField(Textbox tb, Label errLbl, boolean mono) {
		if (tb != null)
			tb.setSclass(mono ? CSS_INPUT_NORM : CSS_INPUT_PLAIN);
		if (errLbl != null) {
			errLbl.setValue("");
			errLbl.setSclass(CSS_ERR_HIDDEN);
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// POPUP OPEN / CLOSE
	// ══════════════════════════════════════════════════════════════════════

	private void openChequePopup(ChequeEntity c, int idx) {
		selectedCheque = c;
		selectedIndex = idx;

		safe(popChequeNo, "Cheque #" + nvl(c.getChequeNo(), "—"));

		String sc = nvl(c.getSortCode(), "");
		setTxt(popCheckNo, nvl(c.getChequeNo(), ""));
		setTxt(popCity, sc.length() >= 3 ? sc.substring(0, 3) : "");
		setTxt(popBank, sc.length() >= 6 ? sc.substring(3, 6) : "");
		setTxt(popBranch, sc.length() >= 9 ? sc.substring(6, 9) : "");
		setTxt(popBaseNo, "000000");
		setTxt(popTc, nvl(c.getTransactionCode(), ""));

		setTxt(popAccountNo, nvl(c.getAccountNo(), ""));
		setTxt(popChequeDate, convertDateForDisplay(nvl(c.getChequeDate(), "")));
		setTxt(popAmount, c.getAmount() != null ? c.getAmount().toPlainString() : "");
		setTxt(popAmountWords, "");

		safe(popIqa, nvl(c.getIqaStatus(), "—"));
		safe(popDuplicate, c.isDuplicate() ? "Yes" : "No");
		safe(popNoMicr, sc.isBlank() ? "Yes" : "No");
		safe(popMicrMismatch, "NA");

		if (popCreditAccNo != null)
			popCreditAccNo.setValue(nvl(c.getAccountNo(), ""));
		safe(lblAcctHolderName, "—");
		safe(lblAcctStatus, "—");
		safe(lblAcctSubcategory, "—");

		clearAllFieldErrors();

		if (btnPopPrev2 != null)
			btnPopPrev2.setDisabled(idx <= 0);
		if (btnPopNext2 != null)
			btnPopNext2.setDisabled(idx >= filtered.size() - 1);
		if (popNavInfo2 != null)
			popNavInfo2.setValue((idx + 1) + " / " + filtered.size());

		Clients.evalJavaScript("bd_ensurePopupPortal();");
		Clients.evalJavaScript("document.getElementById('chequePopup').style.display='flex';");

		if (c.getId() != null) {
			Clients.evalJavaScript("bce_imagesLoading();");
			Clients.evalJavaScript("bce_renderImages(" + c.getId() + ");");
		}

		renderPage();
	}

	private void closePopup() {
		selectedCheque = null;
		selectedIndex = -1;
		clearAllFieldErrors();
		Clients.evalJavaScript("document.getElementById('chequePopup').style.display='none';");
		renderPage();
	}

	// ══════════════════════════════════════════════════════════════════════
	// SAVE
	// ══════════════════════════════════════════════════════════════════════

	private void saveSelectedCheque() {
		if (selectedCheque == null)
			return;
		try {
			applyPopupEditsToEntity();
			// ✅ service sets status=Ready + persists — no direct DAO
			chequeService.saveChequeFields(selectedCheque);
			Clients.showNotification("✔ Cheque " + nvl(selectedCheque.getChequeNo(), "") + " saved.", "info", null,
					"top_right", 2000);
		} catch (Exception ex) {
			Clients.showNotification("Save failed: " + ex.getMessage(), "error", null, "middle_center", 4000);
		}
	}

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

		String creditAccNo = txt(popCreditAccNo);
		if (!creditAccNo.isEmpty())
			selectedCheque.setAccountNo(creditAccNo);

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

		selectedCheque.setStatus("Ready");
	}

	// ══════════════════════════════════════════════════════════════════════
	// DATA LOAD
	// ══════════════════════════════════════════════════════════════════════

	private void loadBatchSummary() {
		try {
			// ✅ service handles batch lookup — no direct DAO
			BatchEntity batch = batchService.getBatchById(batchId);
			if (batch != null) {
				safe(lblBatchId, batch.getBatchId());
				safe(lblBatchIdBc, batch.getBatchId());
				safe(lblBatchIdTitle, batch.getBatchId());
				safe(lblBranchCode, nvl(batch.getBranchCode(), "—"));
				safe(lblTotalAmount, fmtAmt(batch.getTotalAmount()));
			}
		} catch (Exception ex) {
			LOG.warning("loadBatchSummary: " + ex.getMessage());
			safe(lblBatchId, batchId);
			safe(lblBatchIdBc, batchId);
			safe(lblBatchIdTitle, batchId);
		}
	}

	private void loadCheques() {
		try {
			// ✅ service handles cheque load — no direct DAO
			allCheques = chequeService.getChequesForBatch(batchId);
		} catch (Exception ex) {
			LOG.severe("loadCheques: " + ex.getMessage());
			allCheques = new ArrayList<>();
		}

		safe(lblTotalCheques, String.valueOf(allCheques.size()));
		long ready = allCheques.stream().filter(c -> "Ready".equalsIgnoreCase(c.getStatus())).count();
		long micr = allCheques.stream().filter(c -> "MICR_Repair".equalsIgnoreCase(c.getStatus())).count();
		long pending = allCheques.size() - ready - micr;
		safe(lblVerifiedCheques, String.valueOf(ready));
		safe(lblMicrCheques, String.valueOf(micr));
		safe(lblPendingCheques, String.valueOf(Math.max(0, pending)));
	}

	// ══════════════════════════════════════════════════════════════════════
	// FILTER + RENDER
	// ══════════════════════════════════════════════════════════════════════

	private void applyFilter() {
		String q = txt(txtSearch).toLowerCase();
		String st = cmbStatusFilter != null && cmbStatusFilter.getValue() != null ? cmbStatusFilter.getValue().trim()
				: "All";

		filtered = allCheques.stream().filter(c -> {
			boolean mQ = q.isEmpty() || (c.getChequeNo() != null && c.getChequeNo().toLowerCase().contains(q))
					|| (c.getDrawerName() != null && c.getDrawerName().toLowerCase().contains(q));
			boolean mS = "All".equals(st) || (c.getStatus() != null && c.getStatus().equalsIgnoreCase(st));
			return mQ && mS;
		}).collect(Collectors.toList());

		totalPages = filtered.isEmpty() ? 1 : (int) Math.ceil((double) filtered.size() / PAGE_SIZE);
		if (currentPage > totalPages)
			currentPage = totalPages;

		if (lblFilterCount != null) {
			boolean def = ("All".equals(st) || st.isBlank()) && q.isEmpty();
			lblFilterCount.setValue(def ? "" : filtered.size() + " match" + (filtered.size() != 1 ? "es" : ""));
		}
	}

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
			ec.setClientAttribute("colspan", "7");
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

			row.appendChild(cell(String.valueOf(rowNo++)));

			Listcell noCell = new Listcell();
			Label arrowL = new Label("▶");
			arrowL.setSclass("chq-arrow");
			Label noLbl = new Label(nvl(c.getChequeNo(), "—"));
			noLbl.setSclass("chq-no-link");
			noCell.appendChild(arrowL);
			noCell.appendChild(noLbl);
			row.appendChild(noCell);

			row.appendChild(cell(nvl(c.getDrawerName(), "—")));
			Listcell amtC = new Listcell(fmtAmt(c.getAmount()));
			amtC.setSclass("amt-cell");
			row.appendChild(amtC);
			row.appendChild(cell(nvl(c.getChequeDate(), "—")));

			Listcell iqaC = new Listcell();
			Label iqaL = new Label(nvl(c.getIqaStatus(), "?"));
			iqaL.setSclass("Pass".equalsIgnoreCase(c.getIqaStatus()) ? "chip ch-pass" : "chip ch-fail");
			iqaC.appendChild(iqaL);
			row.appendChild(iqaC);

			Listcell stC = new Listcell();
			Label stL = new Label(nvl(c.getStatus(), "—"));
			stL.setSclass(chequeStatusChip(c.getStatus()));
			stC.appendChild(stL);
			row.appendChild(stC);

			row.addEventListener("onClick", e -> openChequePopup(c, gIdx));
			lbCheques.appendChild(row);
		}
	}

	// ══════════════════════════════════════════════════════════════════════
	// HELPERS
	// ══════════════════════════════════════════════════════════════════════

	private void guardSession() {
		if (Sessions.getCurrent().getAttribute(SESS_LOGGED_USER) == null)
			Executions.sendRedirect("index.zul");
	}

	private void populateHeader() {
		safe(lblHdrUser, sessionStr(SESS_USER_NAME, sessionStr(SESS_LOGGED_USER, "USER")).toUpperCase());
		safe(lblHdrRole, sessionStr(SESS_USER_ROLE, "OPERATOR").toUpperCase());
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

	private String sessionStr(String key, String def) {
		Object v = Sessions.getCurrent().getAttribute(key);
		return (v != null && !v.toString().trim().isEmpty()) ? v.toString().trim() : def;
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

	private String chequeStatusChip(String s) {
		if (s == null)
			return "chip ch-pending";
		return switch (s) {
		case "Ready" -> "chip ch-pass";
		case "MICR_Repair" -> "chip ch-amber";
		case "Exception" -> "chip ch-amber";
		case "Rejected" -> "chip ch-fail";
		default -> "chip ch-pending";
		};
	}
}