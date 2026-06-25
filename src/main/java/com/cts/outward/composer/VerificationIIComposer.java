package com.cts.outward.composer;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.service.VerificationIIService;
import com.cts.outward.service.VerificationIIServiceImpl;
import com.cts.outward.service.CBSService;
import com.cts.outward.service.CBSServiceImpl;
import com.cts.outward.dao.CBSDAOImpl;
import com.cts.util.SecurityUtil;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.*;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VerificationIIComposer — Multi-Select Toggle Filter
 *
 * Filter Logic
 * ────────────
 *  Type group   : HV, RF — each is an independent on/off toggle
 *  Status group : Pending, Passed, Rejected — each is an independent on/off toggle
 *
 *  Rules:
 *   - Second click on an active chip turns it OFF (toggle behavior)
 *   - Nothing active in a group = show all of that dimension
 *   - Within each group: active chips are OR'd (e.g. HV+RF = both)
 *   - Across groups: type AND status (e.g. HV active + Pending active = HV pending only)
 *
 *  Popup navigation respects active filters:
 *  popupFilteredChequeList captures the filtered cheque list at the moment a cheque
 *  is opened. Prev / Next and findNextPendingInList() all navigate within that list.
 *  After Accept / Reject the filtered list is refreshed so status changes are
 *  reflected immediately in navigation.
 */
