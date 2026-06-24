/*
 * Project     : Navbharat CTS Outward
 * File        : VerificationOneComposer.java
 * Package     : com.cts.outward.composer
 * Author      : Anusha M.
 * Created     : June 2026
 * Description : ZK SelectorComposer for the Verification I (Checker) screen.
 *               Manages two-phase UI: batch list (Phase 1) → cheque list (Phase 2)
 *               → cheque verification popup. All business logic is delegated to
 *               VerificationOneService; this class handles only UI state and events.
 */
package com.cts.outward.composer;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Image;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.CBSDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.model.BatchSummary;
import com.cts.outward.model.CbsAccountDetails;
import com.cts.outward.service.CBSServiceImpl;
import com.cts.outward.service.VerificationOneService;
import com.cts.outward.service.VerificationOneServiceImpl;

public class VerificationOneComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final String CHEQUE_IMAGE_SERVLET = "/chequeImage";
    private static final String DEFAULT_USER = "SYSTEM";
    private static final Logger LOG = Logger.getLogger(VerificationOneComposer.class.getName());

    private static final int BATCH_PAGE_SIZE = 5;
    private static final int CHEQUE_PAGE_SIZE = 5;

    /**
     * Single service dependency — handles all business logic for Verification I.
     */
    private final VerificationOneService verificationService = new VerificationOneServiceImpl(
            new BatchDAOImpl(),
            new ChequeDAOImpl(),
            new CBSServiceImpl(new CBSDAOImpl()));

    // ── Phase 1: Batch list panel ─────────────────────────────────────────
    @Wire
    private Div pnlBatchList;
    @Wire
    private Listbox lbBatches;
    @Wire
    private Label lblBatchPagingInfo;
    @Wire
    private Label lblBatchPageNum;
    @Wire
    private Button btnBatchPrev;
    @Wire
    private Button btnBatchNext;

    // ── Phase 1: Batch filter controls ───────────────────────────────────
    @Wire
    private Textbox txBatchSearch;
    @Wire
    private Datebox dtBatchFromDate;
    @Wire
    private Datebox dtBatchToDate;
    @Wire
    private Combobox cmbBatchStatusFilter;
    @Wire
    private Button btnBatchDateClear;

    // ── Phase 2: Cheque list panel ────────────────────────────────────────
    @Wire
    private Div pnlChequeList;
    @Wire
    private Listbox lbCheques;
    @Wire
    private Label spCntPending;
    @Wire
    private Label spCntPassed;
    @Wire
    private Label spCntRejected;
    @Wire
    private Label lblBatchTitle;
    @Wire
    private Label lblChequePagingInfo;
    @Wire
    private Label lblChequePageNum;
    @Wire
    private Button btnChequePrev;
    @Wire
    private Button btnChequeNext;

    // ── Phase 2: Cheque filter controls ──────────────────────────────────
    @Wire
    private Textbox txChequeSearch;
    @Wire
    private Datebox dtChequeFromDate;
    @Wire
    private Datebox dtChequeToDate;
    @Wire
    private Combobox cmbChequeStatusFilter;
    @Wire
    private Button btnChequeDateClear;

    // ── Verification popup ────────────────────────────────────────────────
    @Wire
    private Div dlgChequeVerify;
    @Wire
    private Div dlgBackdrop;
    @Wire
    private Label dlgBatchPill;
    @Wire
    private Label dlgRecordPos;
    @Wire
    private Div dlgImageBox;
    @Wire
    private Image dlgImage;
    @Wire
    private Label dlgImagePh;

    @Wire
    private Label dlgChequeNo;
    @Wire
    private Label dlgCityCode;
    @Wire
    private Label dlgBankCode;
    @Wire
    private Label dlgBranchCode;
    @Wire
    private Label dlgTcCode;

    @Wire
    private Label dlgPayeeName;
    @Wire
    private Label dlgCbsAccName;
    @Wire
    private Label dlgCbsPayeeMatch;
    @Wire
    private Label dlgAccountNo;
    @Wire
    private Label dlgChequeDate;
    @Wire
    private Label dlgCbsAccStatus;
    @Wire
    private Label dlgCbsNewAcc;
    @Wire
    private Label dlgAmount;
    @Wire
    private Label dlgAmountWords;

    @Wire
    private Button btnDlgPrev;
    @Wire
    private Button btnDlgNext;
    @Wire
    private Button btnDlgAccept;
    @Wire
    private Combobox cmbRejectReason;
    @Wire
    private Button btnDlgReject;
    @Wire
    private Combobox cmbReferReason;
    @Wire
    private Button btnDlgRefer;
    @Wire
    private Button btnToggleSide;

    // ── Composer state ────────────────────────────────────────────────────
    private String currentUser;
    private String activeBatchId;

    private List<BatchSummary> batchSummaryList = new ArrayList<>();
    private List<ChequeEntity> pendingChequeList = new ArrayList<>();

    // Filtered views used for display (derived from the master lists above)
    private List<BatchSummary> filteredBatchList = new ArrayList<>();
    private List<ChequeEntity> filteredChequeList = new ArrayList<>();

    private int batchCurrentPage = 1;
    private int batchTotalPages = 1;

    private int chequeCurrentPage = 1;
    private int chequeTotalPages = 1;

    private int popupChequeIndex = 0;
    private boolean isRearImageVisible = false;

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        Session session = Sessions.getCurrent();
        com.cts.uam.model.User loggedInUser = com.cts.util.SecurityUtil.getCurrentUser();
        currentUser = (loggedInUser != null) ? loggedInUser.getUsername() : DEFAULT_USER;
        // Disable vflex on listboxes to prevent unwanted scrollable expansion
        lbBatches.setVflex("false");
        lbCheques.setVflex("false");

        resetState();
        dlgChequeVerify.setVisible(false);
        dlgBackdrop.setVisible(false);

        showPhase(1);
        loadBatchList();
        LOG.info("VerificationOneComposer initialized — user=" + currentUser);
    }

    /** Resets all navigation and selection state to initial values. */
    private void resetState() {
        activeBatchId = null;
        pendingChequeList = new ArrayList<>();
        batchSummaryList = new ArrayList<>();
        filteredBatchList = new ArrayList<>();
        filteredChequeList = new ArrayList<>();
        popupChequeIndex = 0;
        batchCurrentPage = 1;
        chequeCurrentPage = 1;
    }

    /**
     * Shows Phase 1 (batch list) or Phase 2 (cheque list) by toggling panel
     * visibility.
     */
    private void showPhase(int phase) {
        pnlBatchList.setVisible(phase == 1);
        pnlChequeList.setVisible(phase == 2);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 1 — BATCH LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fetches verifiable batch summaries from the service and re-renders the batch
     * table.
     */
    private void loadBatchList() {
        batchSummaryList = verificationService.getVerifiableBatchSummaries();
        applyBatchFiltersAndRender();
    }

    /**
     * Applies the active batch search text, date range, and status filter to
     * {@code batchSummaryList}, then re-renders the current page.
     */
    private void applyBatchFiltersAndRender() {
        String searchText = txBatchSearch != null ? txBatchSearch.getValue().trim().toLowerCase() : "";
        String statusValue = getComboSelectedValue(cmbBatchStatusFilter);
        Date fromDate = dtBatchFromDate != null ? dtBatchFromDate.getValue() : null;
        Date toDate = dtBatchToDate != null ? dtBatchToDate.getValue() : null;

        filteredBatchList = batchSummaryList.stream()
                .filter(summary -> {
                    // ── text search: match on Batch ID ──
                    if (!searchText.isEmpty()
                            && !summary.getBatchId().toLowerCase().contains(searchText)) {
                        return false;
                    }
                    // ── status filter ──
                    if (statusValue != null && !"ALL".equals(statusValue)) {
                        BatchStatus selected = BatchStatus.fromDb(statusValue);
                        if (summary.getStatus() != selected)
                            return false;
                    }
                    // ── date range: filter on createdAt string (dd/MM/yyyy or timestamp) ──
                    if (fromDate != null || toDate != null) {
                        Date rowDate = parseSummaryDate(summary.getCreatedAt());
                        if (rowDate != null) {
                            if (fromDate != null && rowDate.before(fromDate))
                                return false;
                            if (toDate != null && rowDate.after(toDate))
                                return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        batchTotalPages = Math.max(1, (int) Math.ceil((double) filteredBatchList.size() / BATCH_PAGE_SIZE));
        batchCurrentPage = 1;
        renderBatchPage();
    }

    /** Renders the current page of batch summaries into the batch listbox. */
    private void renderBatchPage() {
        lbBatches.getItems().clear();

        int totalBatches = filteredBatchList.size();
        int pageStart = (batchCurrentPage - 1) * BATCH_PAGE_SIZE;
        int pageEnd = Math.min(pageStart + BATCH_PAGE_SIZE, totalBatches);

        for (BatchSummary summary : filteredBatchList.subList(pageStart, pageEnd)) {
            Listitem row = new Listitem();
            row.appendChild(cell(summary.getBatchId()));
            row.appendChild(cellCenter(String.valueOf(summary.getTotalCheques())));
            row.appendChild(cellCenter(String.valueOf(summary.getPendingCount())));
            row.appendChild(cellCenter(String.valueOf(summary.getProcessedCount())));
            row.appendChild(cell(summary.getCreatedAt()));

            Listcell statusCell = new Listcell();
            Label statusLabel = new Label(summary.getStatus().getLabel());
            statusLabel.setSclass("batch-pill " + batchStatusPillClass(summary.getStatus()));
            statusCell.appendChild(statusLabel);
            row.appendChild(statusCell);

            Listcell actionCell = new Listcell();
            String buttonLabel = summary.getStatus() == BatchStatus.VERIFICATION_IN_PROGRESS ? "Resume" : "Process";
            Button processButton = new Button(buttonLabel);
            processButton.setSclass("v1-action-process-btn");
            final String batchId = summary.getBatchId();
            processButton.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK, e -> openBatchChequeList(batchId));
            actionCell.appendChild(processButton);
            row.appendChild(actionCell);

            row.setParent(lbBatches);
        }

        if (totalBatches == 0) {
            lblBatchPagingInfo.setValue("Showing 0 \u2013 0 of 0 batches");
        } else {
            lblBatchPagingInfo
                    .setValue("Showing " + (pageStart + 1) + " \u2013 " + pageEnd + " of " + totalBatches + " batches");
        }
        lblBatchPageNum.setValue("Page " + batchCurrentPage + " of " + batchTotalPages);
        btnBatchPrev.setDisabled(batchCurrentPage <= 1);
        btnBatchNext.setDisabled(batchCurrentPage >= batchTotalPages);
    }

    // ── Batch filter event listeners ──────────────────────────────────────

    /** Fires on every keystroke in the batch search box (instant="true" in ZUL). */
    @Listen("onChanging = #txBatchSearch")
    public void onBatchSearchChanging(org.zkoss.zk.ui.event.InputEvent event) {
        applyBatchFiltersAndRender();
    }

    @Listen("onChange = #txBatchSearch")
    public void onBatchSearchChange() {
        applyBatchFiltersAndRender();
    }

    // FIX: Split into two separate methods — ZK does not allow combining
    // multiple event names in a single @Listen annotation.
    @Listen("onChange = #cmbBatchStatusFilter")
    public void onBatchStatusFilterChange() {
        applyBatchFiltersAndRender();
    }

    @Listen("onSelect = #cmbBatchStatusFilter")
    public void onBatchStatusFilterSelect() {
        applyBatchFiltersAndRender();
    }

    @Listen("onChange = #dtBatchFromDate")
    public void onBatchFromDateChange() {
        applyBatchFiltersAndRender();
    }

    @Listen("onChange = #dtBatchToDate")
    public void onBatchToDateChange() {
        applyBatchFiltersAndRender();
    }

    /** Clears all batch filters and reloads the full unfiltered list. */
    @Listen("onClick = #btnBatchDateClear")
    public void onBatchDateClear() {
        txBatchSearch.setValue("");
        dtBatchFromDate.setValue(null);
        dtBatchToDate.setValue(null);
        cmbBatchStatusFilter.setValue("");
        cmbBatchStatusFilter.setSelectedItem(null);
        batchCurrentPage = 1;
        applyBatchFiltersAndRender();
    }

    @Listen("onClick = #btnBatchPrev")
    public void onBatchPrev() {
        if (batchCurrentPage > 1) {
            batchCurrentPage--;
            renderBatchPage();
        }
    }

    @Listen("onClick = #btnBatchNext")
    public void onBatchNext() {
        if (batchCurrentPage < batchTotalPages) {
            batchCurrentPage++;
            renderBatchPage();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — CHEQUE LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Opens the cheque list for the selected batch (Phase 1 → Phase 2 transition).
     * The service transitions the batch to VERIFICATION_IN_PROGRESS on first open.
     * Stays on Phase 1 with a message if no V1-pending cheques remain.
     */
    private void openBatchChequeList(String batchId) {
        List<ChequeEntity> v1PendingCheques = verificationService.openBatchForVerification(batchId);

        if (v1PendingCheques.isEmpty()) {
            Messagebox.show("No V1 pending cheques in batch " + batchId + ".",
                    "Nothing to Verify", Messagebox.OK, Messagebox.INFORMATION);
            return;
        }

        activeBatchId = batchId;
        pendingChequeList = v1PendingCheques;
        chequeCurrentPage = 1;

        // Reset cheque filters when opening a new batch
        if (txChequeSearch != null)
            txChequeSearch.setValue("");
        if (dtChequeFromDate != null)
            dtChequeFromDate.setValue(null);
        if (dtChequeToDate != null)
            dtChequeToDate.setValue(null);
        if (cmbChequeStatusFilter != null) {
            cmbChequeStatusFilter.setValue("");
            cmbChequeStatusFilter.setSelectedItem(null);
        }

        lblBatchTitle.setValue(batchId);
        updateStatusCounters();
        applyChequeFitlersAndRender();
        showPhase(2);
    }

    /**
     * Applies the active cheque search text, date range, and status filter to
     * {@code pendingChequeList}, then re-renders the current page.
     */
    private void applyChequeFitlersAndRender() {
        String searchText = txChequeSearch != null ? txChequeSearch.getValue().trim().toLowerCase() : "";
        String statusValue = getComboSelectedValue(cmbChequeStatusFilter);
        Date fromDate = dtChequeFromDate != null ? dtChequeFromDate.getValue() : null;
        Date toDate = dtChequeToDate != null ? dtChequeToDate.getValue() : null;

        filteredChequeList = pendingChequeList.stream()
                .filter(cheque -> {
                    // ── text search: cheque no., payee name, amount ──
                    if (!searchText.isEmpty()) {
                        boolean matchesChequeNo = cheque.getChequeNo() != null
                                && cheque.getChequeNo().toLowerCase().contains(searchText);
                        boolean matchesPayee = cheque.getPayeeName() != null
                                && cheque.getPayeeName().toLowerCase().contains(searchText);
                        boolean matchesAmount = cheque.getAmount() != null
                                && cheque.getAmount().toPlainString().contains(searchText);
                        if (!matchesChequeNo && !matchesPayee && !matchesAmount)
                            return false;
                    }
                    // ── status filter ──
                    if (statusValue != null && !"ALL".equals(statusValue)) {
                        ChequeStatus selected = ChequeStatus.valueOf(statusValue);
                        if (!selected.db().equals(cheque.getStatus()))
                            return false;
                    }
                    // ── date range: filter on cheque date string ──
                    if (fromDate != null || toDate != null) {
                        Date chequeDate = parseChequeDate(cheque.getChequeDate());
                        if (chequeDate != null) {
                            if (fromDate != null && chequeDate.before(fromDate))
                                return false;
                            if (toDate != null && chequeDate.after(toDate))
                                return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        chequeTotalPages = Math.max(1, (int) Math.ceil((double) filteredChequeList.size() / CHEQUE_PAGE_SIZE));
        chequeCurrentPage = 1;
        renderChequePage();
    }

    /** Renders the current page of cheques into the cheque listbox. */
    private void renderChequePage() {
        lbCheques.getItems().clear();

        int totalCheques = filteredChequeList.size();
        int pageStart = (chequeCurrentPage - 1) * CHEQUE_PAGE_SIZE;
        int pageEnd = Math.min(pageStart + CHEQUE_PAGE_SIZE, totalCheques);

        for (int i = 0; i < pageEnd - pageStart; i++) {
            ChequeEntity cheque = filteredChequeList.get(pageStart + i);
            // absoluteIdx refers to position in filteredChequeList for popup navigation
            final int absoluteIdx = pageStart + i;

            Listitem row = new Listitem();
            row.appendChild(cellCenter(String.valueOf(absoluteIdx + 1)));
            row.appendChild(cellMono(blankToEmDash(cheque.getChequeNo())));
            row.appendChild(cell(blankToEmDash(cheque.getPayeeName())));
            row.appendChild(cellAmt(formatAmount(cheque.getAmount())));
            row.appendChild(cell(blankToEmDash(cheque.getChequeDate())));

            ChequeStatus chequeStatus = ChequeStatus.fromDb(cheque.getStatus());
            Listcell verificationStatusCell = new Listcell();
            Label verificationStatusLabel = new Label(chequeStatus.getLabel());
            verificationStatusLabel.setSclass("v1-row-status-badge " + chequeBadgeClass(chequeStatus));
            verificationStatusCell.appendChild(verificationStatusLabel);
            row.appendChild(verificationStatusCell);

            Listcell actionCell = new Listcell();
            Button openButton = new Button("Open");
            openButton.setSclass("v1-action-open-btn");
            openButton.setDisabled(chequeStatus != ChequeStatus.V1_PENDING);
            openButton.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK, e -> openChequePopup(absoluteIdx));
            actionCell.appendChild(openButton);
            row.appendChild(actionCell);

            row.setParent(lbCheques);
        }

        if (totalCheques == 0) {
            lblChequePagingInfo.setValue("Showing 0 \u2013 0 of 0 cheques");
        } else {
            lblChequePagingInfo
                    .setValue("Showing " + (pageStart + 1) + " \u2013 " + pageEnd + " of " + totalCheques + " cheques");
        }
        lblChequePageNum.setValue("Page " + chequeCurrentPage + " of " + chequeTotalPages);
        btnChequePrev.setDisabled(chequeCurrentPage <= 1);
        btnChequeNext.setDisabled(chequeCurrentPage >= chequeTotalPages);
    }

    // ── Cheque filter event listeners ─────────────────────────────────────

    /**
     * Fires on every keystroke in the cheque search box (instant="true" in ZUL).
     */
    @Listen("onChanging = #txChequeSearch")
    public void onChequeSearchChanging(org.zkoss.zk.ui.event.InputEvent event) {
        applyChequeFitlersAndRender();
    }

    @Listen("onChange = #txChequeSearch")
    public void onChequeSearchChange() {
        applyChequeFitlersAndRender();
    }

    // FIX: Split into two separate methods — ZK does not allow combining
    // multiple event names in a single @Listen annotation.
    @Listen("onChange = #cmbChequeStatusFilter")
    public void onChequeStatusFilterChange() {
        applyChequeFitlersAndRender();
    }

    @Listen("onSelect = #cmbChequeStatusFilter")
    public void onChequeStatusFilterSelect() {
        applyChequeFitlersAndRender();
    }

    @Listen("onChange = #dtChequeFromDate")
    public void onChequeFromDateChange() {
        applyChequeFitlersAndRender();
    }

    @Listen("onChange = #dtChequeToDate")
    public void onChequeToDateChange() {
        applyChequeFitlersAndRender();
    }

    /** Clears all cheque filters and re-renders the full unfiltered cheque list. */
    @Listen("onClick = #btnChequeDateClear")
    public void onChequeDateClear() {
        txChequeSearch.setValue("");
        dtChequeFromDate.setValue(null);
        dtChequeToDate.setValue(null);
        cmbChequeStatusFilter.setValue("");
        cmbChequeStatusFilter.setSelectedItem(null);
        chequeCurrentPage = 1;
        applyChequeFitlersAndRender();
    }

    @Listen("onClick = #btnChequePrev")
    public void onChequePrev() {
        if (chequeCurrentPage > 1) {
            chequeCurrentPage--;
            renderChequePage();
        }
    }

    @Listen("onClick = #btnChequeNext")
    public void onChequeNext() {
        if (chequeCurrentPage < chequeTotalPages) {
            chequeCurrentPage++;
            renderChequePage();
        }
    }

    /**
     * Updates the Pending / Accepted / Rejected counter chips above the cheque
     * list.
     */
    private void updateStatusCounters() {
        long pendingCount = countChequesByStatus(ChequeStatus.V1_PENDING);
        long acceptedCount = countChequesByStatus(ChequeStatus.VERIFIED);
        long rejectedCount = countChequesByStatus(ChequeStatus.REJECTED);
        spCntPending.setValue(pendingCount + " Pending");
        spCntPassed.setValue(acceptedCount + " Accepted");
        spCntRejected.setValue(rejectedCount + " Rejected");
    }

    private long countChequesByStatus(ChequeStatus status) {
        return pendingChequeList.stream()
                .filter(cheque -> status.db().equals(cheque.getStatus()))
                .count();
    }

    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        closePopup();
        activeBatchId = null;
        pendingChequeList = new ArrayList<>();
        filteredChequeList = new ArrayList<>();
        chequeCurrentPage = 1;
        batchCurrentPage = 1;
        showPhase(1);
        loadBatchList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // VERIFICATION POPUP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Opens the cheque verification popup for the cheque at the given index
     * inside {@code filteredChequeList}.
     */
    private void openChequePopup(int index) {
        if (filteredChequeList == null || index < 0 || index >= filteredChequeList.size())
            return;
        popupChequeIndex = index;
        isRearImageVisible = false;
        renderPopup();
        dlgBackdrop.setVisible(true);
        dlgChequeVerify.setVisible(true);
    }

    private void closePopup() {
        dlgChequeVerify.setVisible(false);
        dlgBackdrop.setVisible(false);
    }

    @Listen("onClick = #btnCloseDlg")
    public void onCloseDlg() {
        closePopup();
    }

    /**
     * Populates all popup fields with MICR data, cheque information, and live CBS
     * account details.
     */
    private void renderPopup() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null)
            return;

        dlgBatchPill.setValue("Batch: " + activeBatchId);
        dlgRecordPos.setValue((popupChequeIndex + 1) + " / " + filteredChequeList.size());

        dlgChequeNo.setValue(blankToEmDash(cheque.getChequeNo()));

        // Extract 9-digit sort code and split into city (0–3) / bank (3–6) / branch
        // (6–9) segments
        String sortCode = cheque.getSortCode() != null ? cheque.getSortCode().replaceAll("[^0-9]", "") : "";
        dlgCityCode.setValue(sortCodeSegment(sortCode, 0, 3));
        dlgBankCode.setValue(sortCodeSegment(sortCode, 3, 6));
        dlgBranchCode.setValue(sortCodeSegment(sortCode, 6, 9));
        dlgTcCode.setValue(blankToEmDash(cheque.getTransactionCode()));

        dlgPayeeName.setValue(blankToEmDash(cheque.getPayeeName()));
        dlgAccountNo.setValue(blankToEmDash(cheque.getPayeeAccountNo()));
        dlgChequeDate.setValue(blankToEmDash(cheque.getChequeDate()));
        dlgAmount.setValue(formatAmount(cheque.getAmount()));
        dlgAmountWords.setValue(blankToEmDash(cheque.getAmountInWords()));

        // Fetch live CBS account data; DTO is always non-null (has safe fallback
        // states)
        CbsAccountDetails cbsDetails = verificationService.getCbsAccountDetails(
                cheque.getPayeeAccountNo(), cheque.getPayeeName());

        dlgCbsAccName.setValue(cbsDetails.getAccountHolderName());
        dlgCbsAccStatus.setValue(cbsDetails.getAccountStatus());
        dlgCbsAccStatus.setSclass(cbsDetails.getAccountStatusSclass());
        dlgCbsNewAcc.setValue(cbsDetails.getIsNewAccount());
        dlgCbsNewAcc.setSclass(cbsDetails.getIsNewAccountSclass());
        dlgCbsPayeeMatch.setValue(cbsDetails.getPayeeMatchLabel());
        dlgCbsPayeeMatch.setSclass(cbsDetails.getPayeeMatchSclass());

        cmbRejectReason.setValue("");
        cmbRejectReason.setSelectedItem(null);
        cmbReferReason.setValue("");
        cmbReferReason.setSelectedItem(null);
        isRearImageVisible = false;
        loadChequeImage(cheque, "front");
        btnToggleSide.setLabel("\u21c4 Show BACK");
    }

    private void loadChequeImage(ChequeEntity cheque, String side) {
        if (cheque.getId() == null) {
            dlgImagePh.setValue("No image");
            dlgImagePh.setVisible(true);
            dlgImage.setVisible(false);
            return;
        }
        dlgImage.setSrc(
                CHEQUE_IMAGE_SERVLET + "?id=" + cheque.getId() + "&side=" + side + "&t=" + System.currentTimeMillis());
        dlgImage.setVisible(true);
        dlgImagePh.setVisible(false);
    }

    @Listen("onClick = #btnToggleSide")
    public void onToggleSide() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null)
            return;
        isRearImageVisible = !isRearImageVisible;
        loadChequeImage(cheque, isRearImageVisible ? "rear" : "front");
        btnToggleSide.setLabel(isRearImageVisible ? "\u21c4 Show FRONT" : "\u21c4 Show BACK");
    }

    @Listen("onClick = #btnDlgPrev")
    public void onDlgPrev() {
        if (popupChequeIndex > 0) {
            popupChequeIndex--;
            isRearImageVisible = false;
            renderPopup();
        }
    }

    @Listen("onClick = #btnDlgNext")
    public void onDlgNext() {
        if (popupChequeIndex < filteredChequeList.size() - 1) {
            popupChequeIndex++;
            isRearImageVisible = false;
            renderPopup();
        }
    }

    /**
     * Validates the CBS account (active + found) and marks the cheque as VERIFIED
     * if it passes.
     */
    @Listen("onClick = #btnDlgAccept")
    public void onDlgAccept() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null)
            return;

        String validationError = verificationService.validateAndAcceptCheque(
                cheque.getId(), cheque.getPayeeAccountNo(), currentUser);

        if (validationError != null) {
            Messagebox.show(validationError, "CBS Validation Failed",
                    Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }
        applyActionAndAdvance(ChequeStatus.VERIFIED.db());
    }

    /** Marks the cheque as REJECTED with the selected rejection reason. */
    @Listen("onClick = #btnDlgReject")
    public void onDlgReject() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null)
            return;
        String rejectionReason = getComboSelectedValue(cmbRejectReason);
        if (rejectionReason == null) {
            Messagebox.show("Select a rejection reason.", "Validation", Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }
        verificationService.rejectCheque(cheque.getId(), currentUser, rejectionReason);
        applyActionAndAdvance(ChequeStatus.REJECTED.db());
    }

    /**
     * Escalates the cheque to Verification II (V2_PENDING) with the selected refer
     * reason.
     */
    @Listen("onClick = #btnDlgRefer")
    public void onDlgRefer() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null)
            return;
        String referReason = getComboSelectedValue(cmbReferReason);
        if (referReason == null) {
            Messagebox.show("Select a refer reason.", "Validation", Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }
        verificationService.referCheque(cheque.getId(), currentUser, referReason);
        applyActionAndAdvance(ChequeStatus.V2_PENDING.db());
    }

    /**
     * Updates the in-memory cheque status after an accept/reject/refer action,
     * refreshes
     * the counters and cheque list, closes the popup, then auto-advances to the
     * next
     * V1-pending cheque.
     */
    private void applyActionAndAdvance(String newStatus) {
        ChequeEntity cheque = getPopupCheque();
        if (cheque != null) {
            cheque.setStatus(newStatus);
            cheque.setVerStatus(newStatus);
        }
        verificationService.checkAndFinalizeBatch(activeBatchId);
        updateStatusCounters();
        // Re-apply filters so the updated status is reflected in the filtered view
        applyChequeFitlersAndRender();
        closePopup();
        advanceToNextPendingOrShowCompletion();
    }

    /**
     * Opens the next V1-pending cheque in the popup, or shows a batch-complete
     * message if all are actioned.
     */
    private void advanceToNextPendingOrShowCompletion() {
        for (int i = 0; i < pendingChequeList.size(); i++) {
            if (ChequeStatus.V1_PENDING.db().equals(pendingChequeList.get(i).getStatus())) {
                // Find its position in filteredChequeList and open from there
                ChequeEntity next = pendingChequeList.get(i);
                for (int j = 0; j < filteredChequeList.size(); j++) {
                    if (filteredChequeList.get(j) == next) {
                        openChequePopup(j);
                        return;
                    }
                }
                // Not visible in current filter — open without filter restriction
                openChequePopupFromMaster(i);
                return;
            }
        }
        Messagebox.show("All cheques in batch " + activeBatchId + " have been actioned.",
                "Batch Complete", Messagebox.OK, Messagebox.INFORMATION);
    }

    /**
     * Opens the popup directly from the master {@code pendingChequeList} when the
     * next
     * pending cheque is hidden by the current filter. Temporarily bypasses the
     * filter
     * by updating {@code filteredChequeList} to include that specific entry.
     */
    private void openChequePopupFromMaster(int masterIndex) {
        filteredChequeList = new ArrayList<>(pendingChequeList);
        chequeTotalPages = Math.max(1, (int) Math.ceil((double) filteredChequeList.size() / CHEQUE_PAGE_SIZE));
        chequeCurrentPage = 1;
        renderChequePage();
        openChequePopup(masterIndex);
    }

    /**
     * Returns the cheque currently shown in the popup, or null if the index is out
     * of range.
     */
    private ChequeEntity getPopupCheque() {
        if (filteredChequeList == null || popupChequeIndex < 0 || popupChequeIndex >= filteredChequeList.size())
            return null;
        return filteredChequeList.get(popupChequeIndex);
    }

    /**
     * Returns the selected value from a combobox, or null if nothing is selected.
     * FIX: Uses toString() instead of cast to safely handle non-String value
     * objects,
     * and falls back to the raw typed text when no item is formally selected.
     */
    private String getComboSelectedValue(Combobox combobox) {
        if (combobox == null)
            return null;
        if (combobox.getSelectedItem() != null) {
            Object val = combobox.getSelectedItem().getValue();
            return val != null ? val.toString() : null;
        }
        // Fallback: user may have typed a value directly
        String raw = combobox.getValue();
        return (raw != null && !raw.isBlank()) ? raw.trim() : null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // DATE PARSE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parses the {@code createdAt} string from a {@link BatchSummary} into a
     * {@link Date}
     * so it can be compared against the date-range filter.
     * Supports ISO timestamp strings produced by {@code LocalDateTime.toString()}.
     */
    private Date parseSummaryDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || "\u2014".equals(dateStr))
            return null;
        try {
            // LocalDateTime.toString() → "2026-06-15T09:30:00" or "2026-06-15"
            String datePart = dateStr.contains("T") ? dateStr.substring(0, 10) : dateStr.trim();
            java.time.LocalDate ld = java.time.LocalDate.parse(datePart);
            return java.util.Date.from(ld.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        } catch (Exception ex) {
            LOG.fine("parseSummaryDate: cannot parse '" + dateStr + "' — " + ex.getMessage());
            return null;
        }
    }

    /**
     * Parses the {@code chequeDate} string from a {@link ChequeEntity} into a
     * {@link Date}.
     * Tries dd/MM/yyyy first (common cheque format), then yyyy-MM-dd as a fallback.
     */
    private Date parseChequeDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || "\u2014".equals(dateStr))
            return null;
        try {
            java.time.format.DateTimeFormatter fmt = dateStr.contains("/")
                    ? java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
            java.time.LocalDate ld = java.time.LocalDate.parse(dateStr.trim(), fmt);
            return java.util.Date.from(ld.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        } catch (Exception ex) {
            LOG.fine("parseChequeDate: cannot parse '" + dateStr + "' — " + ex.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** Returns the CSS class for the batch status pill badge. */
    private String batchStatusPillClass(BatchStatus batchStatus) {
        switch (batchStatus) {
            case VERIFICATION_IN_PROGRESS:
                return "batch-pill-blue";
            case READY_FOR_VERIFICATION:
                return "batch-pill-amber";
            case VERIFIED:
                return "batch-pill-green";
            default:
                return "batch-pill-gray";
        }
    }

    /** Returns the CSS class for the cheque verification status badge. */
    private String chequeBadgeClass(ChequeStatus chequeStatus) {
        switch (chequeStatus) {
            case VERIFIED:
                return "badge-green";
            case REJECTED:
                return "badge-red";
            case PENDING:
                return "badge-gray";
            default:
                return "badge-amber";
        }
    }

    /**
     * Extracts a substring segment from a 9-digit numeric sort code.
     * Returns em-dash if the sort code is missing or shorter than the requested
     * start index.
     */
    private String sortCodeSegment(String sortCode, int fromIndex, int toIndex) {
        if (sortCode == null || sortCode.isEmpty() || sortCode.length() <= fromIndex)
            return "\u2014";
        return sortCode.substring(fromIndex, Math.min(toIndex, sortCode.length()));
    }

    private Listcell cell(String text) {
        return new Listcell(text == null ? "\u2014" : text);
    }

    private Listcell cellCenter(String text) {
        Listcell lc = cell(text);
        lc.setStyle("text-align:center");
        return lc;
    }

    private Listcell cellMono(String text) {
        Listcell lc = cell(text);
        lc.setSclass("mono");
        return lc;
    }

    private Listcell cellAmt(String text) {
        Listcell lc = cell(text);
        lc.setSclass("amt");
        return lc;
    }

    /** Formats a BigDecimal amount as Indian rupees (e.g., "Rs. 1,23,456.78"). */
    private String formatAmount(BigDecimal amount) {
        return amount == null ? "Rs. 0.00"
                : "Rs. " + NumberFormat.getNumberInstance(new Locale("en", "IN")).format(amount);
    }

    /**
     * Returns the value as-is, or an em-dash (\u2014) if the value is null or
     * blank.
     */
    private String blankToEmDash(String value) {
        return (value == null || value.isBlank()) ? "\u2014" : value;
    }
}