public class VerificationIIComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;
	private final VerificationIIService verificationService = new VerificationIIServiceImpl();
    private final CBSService cbsService = new CBSServiceImpl(new CBSDAOImpl());

    private static final int BATCH_PAGE_SIZE  = 5;
    private static final int CHEQUE_PAGE_SIZE = 5;

    // ── Wired — threshold strip labels ───────────────────────────────────────

    @Wire protected Label lblTotalBatchCount;
    @Wire protected Label lblTotalHighValueCount;
    @Wire protected Label lblTotalReferredCount;

    // ── Wired — Screen 1: Batch List ─────────────────────────────────────────

    @Wire protected Div    batchListView;
    @Wire protected Rows   batchGridRows;
    @Wire protected Div    batchEmptyState;
    @Wire protected Label  lblBatchRecordCount;

    @Wire protected Textbox  txtBatchSearch;
    @Wire protected Datebox  dteBatchDateFrom;
    @Wire protected Datebox  dteBatchDateTo;
    @Wire protected Combobox cmbBatchStatus;
    @Wire protected Button   btnClearBatchFilters;

    @Wire protected Button btnBatchPagePrev;
    @Wire protected Button btnBatchPageNext;
    @Wire protected Label  lblBatchPageInfo;

    // ── Wired — Screen 2: Cheque List ────────────────────────────────────────

    @Wire protected Div    chequeListView;
    @Wire protected Label  lblActiveBatchId;
    @Wire protected Rows   chequeGridRows;
    @Wire protected Div    chequeEmptyState;

    @Wire protected Button btnChequePagePrev;
    @Wire protected Button btnChequePageNext;
    @Wire protected Label  lblChequePageInfo;
    @Wire protected Label  lblChequeRecordCount;

    // Chip filter labels (clickable) — each is an independent toggle
    @Wire protected Label chipFilterHighValue;   // Type toggle — High Value
    @Wire protected Label chipFilterReferred;    // Type toggle — Referred
    @Wire protected Label chipStatusPending;     // Status toggle — Pending
    @Wire protected Label chipStatusPassed;      // Status toggle — Passed/Accepted
    @Wire protected Label chipStatusRejected;    // Status toggle — Rejected

    @Wire protected Textbox txtChequeSearch;
    @Wire protected Datebox dteChequeDateFrom;
    @Wire protected Datebox dteChequeDateTo;
    @Wire protected Button  btnClearChequeFilters;

    // ── Wired — Screen 3: Cheque Detail Popup ────────────────────────────────

    @Wire protected Window chequeDetailPopup;

    // Popup children — wired manually via getFellow()
    protected Label  lblPopupChequeTitle;
    protected Div    divPopupChequeTypeBadge;

    protected Button btnFlipChequeImage;
    private  boolean isShowingFrontImage = true;
    protected Div    imgPanelFront;
    protected Div    imgPanelBack;
    protected Image  imgChequeFront;
    protected Image  imgChequeRear;
    protected Div    divFrontImagePlaceholder;
    protected Div    divRearImagePlaceholder;

    // MICR Data fields
    protected Label lblChequeNumber;
    protected Label lblCityCode;
    protected Label lblBankCode;
    protected Label lblBranchCode;
    protected Label lblTransactionCode;

    // Cheque Data fields
    protected Label lblPayeeName;
    protected Label lblChequeAmount;
    protected Label lblAccountNumber;
    protected Label lblChequeDate;
    protected Label lblAmountInWords;

    // CBS Data fields (stub — backend integration pending)
    protected Label lblCbsPayeeName;
    protected Label lblCbsAccountStatus;
    protected Label lblCbsPayeeMatch;
    protected Label lblCbsNewAccount;

    // Action bar
    protected Textbox txtVerificationRemarks;
    protected Button  btnAcceptCheque;
    protected Button  btnRejectCheque;

    // Popup navigation
    protected Label  lblPopupChequeCounter;
    protected Button btnPopupPrev;
    protected Button btnPopupNext;

    // ── State ─────────────────────────────────────────────────────────────────

    private List<BatchModel>  allHighValueBatches;
    private List<ChequeModel> currentBatchChequeList;

    // Snapshot of the filtered cheque list at popup open-time.
    // Prev / Next navigation and findNextPendingInList() work against
    // this list so navigation stays within the active filter.
    // After Accept / Reject the list is refreshed from getFilteredCheques().
    private List<ChequeModel> popupFilteredChequeList;

    private int popupCurrentIndex = 0;

    private String batchStatusFilter = "ALL";
    private int    batchCurrentPage  = 1;

    // Cheque multi-select toggle filter state
    // Type group   : highValueActive, referredActive  — OR within group, AND across groups
    // Status group : pendingActive, passedActive, rejectedActive — OR within group
    // Nothing active in a group = show all of that dimension
    private boolean highValueActive  = false;
    private boolean referredActive   = false;
    private boolean pendingActive    = false;
    private boolean passedActive     = false;
    private boolean rejectedActive   = false;

    // Cheque list search + date range
    private String    chequeSearchKeyword = "";
    private LocalDate chequeFilterDateFrom = null;
    private LocalDate chequeFilterDateTo   = null;

    private int chequeCurrentPage = 1;

    // Batch list search + date range
    private String    batchSearchKeyword = "";
    private LocalDate batchFilterDateFrom = null;
    private LocalDate batchFilterDateTo   = null;

    private static final DateTimeFormatter BATCH_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    // Session attribute keys
    private static final String SESSION_KEY_BATCH_DATE_FROM = "v2_batchDateFrom";
    private static final String SESSION_KEY_BATCH_DATE_TO   = "v2_batchDateTo";
    private static final String SESSION_KEY_BATCH_STATUS    = "v2_batchFilter";
    private static final String SESSION_KEY_BATCH_SEARCH    = "v2_batchSearch";
    private static final String SESSION_KEY_ACTIVE_VIEW     = "v2_activeView";
    private static final String SESSION_KEY_ACTIVE_BATCH_ID = "v2_activeBatchId";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // Wire popup children manually via getFellow()
        lblPopupChequeTitle    = (Label) chequeDetailPopup.getFellow("lblPopupChequeTitle");
        divPopupChequeTypeBadge = (Div)  chequeDetailPopup.getFellow("divPopupChequeTypeBadge");

        btnFlipChequeImage       = (Button) chequeDetailPopup.getFellow("btnFlipChequeImage");
        imgPanelFront            = (Div)    chequeDetailPopup.getFellow("imgPanelFront");
        imgPanelBack             = (Div)    chequeDetailPopup.getFellow("imgPanelBack");
        imgChequeFront           = (Image)  chequeDetailPopup.getFellow("imgChequeFront");
        imgChequeRear            = (Image)  chequeDetailPopup.getFellow("imgChequeRear");
        divFrontImagePlaceholder = (Div)    chequeDetailPopup.getFellow("divFrontImagePlaceholder");
        divRearImagePlaceholder  = (Div)    chequeDetailPopup.getFellow("divRearImagePlaceholder");

        lblChequeNumber     = (Label) chequeDetailPopup.getFellow("lblChequeNumber");
        lblCityCode         = (Label) chequeDetailPopup.getFellow("lblCityCode");
        lblBankCode         = (Label) chequeDetailPopup.getFellow("lblBankCode");
        lblBranchCode       = (Label) chequeDetailPopup.getFellow("lblBranchCode");
        lblTransactionCode  = (Label) chequeDetailPopup.getFellow("lblTransactionCode");

        lblPayeeName    = (Label) chequeDetailPopup.getFellow("lblPayeeName");
        lblChequeAmount = (Label) chequeDetailPopup.getFellow("lblChequeAmount");
        lblAccountNumber = (Label) chequeDetailPopup.getFellow("lblAccountNumber");
        lblChequeDate   = (Label) chequeDetailPopup.getFellow("lblChequeDate");
        lblAmountInWords = (Label) chequeDetailPopup.getFellow("lblAmountInWords");

        lblCbsPayeeName     = (Label) chequeDetailPopup.getFellow("lblCbsPayeeName");
        lblCbsAccountStatus = (Label) chequeDetailPopup.getFellow("lblCbsAccountStatus");
        lblCbsPayeeMatch    = (Label) chequeDetailPopup.getFellow("lblCbsPayeeMatch");
        lblCbsNewAccount    = (Label) chequeDetailPopup.getFellow("lblCbsNewAccount");

        txtVerificationRemarks = (Textbox) chequeDetailPopup.getFellow("txtVerificationRemarks");
        btnAcceptCheque        = (Button)  chequeDetailPopup.getFellow("btnAcceptCheque");
        btnRejectCheque        = (Button)  chequeDetailPopup.getFellow("btnRejectCheque");

        lblPopupChequeCounter = (Label)  chequeDetailPopup.getFellow("lblPopupChequeCounter");
        btnPopupPrev          = (Button) chequeDetailPopup.getFellow("btnPopupPrev");
        btnPopupNext          = (Button) chequeDetailPopup.getFellow("btnPopupNext");

        // Image flip button listener
        btnFlipChequeImage.addEventListener("onClick", e -> flipChequeImage());

        // Popup close button listener
        Button btnClosePopup = (Button) chequeDetailPopup.getFellow("btnClosePopup");
        btnClosePopup.addEventListener("onClick", e -> {
            chequeDetailPopup.doEmbedded();
            chequeDetailPopup.setVisible(false);
        });

        // Popup navigation — navigate within popupFilteredChequeList
        btnPopupPrev.addEventListener("onClick", e -> {
            if (popupCurrentIndex > 0) renderPopup(--popupCurrentIndex);
        });
        btnPopupNext.addEventListener("onClick", e -> {
            List<ChequeModel> list = getActivePopupList();
            if (list != null && popupCurrentIndex < list.size() - 1)
                renderPopup(++popupCurrentIndex);
        });

        btnAcceptCheque.addEventListener("onClick", e -> onAcceptChequeClick());
        btnRejectCheque.addEventListener("onClick", e -> onRejectChequeClick());

        chequeDetailPopup.setVisible(false);

        // Restore session state on page load
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        String savedView    = (String) session.getAttribute(SESSION_KEY_ACTIVE_VIEW);
        String savedBatchId = (String) session.getAttribute(SESSION_KEY_ACTIVE_BATCH_ID);

        if ("CHEQUE_LIST".equals(savedView) && savedBatchId != null) {
            loadHighValueBatches();
            BatchModel targetBatch = null;
            if (allHighValueBatches != null) {
                for (BatchModel batch : allHighValueBatches) {
                    if (savedBatchId.equals(batch.getBatchId())) {
                        targetBatch = batch;
                        break;
                    }
                }
            }
            if (targetBatch != null) loadChequeList(targetBatch);
        } else {
            loadHighValueBatches();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 1 — BATCH LIST
    // ════════════════════════════════════════════════════════════════════

    private void saveBatchFilterState() {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        session.setAttribute(SESSION_KEY_BATCH_DATE_FROM, batchFilterDateFrom);
        session.setAttribute(SESSION_KEY_BATCH_DATE_TO,   batchFilterDateTo);
        session.setAttribute(SESSION_KEY_BATCH_STATUS,    batchStatusFilter);
        session.setAttribute(SESSION_KEY_BATCH_SEARCH,    batchSearchKeyword);
    }

    private void saveBatchViewState(String batchId) {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        session.setAttribute(SESSION_KEY_ACTIVE_VIEW,     "CHEQUE_LIST");
        session.setAttribute(SESSION_KEY_ACTIVE_BATCH_ID, batchId);
    }

    private void clearBatchViewState() {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        session.setAttribute(SESSION_KEY_ACTIVE_VIEW,     "BATCH_LIST");
        session.setAttribute(SESSION_KEY_ACTIVE_BATCH_ID, null);
    }

    private boolean restoreBatchFilterState() {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        if (session.getAttribute(SESSION_KEY_BATCH_DATE_FROM) == null) return false;

        batchFilterDateFrom = (LocalDate) session.getAttribute(SESSION_KEY_BATCH_DATE_FROM);
        batchFilterDateTo   = (LocalDate) session.getAttribute(SESSION_KEY_BATCH_DATE_TO);
        batchStatusFilter   = (String)    session.getAttribute(SESSION_KEY_BATCH_STATUS);
        batchSearchKeyword  = (String)    session.getAttribute(SESSION_KEY_BATCH_SEARCH);
        return true;
    }

    private void loadHighValueBatches() {
        allHighValueBatches = verificationService.fetchHighValueBatches();

        int totalHighValue = 0;
        int totalReferred  = 0;
        for (BatchModel batch : allHighValueBatches) {
            totalHighValue += parseHighValueCount(batch);
            totalReferred  += parseReferredCount(batch);
        }

        lblTotalBatchCount.setValue(allHighValueBatches.size() + " Batches");
        lblTotalHighValueCount.setValue(totalHighValue + " HV Cheques");
        lblTotalReferredCount.setValue(totalReferred + " Referred");

        batchCurrentPage = 1;

        boolean restored = restoreBatchFilterState();
        if (!restored) {
            LocalDate today = LocalDate.now();
            batchFilterDateFrom = today;
            batchFilterDateTo   = today;
            batchStatusFilter   = "ALL";
            batchSearchKeyword  = "";
            saveBatchFilterState();
        }

        txtBatchSearch.setValue(batchSearchKeyword);
        cmbBatchStatus.setSelectedIndex(getBatchStatusComboIndex(batchStatusFilter));

        java.util.Date fromDate = (batchFilterDateFrom == null) ? null :
                java.util.Date.from(batchFilterDateFrom.atStartOfDay(ZoneId.systemDefault()).toInstant());
        java.util.Date toDate = (batchFilterDateTo == null) ? null :
                java.util.Date.from(batchFilterDateTo.atStartOfDay(ZoneId.systemDefault()).toInstant());
        dteBatchDateFrom.setValue(fromDate);
        dteBatchDateTo.setValue(toDate);
        updateDateRangeConstraints(dteBatchDateFrom, dteBatchDateTo, batchFilterDateFrom, batchFilterDateTo);

        buildFilteredPagedBatchRows();
    }

    private int getBatchStatusComboIndex(String statusFilter) {
        switch (statusFilter == null ? "ALL" : statusFilter) {
            case "PENDING":    return 1;
            case "VERIFIED":   return 2;
            case "INPROGRESS": return 3;
            default:           return 0;
        }
    }

    @Listen("onChange = #txtBatchSearch")
    public void onBatchSearchChange() {
        batchSearchKeyword = txtBatchSearch.getValue();
        batchCurrentPage = 1;
        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onChange = #dteBatchDateFrom")
    public void onBatchDateFromChange() {
        Date selectedDate = dteBatchDateFrom.getValue();
        batchFilterDateFrom = (selectedDate == null)
                ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        if (batchFilterDateFrom != null && batchFilterDateTo != null
                && batchFilterDateFrom.isAfter(batchFilterDateTo)) {
            batchFilterDateTo = batchFilterDateFrom;
            dteBatchDateTo.setValue(toUtilDate(batchFilterDateTo));
        }
        updateDateRangeConstraints(dteBatchDateFrom, dteBatchDateTo, batchFilterDateFrom, batchFilterDateTo);

        batchCurrentPage = 1;
        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onChange = #dteBatchDateTo")
    public void onBatchDateToChange() {
        Date selectedDate = dteBatchDateTo.getValue();
        batchFilterDateTo = (selectedDate == null)
                ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        if (batchFilterDateFrom != null && batchFilterDateTo != null
                && batchFilterDateTo.isBefore(batchFilterDateFrom)) {
            batchFilterDateFrom = batchFilterDateTo;
            dteBatchDateFrom.setValue(toUtilDate(batchFilterDateFrom));
        }
        updateDateRangeConstraints(dteBatchDateFrom, dteBatchDateTo, batchFilterDateFrom, batchFilterDateTo);

        batchCurrentPage = 1;
        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onSelect = #cmbBatchStatus")
    public void onBatchStatusChange() {
        Comboitem selectedItem = cmbBatchStatus.getSelectedItem();
        batchStatusFilter = (selectedItem != null) ? selectedItem.getValue() : "ALL";
        batchCurrentPage = 1;
        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onClick = #btnClearBatchFilters")
    public void onClearBatchFilters() {
        LocalDate today = LocalDate.now();

        dteBatchDateFrom.setConstraint((String) null);
        dteBatchDateTo.setConstraint((String) null);

        batchFilterDateFrom = today;
        batchFilterDateTo   = today;

        java.util.Date todayDate = java.util.Date.from(
                today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());

        txtBatchSearch.setValue("");
        dteBatchDateFrom.setValue(todayDate);
        dteBatchDateTo.setValue(todayDate);
        cmbBatchStatus.setSelectedIndex(0);
        updateDateRangeConstraints(dteBatchDateFrom, dteBatchDateTo, batchFilterDateFrom, batchFilterDateTo);

        batchSearchKeyword  = "";
        batchStatusFilter   = "ALL";
        batchCurrentPage    = 1;

        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (batchCurrentPage > 1) {
            batchCurrentPage--;
            buildFilteredPagedBatchRows();
        }
    }

    @Listen("onClick = #btnBatchPageNext")
    public void onBatchPageNext() {
        List<BatchModel> filteredBatches = getFilteredBatches();
        int totalPages = getTotalPageCount(filteredBatches.size(), BATCH_PAGE_SIZE);
        if (batchCurrentPage < totalPages) {
            batchCurrentPage++;
            buildFilteredPagedBatchRows();
        }
    }

    private List<BatchModel> getFilteredBatches() {
        List<BatchModel> result = new ArrayList<>();
        for (BatchModel batch : allHighValueBatches) {
            if (!matchesBatchStatusFilter(batch))    continue;
            if (!matchesBatchSearchFilter(batch))    continue;
            if (!matchesBatchDateFilter(batch))      continue;
            result.add(batch);
        }
        return result;
    }

    private boolean matchesBatchStatusFilter(BatchModel batch) {
        if ("ALL".equals(batchStatusFilter)) return true;
        return batchStatusFilter.equals(getBatchDisplayStatus(batch));
    }

    private boolean matchesBatchSearchFilter(BatchModel batch) {
        if (batchSearchKeyword == null || batchSearchKeyword.isBlank()) return true;
        String batchId = batch.getBatchId();
        if (batchId == null) return false;
        return batchId.toLowerCase().contains(batchSearchKeyword.trim().toLowerCase());
    }

    private boolean matchesBatchDateFilter(BatchModel batch) {
        if (batchFilterDateFrom == null && batchFilterDateTo == null) return true;
        if (batch.getCreatedAt() == null) return false;
        LocalDate createdDate = batch.getCreatedAt().toLocalDate();
        if (batchFilterDateFrom != null && createdDate.isBefore(batchFilterDateFrom)) return false;
        if (batchFilterDateTo   != null && createdDate.isAfter(batchFilterDateTo))    return false;
        return true;
    }

    private void buildFilteredPagedBatchRows() {
        List<BatchModel> filteredBatches = getFilteredBatches();
        int totalPages = getTotalPageCount(filteredBatches.size(), BATCH_PAGE_SIZE);
        if (batchCurrentPage > totalPages) batchCurrentPage = totalPages;

        lblBatchPageInfo.setValue("Page " + batchCurrentPage + " of " + totalPages);
        btnBatchPagePrev.setDisabled(batchCurrentPage <= 1);
        btnBatchPageNext.setDisabled(batchCurrentPage >= totalPages);

        int fromIndex = (batchCurrentPage - 1) * BATCH_PAGE_SIZE;
        int toIndex   = Math.min(fromIndex + BATCH_PAGE_SIZE, filteredBatches.size());
        List<BatchModel> currentPageBatches = filteredBatches.subList(fromIndex, toIndex);

        batchGridRows.getChildren().clear();
        batchEmptyState.setVisible(currentPageBatches.isEmpty());

        for (BatchModel batch : currentPageBatches) {
            batchGridRows.appendChild(buildBatchRow(batch));
        }

        int totalFiltered = filteredBatches.size();
        if (totalFiltered == 0) {
            lblBatchRecordCount.setValue("Showing 0 batches");
        } else {
            lblBatchRecordCount.setValue(
                "Showing " + (fromIndex + 1) + "–" + toIndex
                + " of " + totalFiltered + " batches");
        }
    }

    private Row buildBatchRow(BatchModel batch) {
        int highValueCount  = parseHighValueCount(batch);
        int pendingCount    = parsePendingCount(batch);
        int processedCount  = parseProcessedCount(batch);

        Row row = new Row();

        Label batchIdLabel = new Label(safeValue(batch.getBatchId()));
        batchIdLabel.setSclass("v2-batch-no-cell");
        row.appendChild(cell(batchIdLabel, ""));

        Label dateLabel = new Label(formatBatchDate(batch.getCreatedAt()));
        dateLabel.setSclass("v2-date-cell");
        row.appendChild(cell(dateLabel, "v2-center"));

        row.appendChild(cell(new Label(String.valueOf(batch.getTotalCheques())), "v2-center"));

        Label hvCountLabel = new Label(String.valueOf(highValueCount));
        hvCountLabel.setSclass("v2-hv-count");
        row.appendChild(cell(hvCountLabel, "v2-center"));

        row.appendChild(cell(new Label(String.valueOf(pendingCount)),   "v2-center"));
        row.appendChild(cell(new Label(String.valueOf(processedCount)), "v2-center"));

        String displayStatus = getBatchDisplayStatus(batch);
        String statusText, statusCss;
        switch (displayStatus) {
            case "VERIFIED":
                statusText = "VERIFIED";
                statusCss  = "v2-status-badge v2-status-complete";
                break;
            case "INPROGRESS":
                statusText = "IN PROGRESS";
                statusCss  = "v2-status-badge v2-status-pending";
                break;
            default:
                statusText = "READY FOR VERIFICATION";
                statusCss  = "v2-status-badge v2-status-notstarted";
                break;
        }
        Label statusLabel = new Label(statusText);
        statusLabel.setSclass(statusCss);
        row.appendChild(cell(statusLabel, "v2-center"));

        Button processButton = new Button("Process");
        processButton.setSclass("v2-process-btn");
        processButton.addEventListener("onClick", e -> loadChequeList(batch));
        row.appendChild(cell(processButton, "v2-center"));

        return row;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 2 — CHEQUE LIST
    // ════════════════════════════════════════════════════════════════════

    private void loadChequeList(BatchModel batch) {
        currentBatchChequeList = verificationService.fetchHighValueChequesForBatch(batch.getBatchId());
        lblActiveBatchId.setValue(batch.getBatchId());

        // Reset all filter toggles and search/date when entering a new batch
        highValueActive  = false;
        referredActive   = false;
        pendingActive    = false;
        passedActive     = false;
        rejectedActive   = false;
        chequeSearchKeyword  = "";
        chequeFilterDateFrom = null;
        chequeFilterDateTo   = null;
        chequeCurrentPage    = 1;

        popupFilteredChequeList = null;

        txtChequeSearch.setValue("");
        dteChequeDateFrom.setValue(null);
        dteChequeDateTo.setValue(null);

        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();

        batchListView.setVisible(false);
        chequeListView.setVisible(true);
        saveBatchViewState(batch.getBatchId());
    }

    @Listen("onClick = #btnChequePagePrev")
    public void onChequePagePrev() {
        if (chequeCurrentPage > 1) {
            chequeCurrentPage--;
            buildFilteredPagedChequeRows();
        }
    }

    @Listen("onClick = #btnChequePageNext")
    public void onChequePageNext() {
        List<ChequeModel> filteredCheques = getFilteredCheques();
        int totalPages = getTotalPageCount(filteredCheques.size(), CHEQUE_PAGE_SIZE);
        if (chequeCurrentPage < totalPages) {
            chequeCurrentPage++;
            buildFilteredPagedChequeRows();
        }
    }
    
    @Listen("onClick = #chipFilterHighValue")
    public void onChipHighValueClick() {
        highValueActive = !highValueActive;
        chequeCurrentPage = 1;
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #chipFilterReferred")
    public void onChipReferredClick() {
        referredActive = !referredActive;
        chequeCurrentPage = 1;
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #chipStatusPending")
    public void onChipStatusPendingClick() {
        pendingActive = !pendingActive;
        chequeCurrentPage = 1;
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #chipStatusPassed")
    public void onChipStatusPassedClick() {
        passedActive = !passedActive;
        chequeCurrentPage = 1;
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #chipStatusRejected")
    public void onChipStatusRejectedClick() {
        rejectedActive = !rejectedActive;
        chequeCurrentPage = 1;
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onChange = #txtChequeSearch")
    public void onChequeSearchChange() {
        chequeSearchKeyword = txtChequeSearch.getValue();
        chequeCurrentPage = 1;
        buildFilteredPagedChequeRows();
    }

    @Listen("onChange = #dteChequeDateFrom")
    public void onChequeDateFromChange() {
        java.util.Date selectedDate = dteChequeDateFrom.getValue();
        chequeFilterDateFrom = (selectedDate == null) ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        if (chequeFilterDateFrom != null && chequeFilterDateTo != null
                && chequeFilterDateFrom.isAfter(chequeFilterDateTo)) {
            chequeFilterDateTo = chequeFilterDateFrom;
            dteChequeDateTo.setValue(toUtilDate(chequeFilterDateTo));
        }
        updateDateRangeConstraints(dteChequeDateFrom, dteChequeDateTo,
                chequeFilterDateFrom, chequeFilterDateTo);

        chequeCurrentPage = 1;
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onChange = #dteChequeDateTo")
    public void onChequeDateToChange() {
        java.util.Date selectedDate = dteChequeDateTo.getValue();
        chequeFilterDateTo = (selectedDate == null) ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        if (chequeFilterDateFrom != null && chequeFilterDateTo != null
                && chequeFilterDateTo.isBefore(chequeFilterDateFrom)) {
            chequeFilterDateFrom = chequeFilterDateTo;
            dteChequeDateFrom.setValue(toUtilDate(chequeFilterDateFrom));
        }
        updateDateRangeConstraints(dteChequeDateFrom, dteChequeDateTo,
                chequeFilterDateFrom, chequeFilterDateTo);

        chequeCurrentPage = 1;
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #btnClearChequeFilters")
    public void onClearChequeFilters() {
        highValueActive  = false;
        referredActive   = false;
        pendingActive    = false;
        passedActive     = false;
        rejectedActive   = false;
        chequeSearchKeyword  = "";
        chequeFilterDateFrom = null;
        chequeFilterDateTo   = null;
        chequeCurrentPage    = 1;

        dteChequeDateFrom.setConstraint((String) null);
        dteChequeDateTo.setConstraint((String) null);

        txtChequeSearch.setValue("");
        dteChequeDateFrom.setValue(null);
        dteChequeDateTo.setValue(null);

        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHEQUE FILTER — getFilteredCheques()
    //
    //  Type group   (HV / RF)              : OR within group, AND with status group
    //  Status group (Pending/Passed/Rejected): OR within group
    //  Search       : cheque no. OR payee name substring match
    //  Date range   : cheque date (dd/MM/yyyy) between from and to
    //
    //  Nothing active in a group = no filter applied for that dimension
    // ════════════════════════════════════════════════════════════════════

    private List<ChequeModel> getFilteredCheques() {
        if (currentBatchChequeList == null) return new ArrayList<>();

        List<ChequeModel> result = new ArrayList<>();

        for (ChequeModel cheque : currentBatchChequeList) {

            // Type group filter (HV / RF)
            boolean anyTypeActive = highValueActive || referredActive;
            if (anyTypeActive) {
                boolean isReferred = cheque.isReferred();
                boolean typeMatches = (highValueActive && !isReferred) || (referredActive && isReferred);
                if (!typeMatches) continue;
            }

            // Status group filter (Pending / Passed / Rejected)
            boolean anyStatusActive = pendingActive || passedActive || rejectedActive;
            if (anyStatusActive) {
                ChequeStatus chequeStatus = ChequeStatus.fromDb(cheque.getVerStatus());
                boolean isPassed   = chequeStatus == ChequeStatus.VERIFIED;
                boolean isRejected = chequeStatus == ChequeStatus.REJECTED;
                boolean isPending  = !isPassed && !isRejected;
                boolean statusMatches = (pendingActive  && isPending)
                                     || (passedActive   && isPassed)
                                     || (rejectedActive && isRejected);
                if (!statusMatches) continue;
            }

            // Search filter (cheque no. or payee name)
            if (chequeSearchKeyword != null && !chequeSearchKeyword.isBlank()) {
                String keyword   = chequeSearchKeyword.trim().toLowerCase();
                String chequeNo  = (cheque.getChequeNo()  != null) ? cheque.getChequeNo().toLowerCase()  : "";
                String payeeName = (cheque.getPayeeName() != null) ? cheque.getPayeeName().toLowerCase() : "";
                if (!chequeNo.contains(keyword) && !payeeName.contains(keyword)) continue;
            }

            // Cheque date range filter
            if (chequeFilterDateFrom != null || chequeFilterDateTo != null) {
                LocalDate chequeDate = parseChequeDate(cheque.getChequeDate());
                if (chequeDate == null) continue;
                if (chequeFilterDateFrom != null && chequeDate.isBefore(chequeFilterDateFrom)) continue;
                if (chequeFilterDateTo   != null && chequeDate.isAfter(chequeFilterDateTo))    continue;
            }

            result.add(cheque);
        }

        return result;
    }

    // Parses chequeDate String "dd/MM/yyyy" → LocalDate; returns null if invalid
    private LocalDate parseChequeDate(String dateString) {
        if (dateString == null || dateString.isBlank()) return null;
        try {
            return LocalDate.parse(dateString.trim(),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return null;
        }
    }

    private void buildFilteredPagedChequeRows() {
        List<ChequeModel> filteredCheques = getFilteredCheques();
        int totalPages = getTotalPageCount(filteredCheques.size(), CHEQUE_PAGE_SIZE);
        if (chequeCurrentPage > totalPages) chequeCurrentPage = totalPages;

        lblChequePageInfo.setValue("Page " + chequeCurrentPage + " of " + totalPages);
        btnChequePagePrev.setDisabled(chequeCurrentPage <= 1);
        btnChequePageNext.setDisabled(chequeCurrentPage >= totalPages);

        int fromIndex = (chequeCurrentPage - 1) * CHEQUE_PAGE_SIZE;
        int toIndex   = Math.min(fromIndex + CHEQUE_PAGE_SIZE, filteredCheques.size());
        List<ChequeModel> currentPageCheques = filteredCheques.subList(fromIndex, toIndex);

        chequeGridRows.getChildren().clear();
        chequeEmptyState.setVisible(currentPageCheques.isEmpty());

        int serialNumber = fromIndex + 1;
        for (ChequeModel cheque : currentPageCheques) {
            chequeGridRows.appendChild(buildChequeRow(serialNumber++, cheque));
        }

        int totalFiltered = filteredCheques.size();
        if (lblChequeRecordCount != null) {
            if (totalFiltered == 0) {
                lblChequeRecordCount.setValue("Showing 0 cheques");
            } else {
                lblChequeRecordCount.setValue(
                    "Showing " + (fromIndex + 1) + "–" + toIndex
                    + " of " + totalFiltered + " cheques");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHIP COUNTS — refreshChequeFilterChips()
    //
    //  HV / RF counts   : always full totals, never filtered
    //  Status counts    : scoped to active type toggles
    // ════════════════════════════════════════════════════════════════════

    private void refreshChequeFilterChips() {
        if (currentBatchChequeList == null) return;

        int hvCount = 0, rfCount = 0;
        int pendingCount = 0, passedCount = 0, rejectedCount = 0;

        for (ChequeModel cheque : currentBatchChequeList) {
            boolean isReferred = cheque.isReferred();

            // Apply date filter to chip counts
            if (chequeFilterDateFrom != null || chequeFilterDateTo != null) {
                LocalDate chequeDate = parseChequeDate(cheque.getChequeDate());
                if (chequeDate == null) continue;
                if (chequeFilterDateFrom != null && chequeDate.isBefore(chequeFilterDateFrom)) continue;
                if (chequeFilterDateTo   != null && chequeDate.isAfter(chequeFilterDateTo))    continue;
            }

            if (isReferred) rfCount++;
            else            hvCount++;

            boolean anyTypeActive = highValueActive || referredActive;
            boolean inScope;
            if (!anyTypeActive) {
                inScope = true;
            } else {
                inScope = (highValueActive && !isReferred) || (referredActive && isReferred);
            }

            if (inScope) {
                ChequeStatus chequeStatus = ChequeStatus.fromDb(cheque.getVerStatus());
                if      (chequeStatus == ChequeStatus.VERIFIED) passedCount++;
                else if (chequeStatus == ChequeStatus.REJECTED) rejectedCount++;
                else                                             pendingCount++;
            }
        }

        chipFilterHighValue.setValue(hvCount + " High Value");
        chipFilterReferred.setValue(rfCount + " Referred");
        chipStatusPending.setValue(pendingCount + " Pending");
        chipStatusPassed.setValue(passedCount + " Passed");
        chipStatusRejected.setValue(rejectedCount + " Rejected");
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHIP STYLES — updateChequeChipStyles()
    //  Adds chip-filter-active CSS class when the toggle boolean is true
    // ════════════════════════════════════════════════════════════════════

    private void updateChequeChipStyles() {
        chipFilterHighValue.setSclass("v2-stat-chip chip-filter-hv chip-filter"
                + (highValueActive ? " chip-filter-active" : ""));
        chipFilterReferred.setSclass("v2-stat-chip chip-filter-ref chip-filter"
                + (referredActive  ? " chip-filter-active" : ""));
        chipStatusPending.setSclass("v2-stat-chip pending-chip chip-filter"
                + (pendingActive   ? " chip-filter-active" : ""));
        chipStatusPassed.setSclass("v2-stat-chip passed-chip chip-filter"
                + (passedActive    ? " chip-filter-active" : ""));
        chipStatusRejected.setSclass("v2-stat-chip rejected-chip chip-filter"
                + (rejectedActive  ? " chip-filter-active" : ""));
    }

    private Row buildChequeRow(int serialNumber, ChequeModel cheque) {
        Row row = new Row();
        row.appendChild(cell(new Label(String.valueOf(serialNumber)), "v2-center"));
        row.appendChild(cell(new Label(safeValue(cheque.getChequeNo())), ""));
        row.appendChild(cell(new Label(safeValue(cheque.getPayeeName())), ""));
        row.appendChild(cell(new Label(formatAmount(cheque.getAmount())), "v2-right"));
        row.appendChild(cell(new Label(safeValue(cheque.getChequeDate())), "v2-center"));
        row.appendChild(cell(buildFlagLabel(cheque.isReferred()), "v2-center"));

        String verStatus = (cheque.getVerStatus() != null) ? cheque.getVerStatus() : "PENDING";
        String verStatusDisplay = ("V1_PENDING".equalsIgnoreCase(verStatus)
                || "V2_PENDING".equalsIgnoreCase(verStatus)
                || "SUBMITTED".equalsIgnoreCase(verStatus)) ? "PENDING" : verStatus;

        Label verStatusLabel = new Label(verStatusDisplay);
        verStatusLabel.setSclass("v2-status-badge v2-status-" + safeStatusCss(verStatusDisplay));
        row.appendChild(cell(verStatusLabel, "v2-center"));

        Button openButton = new Button("Open");
        openButton.setSclass("v2-open-btn");
        openButton.addEventListener("onClick", e -> openChequeDetailPopup(cheque));
        row.appendChild(cell(openButton, "v2-center"));

        return row;
    }

    private Label buildFlagLabel(boolean isReferred) {
        if (isReferred) {
            Label label = new Label("⇄ REF");
            label.setSclass("v2-ref-flag");
            return label;
        }
        Label label = new Label("⚑ HV");
        label.setSclass("v2-hv-flag");
        return label;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 3 — POPUP
    // ════════════════════════════════════════════════════════════════════

    // Opens the popup for a specific cheque.
    // Snapshots the current filtered list into popupFilteredChequeList so that
    // Prev / Next and findNextPendingInList() stay within the filtered set.
    private void openChequeDetailPopup(ChequeModel cheque) {
        popupFilteredChequeList = getFilteredCheques();

        popupCurrentIndex = popupFilteredChequeList.indexOf(cheque);
        if (popupCurrentIndex < 0) popupCurrentIndex = 0;

        resetChequeImageToFront();
        renderPopup(popupCurrentIndex);
        chequeDetailPopup.setVisible(true);
        chequeDetailPopup.doModal();
    }

    // Returns the list the popup should navigate within.
    // Falls back to the full batch list if the snapshot is null.
    private List<ChequeModel> getActivePopupList() {
        return (popupFilteredChequeList != null && !popupFilteredChequeList.isEmpty())
               ? popupFilteredChequeList
               : currentBatchChequeList;
    }

    // Renders a cheque at the given index inside getActivePopupList().
    // Counter shows "x / total" relative to the filtered set.
    private void renderPopup(int index) {
        List<ChequeModel> list = getActivePopupList();
        if (list == null || list.isEmpty()) return;

        ChequeModel cheque = list.get(index);

        lblPopupChequeTitle.setValue("Cheque  #" + safeValue(cheque.getChequeNo()));

        boolean isReferred = cheque.isReferred();
        if (isReferred) {
            divPopupChequeTypeBadge.setSclass("v2-badge-ref");
            divPopupChequeTypeBadge.getChildren().clear();
            divPopupChequeTypeBadge.appendChild(new Label("REFERRED"));
        } else {
            divPopupChequeTypeBadge.setSclass("v2-badge-hv");
            divPopupChequeTypeBadge.getChildren().clear();
            divPopupChequeTypeBadge.appendChild(new Label("HIGH VALUE"));
        }

        String sortCode   = safeValue(cheque.getSortCode());
        String[] micrParts = splitMicrSortCode(sortCode);
        lblChequeNumber.setValue(safeValue(cheque.getChequeNo()));
        lblCityCode.setValue(micrParts[0]);
        lblBankCode.setValue(micrParts[1]);
        lblBranchCode.setValue(micrParts[2]);
        lblTransactionCode.setValue(safeValue(cheque.getTransactionCode()));

        lblPayeeName.setValue(safeValue(cheque.getPayeeName()));
        lblChequeAmount.setValue(formatAmount(cheque.getAmount()));
        lblAccountNumber.setValue(safeValue(cheque.getPayeeAccountNo()));
        lblChequeDate.setValue(safeValue(cheque.getChequeDate()));
        lblAmountInWords.setValue(safeValue(cheque.getAmountInWords()));

        // CBS live lookup using payee_account_no (correct CBS key)
        String accountNo = cheque.getPayeeAccountNo() != null ? cheque.getPayeeAccountNo().trim() : null;
        if (accountNo != null && !accountNo.isBlank()) {
            com.fasterxml.jackson.databind.JsonNode cbsFields = cbsService.lookupAccountFields(accountNo);
            if (cbsFields != null && !cbsFields.isMissingNode()) {
                String cbsPayeeName = cbsFields.path("accountHolderName").path("stringValue").asText(null);
                boolean isActive    = cbsFields.path("active").path("booleanValue").asBoolean(false);

                lblCbsPayeeName.setValue(cbsPayeeName != null ? cbsPayeeName : "—");
                lblCbsAccountStatus.setValue(isActive ? "Active" : "Inactive");
                lblCbsNewAccount.setValue(cbsService.getIsNewAccount(accountNo));

                String payeeName = cheque.getPayeeName();
                if (cbsPayeeName != null && payeeName != null) {
                    boolean nameMatches = cbsPayeeName.trim().equalsIgnoreCase(payeeName.trim());
                    lblCbsPayeeMatch.setValue(nameMatches ? "Match" : "Mismatch");
                    lblCbsPayeeMatch.setSclass(nameMatches ? "cbs-match-ok" : "cbs-match-fail");
                } else {
                    lblCbsPayeeMatch.setValue("—");
                    lblCbsPayeeMatch.setSclass("");
                }
            } else {
                lblCbsPayeeName.setValue("—");
                lblCbsAccountStatus.setValue("Not found");
                lblCbsNewAccount.setValue("—");
                lblCbsPayeeMatch.setValue("—");
                lblCbsPayeeMatch.setSclass("");
            }
        } else {
            lblCbsPayeeName.setValue("—");
            lblCbsAccountStatus.setValue("—");
            lblCbsNewAccount.setValue("—");
            lblCbsPayeeMatch.setValue("—");
            lblCbsPayeeMatch.setSclass("");
        }

        ChequeStatus currentStatus = ChequeStatus.fromDb(cheque.getVerStatus());
        boolean alreadyActioned    = currentStatus == ChequeStatus.VERIFIED
                                  || currentStatus == ChequeStatus.REJECTED;
        btnAcceptCheque.setDisabled(alreadyActioned);
        btnRejectCheque.setDisabled(alreadyActioned);

        txtVerificationRemarks.setValue("");
        txtVerificationRemarks.setSclass("v2-remarks-box");

        byte[] frontImageBytes = cheque.getFrontImageBytes();
        if (frontImageBytes != null && frontImageBytes.length > 0) {
            imgChequeFront.setSrc("data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(frontImageBytes));
            imgChequeFront.setVisible(true);
            divFrontImagePlaceholder.setVisible(false);
        } else {
            imgChequeFront.setVisible(false);
            divFrontImagePlaceholder.setVisible(true);
        }

        byte[] rearImageBytes = cheque.getRearImageBytes();
        if (rearImageBytes != null && rearImageBytes.length > 0) {
            imgChequeRear.setSrc("data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(rearImageBytes));
            imgChequeRear.setVisible(true);
            divRearImagePlaceholder.setVisible(false);
        } else {
            imgChequeRear.setVisible(false);
            divRearImagePlaceholder.setVisible(true);
        }

        int totalInList = list.size();
        lblPopupChequeCounter.setValue((index + 1) + " / " + totalInList);
        btnPopupPrev.setDisabled(index == 0);
        btnPopupNext.setDisabled(index == totalInList - 1);
    }

    // Flips the cheque image between FRONT and BACK
    private void flipChequeImage() {
        isShowingFrontImage = !isShowingFrontImage;
        imgPanelFront.setVisible(isShowingFrontImage);
        imgPanelBack.setVisible(!isShowingFrontImage);
        btnFlipChequeImage.setLabel(isShowingFrontImage ? "Show Back" : "Show Front");
    }

    // Resets image to front side whenever a new cheque is opened
    private void resetChequeImageToFront() {
        isShowingFrontImage = true;
        imgPanelFront.setVisible(true);
        imgPanelBack.setVisible(false);
        btnFlipChequeImage.setLabel("Show Back");
    }

    // ════════════════════════════════════════════════════════════════════
    //  VERIFICATION ACTIONS
    // ════════════════════════════════════════════════════════════════════

    private void onAcceptChequeClick() {
        String verifierUsername = getVerifierUsername();
        String remarks = txtVerificationRemarks.getValue();
        if (remarks == null || remarks.isBlank()) {
            remarks = "Accepted by " + verifierUsername;
            txtVerificationRemarks.setValue(remarks);
        }
        performVerification(ChequeStatus.VERIFIED.db(), remarks, verifierUsername);
    }

    private void onRejectChequeClick() {
        String remarks = txtVerificationRemarks.getValue();
        if (remarks == null || remarks.isBlank()) {
            txtVerificationRemarks.setSclass("v2-remarks-box v2-remarks-box-error");
            txtVerificationRemarks.focus();
            return;
        }
        txtVerificationRemarks.setSclass("v2-remarks-box");

        String verifierUsername = getVerifierUsername();
        performVerification(ChequeStatus.REJECTED.db(), remarks, verifierUsername);
    }

    // Saves the verification result, refreshes the filtered list, and navigates to next pending
    private void performVerification(String action, String remarks, String verifierUsername) {
        List<ChequeModel> list = getActivePopupList();
        if (list == null || list.isEmpty()) return;

        ChequeModel cheque  = list.get(popupCurrentIndex);
        long        chequeId = Long.parseLong(cheque.getId());

        verificationService.submitHighValueChequeVerification(chequeId, action, verifierUsername, remarks);

        ChequeStatus resolvedChequeStatus = ChequeStatus.fromDb(action);
        String updatedVerificationStatus = (resolvedChequeStatus == ChequeStatus.VERIFIED)
                ? ChequeStatus.VERIFIED.db()
                : ChequeStatus.REJECTED.db();
        cheque.setVerStatus(updatedVerificationStatus);

        verificationService.evaluateAndUpdateBatchVerificationStatus(cheque.getBatchId());

        refreshChequeFilterChips();
        buildFilteredPagedChequeRows();

        // Refresh the popup filtered list after the status change
        popupFilteredChequeList = getFilteredCheques();

        int nextIndex = findNextPendingInList(popupCurrentIndex, popupFilteredChequeList);
        popupCurrentIndex = (nextIndex >= 0) ? nextIndex : popupCurrentIndex;
        renderPopup(popupCurrentIndex);
    }

    // Searches for the next pending cheque within the given list
    private int findNextPendingInList(int currentIndex, List<ChequeModel> list) {
        if (list == null) return -1;
        for (int i = currentIndex + 1; i < list.size(); i++) {
            ChequeStatus status = ChequeStatus.fromDb(list.get(i).getVerStatus());
            if (status != ChequeStatus.VERIFIED && status != ChequeStatus.REJECTED) return i;
        }
        for (int i = currentIndex - 1; i >= 0; i--) {
            ChequeStatus status = ChequeStatus.fromDb(list.get(i).getVerStatus());
            if (status != ChequeStatus.VERIFIED && status != ChequeStatus.REJECTED) return i;
        }
        return -1;
    }

    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        chequeListView.setVisible(false);
        batchListView.setVisible(true);
        currentBatchChequeList  = null;
        popupFilteredChequeList = null;

        // Reset all cheque filter state when leaving the cheque list
        highValueActive  = false;
        referredActive   = false;
        pendingActive    = false;
        passedActive     = false;
        rejectedActive   = false;
        chequeSearchKeyword  = "";
        chequeFilterDateFrom = null;
        chequeFilterDateTo   = null;
        chequeCurrentPage    = 1;

        clearBatchViewState();
        loadHighValueBatches();
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String getVerifierUsername() {
        String username = SecurityUtil.getCurrentUserId();
        return (username == null || username.isBlank()) ? "Unknown" : username;
    }

    // Keeps a From/To datebox pair from accepting an invalid range
    private void updateDateRangeConstraints(Datebox fromBox, Datebox toBox,
                                            LocalDate fromDate, LocalDate toDate) {
        if (toBox != null) {
            toBox.setConstraint(fromDate != null ? "after " + formatYyyyMmDd(fromDate) : (String) null);
        }
        if (fromBox != null) {
            fromBox.setConstraint(toDate != null ? "before " + formatYyyyMmDd(toDate) : (String) null);
        }
    }

    private String formatYyyyMmDd(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private Date toUtilDate(LocalDate localDate) {
        return (localDate == null) ? null
                : Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private String[] splitMicrSortCode(String sortCode) {
        if (sortCode == null || sortCode.isBlank() || "——".equals(sortCode) || "0".equals(sortCode)) {
            return new String[]{"—", "—", "—"};
        }
        String code = sortCode.trim();
        if (code.length() >= 9) return new String[]{code.substring(0, 3), code.substring(3, 6), code.substring(6, 9)};
        if (code.length() >= 6) return new String[]{code.substring(0, 3), code.substring(3, 6), "—"};
        return new String[]{code, "—", "—"};
    }

    private int getTotalPageCount(int listSize, int pageSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / pageSize);
    }

    private int parseHighValueCount(BatchModel batch)  { return parseEncodedPart(batch.getPresentingBankId(), 0); }
    private int parsePendingCount(BatchModel batch)    { return parseEncodedPart(batch.getPresentingBankId(), 1); }
    private int parseProcessedCount(BatchModel batch)  { return parseEncodedPart(batch.getPresentingBankId(), 2); }
    private int parseReferredCount(BatchModel batch)   { return parseEncodedPart(batch.getPresentingBankId(), 3); }

    private String getBatchDisplayStatus(BatchModel batch) {
        int hvCount       = parseHighValueCount(batch);
        int processedCount = parseProcessedCount(batch);
        if (processedCount == 0)             return "PENDING";
        if (processedCount < hvCount)        return "INPROGRESS";
        return "VERIFIED";
    }

    private int parseEncodedPart(String encodedValue, int partIndex) {
        if (encodedValue == null || encodedValue.isEmpty()) return 0;
        try {
            String[] parts = encodedValue.split("\\|");
            return parts.length > partIndex ? Integer.parseInt(parts[partIndex]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Cell cell(Component child, String sclass) {
        Cell c = new Cell();
        if (sclass != null && !sclass.isEmpty()) c.setSclass(sclass);
        c.appendChild(child);
        return c;
    }

    private String safeValue(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

    private String safeStatusCss(String status) {
        return (status == null || status.isBlank()) ? "pending" : status.toLowerCase();
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "——";
        NumberFormat formatter = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
        return "Rs. " + formatter.format(amount);
    }

    private String formatBatchDate(java.time.LocalDateTime dateTime) {
        if (dateTime == null) return "—";
        return dateTime.format(BATCH_DATE_FORMATTER);
    }
}