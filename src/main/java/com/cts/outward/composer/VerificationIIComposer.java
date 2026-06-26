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

    // Manual DI: service wired with its implementation (no Spring)
    private final VerificationIIService verificationService = new VerificationIIServiceImpl();
    // CBS service used for live account lookup when rendering the cheque detail popup
    private final CBSService cbsService = new CBSServiceImpl(new CBSDAOImpl());

    // Fixed page sizes for batch list and cheque list — not user-configurable in V2
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

    // chequeDetailPopup is wired by @Wire; its internal children are resolved manually via getFellow()
    @Wire protected Window chequeDetailPopup;

    // Popup children — wired manually via getFellow() since they are inside a Window, not direct ZUL children
    protected Label  lblPopupChequeTitle;
    protected Div    divPopupChequeTypeBadge;

    protected Button btnFlipChequeImage;
    // Tracks whether the front or back image is currently shown in the popup
    private  boolean isShowingFrontImage = true;
    protected Div    imgPanelFront;
    protected Div    imgPanelBack;
    protected Image  imgChequeFront;
    protected Image  imgChequeRear;
    protected Div    divFrontImagePlaceholder;
    protected Div    divRearImagePlaceholder;

    // MICR Data fields — populated from cheque.getSortCode() split into 3 parts
    protected Label lblChequeNumber;
    protected Label lblCityCode;
    protected Label lblBankCode;
    protected Label lblBranchCode;
    protected Label lblTransactionCode;

    // Cheque Data fields — populated directly from ChequeModel fields
    protected Label lblPayeeName;
    protected Label lblChequeAmount;
    protected Label lblAccountNumber;
    protected Label lblChequeDate;
    protected Label lblAmountInWords;

    // CBS Data fields — populated from live CBS lookup using payee account number
    protected Label lblCbsPayeeName;
    protected Label lblCbsAccountStatus;
    protected Label lblCbsPayeeMatch;
    protected Label lblCbsNewAccount;

    // Action bar — remarks are required for reject; auto-filled for accept if left blank
    protected Textbox txtVerificationRemarks;
    protected Button  btnAcceptCheque;
    protected Button  btnRejectCheque;

    // Popup navigation — navigates within popupFilteredChequeList (snapshot of filtered set at open time)
    protected Label  lblPopupChequeCounter;
    protected Button btnPopupPrev;
    protected Button btnPopupNext;

    // ── State ─────────────────────────────────────────────────────────────────

    // Full list of all HV batches fetched from DB — source for in-memory filtering
    private List<BatchModel>  allHighValueBatches;
    // Full cheque list for the currently open batch — source for cheque filter logic
    private List<ChequeModel> currentBatchChequeList;

    // Snapshot of the filtered cheque list at popup open-time.
    // Prev / Next navigation and findNextPendingInList() work against
    // this list so navigation stays within the active filter.
    // After Accept / Reject the list is refreshed from getFilteredCheques().
    private List<ChequeModel> popupFilteredChequeList;

    // Current position within popupFilteredChequeList for popup Prev/Next navigation
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

    // Formatter for batch created date display in the batch list table
    private static final DateTimeFormatter BATCH_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    // Session attribute keys — persist batch filter state and active view across page navigations
    private static final String SESSION_KEY_BATCH_DATE_FROM = "v2_batchDateFrom";
    private static final String SESSION_KEY_BATCH_DATE_TO   = "v2_batchDateTo";
    private static final String SESSION_KEY_BATCH_STATUS    = "v2_batchFilter";
    private static final String SESSION_KEY_BATCH_SEARCH    = "v2_batchSearch";
    private static final String SESSION_KEY_ACTIVE_VIEW     = "v2_activeView";
    private static final String SESSION_KEY_ACTIVE_BATCH_ID = "v2_activeBatchId";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Called by ZK after all @Wire fields are injected.
     * Manually wires popup children, attaches button listeners, then restores the last active view from session.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // Popup children are inside a Window — @Wire can't reach them, so getFellow() is used instead
        lblPopupChequeTitle     = (Label) chequeDetailPopup.getFellow("lblPopupChequeTitle");
        divPopupChequeTypeBadge = (Div)   chequeDetailPopup.getFellow("divPopupChequeTypeBadge");

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

        lblPayeeName     = (Label) chequeDetailPopup.getFellow("lblPayeeName");
        lblChequeAmount  = (Label) chequeDetailPopup.getFellow("lblChequeAmount");
        lblAccountNumber = (Label) chequeDetailPopup.getFellow("lblAccountNumber");
        lblChequeDate    = (Label) chequeDetailPopup.getFellow("lblChequeDate");
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

        // Popup button listeners — attached programmatically since buttons are inside the Window
        btnFlipChequeImage.addEventListener("onClick", e -> flipChequeImage());

        // Close button hides popup and returns it to embedded (non-modal) state
        Button btnClosePopup = (Button) chequeDetailPopup.getFellow("btnClosePopup");
        btnClosePopup.addEventListener("onClick", e -> {
            chequeDetailPopup.doEmbedded();
            chequeDetailPopup.setVisible(false);
        });

        // Prev/Next navigate within popupFilteredChequeList (the snapshot taken at open time)
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

        // Start popup hidden; it is shown via doModal() when a cheque is opened
        chequeDetailPopup.setVisible(false);

        // Restore active view from session: if user was on a cheque list, go back to that batch
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        String savedView    = (String) session.getAttribute(SESSION_KEY_ACTIVE_VIEW);
        String savedBatchId = (String) session.getAttribute(SESSION_KEY_ACTIVE_BATCH_ID);

        if ("CHEQUE_LIST".equals(savedView) && savedBatchId != null) {
            // Load batches first so we can locate the saved batch by ID
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
            // Navigate directly to the cheque list if the batch still exists; otherwise show batch list
            if (targetBatch != null) loadChequeList(targetBatch);
        } else {
            loadHighValueBatches();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 1 — BATCH LIST
    // ════════════════════════════════════════════════════════════════════

    /** Saves all batch filter fields to ZK session so they survive navigation away and back */
    private void saveBatchFilterState() {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        session.setAttribute(SESSION_KEY_BATCH_DATE_FROM, batchFilterDateFrom);
        session.setAttribute(SESSION_KEY_BATCH_DATE_TO,   batchFilterDateTo);
        session.setAttribute(SESSION_KEY_BATCH_STATUS,    batchStatusFilter);
        session.setAttribute(SESSION_KEY_BATCH_SEARCH,    batchSearchKeyword);
    }

    /** Records in session that the user is on the cheque list view for the given batch */
    private void saveBatchViewState(String batchId) {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        session.setAttribute(SESSION_KEY_ACTIVE_VIEW,     "CHEQUE_LIST");
        session.setAttribute(SESSION_KEY_ACTIVE_BATCH_ID, batchId);
    }

    /** Resets active view back to BATCH_LIST in session when user navigates back from cheque list */
    private void clearBatchViewState() {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        session.setAttribute(SESSION_KEY_ACTIVE_VIEW,     "BATCH_LIST");
        session.setAttribute(SESSION_KEY_ACTIVE_BATCH_ID, null);
    }

    /**
     * Reads saved batch filter values from session into local variables.
     * Returns false if no saved state exists (first visit this session).
     */
    private boolean restoreBatchFilterState() {
        org.zkoss.zk.ui.Session session = Sessions.getCurrent();
        // Use date-from as the sentinel: if it's null, nothing was ever saved
        if (session.getAttribute(SESSION_KEY_BATCH_DATE_FROM) == null) return false;

        batchFilterDateFrom = (LocalDate) session.getAttribute(SESSION_KEY_BATCH_DATE_FROM);
        batchFilterDateTo   = (LocalDate) session.getAttribute(SESSION_KEY_BATCH_DATE_TO);
        batchStatusFilter   = (String)    session.getAttribute(SESSION_KEY_BATCH_STATUS);
        batchSearchKeyword  = (String)    session.getAttribute(SESSION_KEY_BATCH_SEARCH);
        return true;
    }

    /**
     * Fetches all HV batches from service, updates threshold strip totals,
     * restores or initializes batch filter state, and renders the first page.
     */
    private void loadHighValueBatches() {
        allHighValueBatches = verificationService.fetchHighValueBatches();

        // Compute HV and Referred totals across ALL batches for the threshold strip labels
        int totalHighValue = 0;
        int totalReferred  = 0;
        for (BatchModel batch : allHighValueBatches) {
            totalHighValue += parseHighValueCount(batch);
            totalReferred  += parseReferredCount(batch);
        }

        lblTotalBatchCount.setValue(allHighValueBatches.size() + " Batches");
        lblTotalHighValueCount.setValue(totalHighValue + " HV Cheques");
        lblTotalReferredCount.setValue(totalReferred + " Referred");

        // Always start on page 1 when reloading the batch list
        batchCurrentPage = 1;

        boolean restored = restoreBatchFilterState();
        if (!restored) {
            // First visit this session — default date range to today with no status/search filter
            LocalDate today = LocalDate.now();
            batchFilterDateFrom = today;
            batchFilterDateTo   = today;
            batchStatusFilter   = "ALL";
            batchSearchKeyword  = "";
            saveBatchFilterState();
        }

        // Push restored/default filter values back into the UI controls
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

    /**
     * Maps a status string to the correct combobox index (0 = ALL, 1 = PENDING, 2 = VERIFIED, 3 = INPROGRESS).
     * Returns 0 for null or unrecognized values so the combo never ends up with no selection.
     */
    private int getBatchStatusComboIndex(String statusFilter) {
        switch (statusFilter == null ? "ALL" : statusFilter) {
            case "PENDING":    return 1;
            case "VERIFIED":   return 2;
            case "INPROGRESS": return 3;
            default:           return 0;
        }
    }

    /** Instant search — updates batchSearchKeyword on every keystroke and re-renders the batch list */
    @Listen("onChange = #txtBatchSearch")
    public void onBatchSearchChange() {
        batchSearchKeyword = txtBatchSearch.getValue();
        // Reset to page 1 so user isn't left on a page that no longer exists after narrowing results
        batchCurrentPage = 1;
        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    /**
     * Date From changed — converts to LocalDate, auto-corrects To if it's now before From,
     * refreshes constraints, and reloads the filtered batch list.
     */
    @Listen("onChange = #dteBatchDateFrom")
    public void onBatchDateFromChange() {
        Date selectedDate = dteBatchDateFrom.getValue();
        // Convert java.util.Date from ZK datebox to LocalDate for range comparison
        batchFilterDateFrom = (selectedDate == null)
                ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        // Auto-correct: if new From is after current To, snap To forward to equal From
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

    /**
     * Date To changed — converts to LocalDate, auto-corrects From if it's now after To,
     * refreshes constraints, and reloads the filtered batch list.
     */
    @Listen("onChange = #dteBatchDateTo")
    public void onBatchDateToChange() {
        Date selectedDate = dteBatchDateTo.getValue();
        // Convert java.util.Date from ZK datebox to LocalDate for range comparison
        batchFilterDateTo = (selectedDate == null)
                ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        // Auto-correct: if new To is before current From, snap From back to equal To
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

    /** Status combo changed — reads selected item value and re-renders batch list in-memory (no DB hit) */
    @Listen("onSelect = #cmbBatchStatus")
    public void onBatchStatusChange() {
        Comboitem selectedItem = cmbBatchStatus.getSelectedItem();
        // Fall back to "ALL" if nothing is selected
        batchStatusFilter = (selectedItem != null) ? selectedItem.getValue() : "ALL";
        batchCurrentPage = 1;
        saveBatchFilterState();
        buildFilteredPagedBatchRows();
    }

    /** Clear — resets all batch filters to today's date with no search or status filter */
    @Listen("onClick = #btnClearBatchFilters")
    public void onClearBatchFilters() {
        LocalDate today = LocalDate.now();

        // Remove stale constraints before setting new values — avoids ZK rejecting "today" as out of range
        dteBatchDateFrom.setConstraint((String) null);
        dteBatchDateTo.setConstraint((String) null);

        batchFilterDateFrom = today;
        batchFilterDateTo   = today;

        java.util.Date todayDate = java.util.Date.from(
                today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());

        // Reset all UI controls to cleared/default state
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

    /** Moves to the previous batch page; does nothing if already on page 1 */
    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (batchCurrentPage > 1) {
            batchCurrentPage--;
            buildFilteredPagedBatchRows();
        }
    }

    /** Moves to the next batch page; does nothing if already on the last page */
    @Listen("onClick = #btnBatchPageNext")
    public void onBatchPageNext() {
        List<BatchModel> filteredBatches = getFilteredBatches();
        int totalPages = getTotalPageCount(filteredBatches.size(), BATCH_PAGE_SIZE);
        if (batchCurrentPage < totalPages) {
            batchCurrentPage++;
            buildFilteredPagedBatchRows();
        }
    }

    /**
     * Applies all three batch filters (status, search, date) to allHighValueBatches
     * and returns only rows that pass all three — no DB call.
     */
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

    /** Returns true if batchStatusFilter is ALL or matches the batch's derived display status */
    private boolean matchesBatchStatusFilter(BatchModel batch) {
        if ("ALL".equals(batchStatusFilter)) return true;
        return batchStatusFilter.equals(getBatchDisplayStatus(batch));
    }

    /** Returns true if the batch ID contains the search keyword (case-insensitive); always true if keyword is blank */
    private boolean matchesBatchSearchFilter(BatchModel batch) {
        if (batchSearchKeyword == null || batchSearchKeyword.isBlank()) return true;
        String batchId = batch.getBatchId();
        if (batchId == null) return false;
        return batchId.toLowerCase().contains(batchSearchKeyword.trim().toLowerCase());
    }

    /** Returns true if the batch's created date falls within [batchFilterDateFrom, batchFilterDateTo] inclusive */
    private boolean matchesBatchDateFilter(BatchModel batch) {
        if (batchFilterDateFrom == null && batchFilterDateTo == null) return true;
        if (batch.getCreatedAt() == null) return false;
        LocalDate createdDate = batch.getCreatedAt().toLocalDate();
        if (batchFilterDateFrom != null && createdDate.isBefore(batchFilterDateFrom)) return false;
        if (batchFilterDateTo   != null && createdDate.isAfter(batchFilterDateTo))    return false;
        return true;
    }

    /**
     * Applies filters, calculates the current page slice, clears the grid,
     * renders batch rows for the page, and updates pagination controls and record count label.
     */
    private void buildFilteredPagedBatchRows() {
        List<BatchModel> filteredBatches = getFilteredBatches();
        int totalPages = getTotalPageCount(filteredBatches.size(), BATCH_PAGE_SIZE);
        // Clamp current page so it stays in range if list size shrank after filtering
        if (batchCurrentPage > totalPages) batchCurrentPage = totalPages;

        lblBatchPageInfo.setValue("Page " + batchCurrentPage + " of " + totalPages);
        btnBatchPagePrev.setDisabled(batchCurrentPage <= 1);
        btnBatchPageNext.setDisabled(batchCurrentPage >= totalPages);

        int fromIndex = (batchCurrentPage - 1) * BATCH_PAGE_SIZE;
        int toIndex   = Math.min(fromIndex + BATCH_PAGE_SIZE, filteredBatches.size());
        List<BatchModel> currentPageBatches = filteredBatches.subList(fromIndex, toIndex);

        // Clear existing rows before re-rendering to avoid duplicate entries
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

    /**
     * Builds a single batch grid row with ID, date, cheque counts, derived status badge,
     * and a Process button that opens the cheque list for that batch.
     */
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

        // Derive display status from encoded counts rather than a raw DB status column
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

        // Process button navigates to Screen 2 (cheque list) for this specific batch
        Button processButton = new Button("Process");
        processButton.setSclass("v2-process-btn");
        processButton.addEventListener("onClick", e -> loadChequeList(batch));
        row.appendChild(cell(processButton, "v2-center"));

        return row;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 2 — CHEQUE LIST
    // ════════════════════════════════════════════════════════════════════

    /**
     * Loads cheques for the selected batch, resets all cheque filters to default,
     * and switches the visible view from batch list to cheque list.
     */
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

        // Clear the popup snapshot so it doesn't carry over from a previous batch
        popupFilteredChequeList = null;

        // Reset UI controls to reflect cleared filter state
        txtChequeSearch.setValue("");
        dteChequeDateFrom.setValue(null);
        dteChequeDateTo.setValue(null);

        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();

        // Switch visible screen from batch list to cheque list
        batchListView.setVisible(false);
        chequeListView.setVisible(true);
        // Persist active batch so navigation back can restore the cheque list view
        saveBatchViewState(batch.getBatchId());
    }

    /** Moves to the previous cheque page; does nothing if already on page 1 */
    @Listen("onClick = #btnChequePagePrev")
    public void onChequePagePrev() {
        if (chequeCurrentPage > 1) {
            chequeCurrentPage--;
            buildFilteredPagedChequeRows();
        }
    }

    /** Moves to the next cheque page; does nothing if already on the last page */
    @Listen("onClick = #btnChequePageNext")
    public void onChequePageNext() {
        List<ChequeModel> filteredCheques = getFilteredCheques();
        int totalPages = getTotalPageCount(filteredCheques.size(), CHEQUE_PAGE_SIZE);
        if (chequeCurrentPage < totalPages) {
            chequeCurrentPage++;
            buildFilteredPagedChequeRows();
        }
    }

    /** Toggles the HV type filter chip; second click on active chip turns it off */
    @Listen("onClick = #chipFilterHighValue")
    public void onChipHighValueClick() {
        highValueActive = !highValueActive;
        chequeCurrentPage = 1;
        // Chip counts depend on type selection, so refresh them whenever type toggles change
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    /** Toggles the Referred type filter chip; second click on active chip turns it off */
    @Listen("onClick = #chipFilterReferred")
    public void onChipReferredClick() {
        referredActive = !referredActive;
        chequeCurrentPage = 1;
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    /** Toggles the Pending status chip; no chip counts refresh needed since status chips don't affect type counts */
    @Listen("onClick = #chipStatusPending")
    public void onChipStatusPendingClick() {
        pendingActive = !pendingActive;
        chequeCurrentPage = 1;
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    /** Toggles the Passed status chip */
    @Listen("onClick = #chipStatusPassed")
    public void onChipStatusPassedClick() {
        passedActive = !passedActive;
        chequeCurrentPage = 1;
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    /** Toggles the Rejected status chip */
    @Listen("onClick = #chipStatusRejected")
    public void onChipStatusRejectedClick() {
        rejectedActive = !rejectedActive;
        chequeCurrentPage = 1;
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    /** Instant search on cheque number or payee name — re-renders cheque list on every keystroke */
    @Listen("onChange = #txtChequeSearch")
    public void onChequeSearchChange() {
        chequeSearchKeyword = txtChequeSearch.getValue();
        chequeCurrentPage = 1;
        buildFilteredPagedChequeRows();
    }

    /**
     * Cheque Date From changed — auto-corrects To if needed, refreshes constraints,
     * updates chip counts, and re-renders the cheque list.
     */
    @Listen("onChange = #dteChequeDateFrom")
    public void onChequeDateFromChange() {
        java.util.Date selectedDate = dteChequeDateFrom.getValue();
        chequeFilterDateFrom = (selectedDate == null) ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        // Auto-correct: if new From is after current To, snap To forward to equal From
        if (chequeFilterDateFrom != null && chequeFilterDateTo != null
                && chequeFilterDateFrom.isAfter(chequeFilterDateTo)) {
            chequeFilterDateTo = chequeFilterDateFrom;
            dteChequeDateTo.setValue(toUtilDate(chequeFilterDateTo));
        }
        updateDateRangeConstraints(dteChequeDateFrom, dteChequeDateTo,
                chequeFilterDateFrom, chequeFilterDateTo);

        chequeCurrentPage = 1;
        // Chip counts include date filter — refresh so counts stay in sync with the active range
        refreshChequeFilterChips();
        updateChequeChipStyles();
        buildFilteredPagedChequeRows();
    }

    /**
     * Cheque Date To changed — auto-corrects From if needed, refreshes constraints,
     * updates chip counts, and re-renders the cheque list.
     */
    @Listen("onChange = #dteChequeDateTo")
    public void onChequeDateToChange() {
        java.util.Date selectedDate = dteChequeDateTo.getValue();
        chequeFilterDateTo = (selectedDate == null) ? null
                : selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        // Auto-correct: if new To is before current From, snap From back to equal To
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

    /** Clear — resets all cheque filters, chip toggles, search, and date range back to defaults */
    @Listen("onClick = #btnClearChequeFilters")
    public void onClearChequeFilters() {
        // Reset all toggle flags so no type or status filter is active
        highValueActive  = false;
        referredActive   = false;
        pendingActive    = false;
        passedActive     = false;
        rejectedActive   = false;
        chequeSearchKeyword  = "";
        chequeFilterDateFrom = null;
        chequeFilterDateTo   = null;
        chequeCurrentPage    = 1;

        // Remove stale constraints before clearing datebox values
        dteChequeDateFrom.setConstraint((String) null);
        dteChequeDateTo.setConstraint((String) null);

        // Reset all UI controls to cleared state
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

    /**
     * Walks currentBatchChequeList applying type, status, search, and date filters.
     * Returns empty list if currentBatchChequeList is null (no batch loaded yet).
     */
    private List<ChequeModel> getFilteredCheques() {
        if (currentBatchChequeList == null) return new ArrayList<>();

        List<ChequeModel> result = new ArrayList<>();

        for (ChequeModel cheque : currentBatchChequeList) {

            // Type group filter (HV / RF) — skip if any type is active and this cheque doesn't match
            boolean anyTypeActive = highValueActive || referredActive;
            if (anyTypeActive) {
                boolean isReferred = cheque.isReferred();
                // HV = not referred; both can be active at once (OR within group)
                boolean typeMatches = (highValueActive && !isReferred) || (referredActive && isReferred);
                if (!typeMatches) continue;
            }

            // Status group filter — OR within group; skipped entirely if no status chip is active
            boolean anyStatusActive = pendingActive || passedActive || rejectedActive;
            if (anyStatusActive) {
                ChequeStatus chequeStatus = ChequeStatus.fromDb(cheque.getVerStatus());
                boolean isPassed   = chequeStatus == ChequeStatus.VERIFIED;
                boolean isRejected = chequeStatus == ChequeStatus.REJECTED;
                // Pending = neither passed nor rejected (covers all intermediate statuses)
                boolean isPending  = !isPassed && !isRejected;
                boolean statusMatches = (pendingActive  && isPending)
                                     || (passedActive   && isPassed)
                                     || (rejectedActive && isRejected);
                if (!statusMatches) continue;
            }

            // Search filter — matches if cheque number OR payee name contains the keyword
            if (chequeSearchKeyword != null && !chequeSearchKeyword.isBlank()) {
                String keyword   = chequeSearchKeyword.trim().toLowerCase();
                String chequeNo  = (cheque.getChequeNo()  != null) ? cheque.getChequeNo().toLowerCase()  : "";
                String payeeName = (cheque.getPayeeName() != null) ? cheque.getPayeeName().toLowerCase() : "";
                if (!chequeNo.contains(keyword) && !payeeName.contains(keyword)) continue;
            }

            // Cheque date range filter — parses "dd/MM/yyyy" string; skips cheque if date is unparseable
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

    /** Parses chequeDate String "dd/MM/yyyy" → LocalDate; returns null if the string is blank or unparseable */
    private LocalDate parseChequeDate(String dateString) {
        if (dateString == null || dateString.isBlank()) return null;
        try {
            return LocalDate.parse(dateString.trim(),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies filters, calculates the current page slice, clears the grid,
     * renders cheque rows for the page, and updates pagination controls and record count label.
     */
    private void buildFilteredPagedChequeRows() {
        List<ChequeModel> filteredCheques = getFilteredCheques();
        int totalPages = getTotalPageCount(filteredCheques.size(), CHEQUE_PAGE_SIZE);
        // Clamp current page so it stays in range if list size shrank
        if (chequeCurrentPage > totalPages) chequeCurrentPage = totalPages;

        lblChequePageInfo.setValue("Page " + chequeCurrentPage + " of " + totalPages);
        btnChequePagePrev.setDisabled(chequeCurrentPage <= 1);
        btnChequePageNext.setDisabled(chequeCurrentPage >= totalPages);

        int fromIndex = (chequeCurrentPage - 1) * CHEQUE_PAGE_SIZE;
        int toIndex   = Math.min(fromIndex + CHEQUE_PAGE_SIZE, filteredCheques.size());
        List<ChequeModel> currentPageCheques = filteredCheques.subList(fromIndex, toIndex);

        // Clear old rows before rebuilding to avoid duplicates
        chequeGridRows.getChildren().clear();
        chequeEmptyState.setVisible(currentPageCheques.isEmpty());

        // Serial number starts from the overall position in filtered list, not 1 per page
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

    /**
     * Recounts cheques to update chip labels.
     * HV/RF totals are never filtered; status counts respect the active type toggle and date range.
     */
    private void refreshChequeFilterChips() {
        if (currentBatchChequeList == null) return;

        int hvCount = 0, rfCount = 0;
        int pendingCount = 0, passedCount = 0, rejectedCount = 0;

        for (ChequeModel cheque : currentBatchChequeList) {
            boolean isReferred = cheque.isReferred();

            // Apply date filter to chip counts so counts match what the filter actually shows
            if (chequeFilterDateFrom != null || chequeFilterDateTo != null) {
                LocalDate chequeDate = parseChequeDate(cheque.getChequeDate());
                if (chequeDate == null) continue;
                if (chequeFilterDateFrom != null && chequeDate.isBefore(chequeFilterDateFrom)) continue;
                if (chequeFilterDateTo   != null && chequeDate.isAfter(chequeFilterDateTo))    continue;
            }

            // HV/RF counts are always the full totals — type chips always show grand total
            if (isReferred) rfCount++;
            else            hvCount++;

            // Status counts are scoped to whichever type chips are currently active
            boolean anyTypeActive = highValueActive || referredActive;
            boolean inScope;
            if (!anyTypeActive) {
                // No type filter active — all cheques count toward status totals
                inScope = true;
            } else {
                // Only count status for cheques that match the active type chip(s)
                inScope = (highValueActive && !isReferred) || (referredActive && isReferred);
            }

            if (inScope) {
                ChequeStatus chequeStatus = ChequeStatus.fromDb(cheque.getVerStatus());
                if      (chequeStatus == ChequeStatus.VERIFIED) passedCount++;
                else if (chequeStatus == ChequeStatus.REJECTED) rejectedCount++;
                else                                             pendingCount++;
            }
        }

        // Push updated counts into chip labels
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

    /** Applies or removes the chip-filter-active CSS class on each chip to reflect its current toggle state */
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

    /**
     * Builds a single cheque grid row with serial number, cheque details, type flag badge,
     * status badge, and an Open button that launches the cheque detail popup.
     */
    private Row buildChequeRow(int serialNumber, ChequeModel cheque) {
        Row row = new Row();
        row.appendChild(cell(new Label(String.valueOf(serialNumber)), "v2-center"));
        row.appendChild(cell(new Label(safeValue(cheque.getChequeNo())), ""));
        row.appendChild(cell(new Label(safeValue(cheque.getPayeeName())), ""));
        row.appendChild(cell(new Label(formatAmount(cheque.getAmount())), "v2-right"));
        row.appendChild(cell(new Label(safeValue(cheque.getChequeDate())), "v2-center"));
        // buildFlagLabel returns "⇄ REF" or "⚑ HV" label with appropriate CSS
        row.appendChild(cell(buildFlagLabel(cheque.isReferred()), "v2-center"));

        String verStatus = (cheque.getVerStatus() != null) ? cheque.getVerStatus() : "PENDING";
        // Normalize internal pipeline statuses (V1_PENDING, V2_PENDING, SUBMITTED) to display as "PENDING"
        String verStatusDisplay = ("V1_PENDING".equalsIgnoreCase(verStatus)
                || "V2_PENDING".equalsIgnoreCase(verStatus)
                || "SUBMITTED".equalsIgnoreCase(verStatus)) ? "PENDING" : verStatus;

        Label verStatusLabel = new Label(verStatusDisplay);
        verStatusLabel.setSclass("v2-status-badge v2-status-" + safeStatusCss(verStatusDisplay));
        row.appendChild(cell(verStatusLabel, "v2-center"));

        // Open button captures the cheque reference for the lambda; opens full detail popup
        Button openButton = new Button("Open");
        openButton.setSclass("v2-open-btn");
        openButton.addEventListener("onClick", e -> openChequeDetailPopup(cheque));
        row.appendChild(cell(openButton, "v2-center"));

        return row;
    }

    /** Returns a styled "⇄ REF" label for referred cheques or "⚑ HV" label for high-value cheques */
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

    /**
     * Opens the cheque detail popup for the given cheque.
     * Snapshots the current filtered list into popupFilteredChequeList so that
     * Prev / Next and findNextPendingInList() stay within the filtered set.
     */
    private void openChequeDetailPopup(ChequeModel cheque) {
        // Take a snapshot of the filtered list at open time — navigation uses this, not the live filter
        popupFilteredChequeList = getFilteredCheques();

        popupCurrentIndex = popupFilteredChequeList.indexOf(cheque);
        // Guard: if cheque is somehow not in the filtered list, start at index 0
        if (popupCurrentIndex < 0) popupCurrentIndex = 0;

        // Always start each popup on the front image
        resetChequeImageToFront();
        renderPopup(popupCurrentIndex);
        chequeDetailPopup.setVisible(true);
        // doModal() blocks interaction with the rest of the page until popup is closed
        chequeDetailPopup.doModal();
    }

    /**
     * Returns the list the popup should navigate within.
     * Falls back to the full batch list if the snapshot is null (popup opened without filters).
     */
    private List<ChequeModel> getActivePopupList() {
        return (popupFilteredChequeList != null && !popupFilteredChequeList.isEmpty())
               ? popupFilteredChequeList
               : currentBatchChequeList;
    }

    /**
     * Renders a cheque at the given index inside getActivePopupList().
     * Populates all data fields, loads CBS info, sets image panels,
     * and updates navigation counter and Prev/Next button states.
     */
    private void renderPopup(int index) {
        List<ChequeModel> list = getActivePopupList();
        if (list == null || list.isEmpty()) return;

        ChequeModel cheque = list.get(index);

        lblPopupChequeTitle.setValue("Cheque  #" + safeValue(cheque.getChequeNo()));

        // Set type badge: "REFERRED" (blue) or "HIGH VALUE" (orange) depending on cheque type
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

        // Split 9-digit MICR sort code into City/Bank/Branch 3-digit parts for individual labels
        String sortCode    = safeValue(cheque.getSortCode());
        String[] micrParts = splitMicrSortCode(sortCode);
        lblChequeNumber.setValue(safeValue(cheque.getChequeNo()));
        lblCityCode.setValue(micrParts[0]);
        lblBankCode.setValue(micrParts[1]);
        lblBranchCode.setValue(micrParts[2]);
        lblTransactionCode.setValue(safeValue(cheque.getTransactionCode()));

        // Populate cheque data section from ChequeModel fields
        lblPayeeName.setValue(safeValue(cheque.getPayeeName()));
        lblChequeAmount.setValue(formatAmount(cheque.getAmount()));
        lblAccountNumber.setValue(safeValue(cheque.getPayeeAccountNo()));
        lblChequeDate.setValue(safeValue(cheque.getChequeDate()));
        lblAmountInWords.setValue(safeValue(cheque.getAmountInWords()));

        // CBS live lookup using payee_account_no (correct CBS key)
        String accountNo = cheque.getPayeeAccountNo() != null ? cheque.getPayeeAccountNo().trim() : null;
        if (accountNo != null && !accountNo.isBlank()) {
            // Lookup returns a JsonNode with account fields from the CBS (Firestore) document
            com.fasterxml.jackson.databind.JsonNode cbsFields = cbsService.lookupAccountFields(accountNo);
            if (cbsFields != null && !cbsFields.isMissingNode()) {
                String cbsPayeeName = cbsFields.path("accountHolderName").path("stringValue").asText(null);
                boolean isActive    = cbsFields.path("active").path("booleanValue").asBoolean(false);

                lblCbsPayeeName.setValue(cbsPayeeName != null ? cbsPayeeName : "—");
                lblCbsAccountStatus.setValue(isActive ? "Active" : "Inactive");
                lblCbsNewAccount.setValue(cbsService.getIsNewAccount(accountNo));

                // Compare CBS account holder name with cheque payee name (case-insensitive)
                String payeeName = cheque.getPayeeName();
                if (cbsPayeeName != null && payeeName != null) {
                    boolean nameMatches = cbsPayeeName.trim().equalsIgnoreCase(payeeName.trim());
                    lblCbsPayeeMatch.setValue(nameMatches ? "Match" : "Mismatch");
                    // Green CSS for match, red for mismatch — key visual signal for verifier
                    lblCbsPayeeMatch.setSclass(nameMatches ? "cbs-match-ok" : "cbs-match-fail");
                } else {
                    lblCbsPayeeMatch.setValue("—");
                    lblCbsPayeeMatch.setSclass("");
                }
            } else {
                // CBS lookup returned no result — account number not found in CBS
                lblCbsPayeeName.setValue("—");
                lblCbsAccountStatus.setValue("Not found");
                lblCbsNewAccount.setValue("—");
                lblCbsPayeeMatch.setValue("—");
                lblCbsPayeeMatch.setSclass("");
            }
        } else {
            // No account number on the cheque — all CBS fields show placeholder dashes
            lblCbsPayeeName.setValue("—");
            lblCbsAccountStatus.setValue("—");
            lblCbsNewAccount.setValue("—");
            lblCbsPayeeMatch.setValue("—");
            lblCbsPayeeMatch.setSclass("");
        }

        // Disable Accept/Reject if cheque is already verified or rejected — prevents re-actioning
        ChequeStatus currentStatus = ChequeStatus.fromDb(cheque.getVerStatus());
        boolean alreadyActioned    = currentStatus == ChequeStatus.VERIFIED
                                  || currentStatus == ChequeStatus.REJECTED;
        btnAcceptCheque.setDisabled(alreadyActioned);
        btnRejectCheque.setDisabled(alreadyActioned);

        // Clear remarks box and reset its style for each new cheque
        txtVerificationRemarks.setValue("");
        txtVerificationRemarks.setSclass("v2-remarks-box");

        // Encode front image bytes as Base64 data URI — show placeholder div if no image available
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

        // Encode rear image bytes as Base64 data URI — show placeholder div if no image available
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

        // Update popup counter (e.g. "3 / 12") and disable Prev/Next at list boundaries
        int totalInList = list.size();
        lblPopupChequeCounter.setValue((index + 1) + " / " + totalInList);
        btnPopupPrev.setDisabled(index == 0);
        btnPopupNext.setDisabled(index == totalInList - 1);
    }

    /** Toggles between front and back cheque image panels and updates the flip button label */
    private void flipChequeImage() {
        isShowingFrontImage = !isShowingFrontImage;
        imgPanelFront.setVisible(isShowingFrontImage);
        imgPanelBack.setVisible(!isShowingFrontImage);
        btnFlipChequeImage.setLabel(isShowingFrontImage ? "Show Back" : "Show Front");
    }

    /** Resets image to front side whenever a new cheque is opened so popup always starts on front */
    private void resetChequeImageToFront() {
        isShowingFrontImage = true;
        imgPanelFront.setVisible(true);
        imgPanelBack.setVisible(false);
        btnFlipChequeImage.setLabel("Show Back");
    }

    // ════════════════════════════════════════════════════════════════════
    //  VERIFICATION ACTIONS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Accept action — auto-fills remarks if blank, then calls performVerification with VERIFIED status.
     */
    private void onAcceptChequeClick() {
        String verifierUsername = getVerifierUsername();
        String remarks = txtVerificationRemarks.getValue();
        // Auto-fill remarks for accept so the DB record is never saved with blank remarks
        if (remarks == null || remarks.isBlank()) {
            remarks = "Accepted by " + verifierUsername;
            txtVerificationRemarks.setValue(remarks);
        }
        performVerification(ChequeStatus.VERIFIED.db(), remarks, verifierUsername);
    }

    /**
     * Reject action — enforces that remarks are not blank before proceeding;
     * highlights the remarks box with an error style if empty.
     */
    private void onRejectChequeClick() {
        String remarks = txtVerificationRemarks.getValue();
        if (remarks == null || remarks.isBlank()) {
            // Highlight remarks box in red and focus it to guide the verifier
            txtVerificationRemarks.setSclass("v2-remarks-box v2-remarks-box-error");
            txtVerificationRemarks.focus();
            return;
        }
        // Clear error style once valid remarks are present
        txtVerificationRemarks.setSclass("v2-remarks-box");

        String verifierUsername = getVerifierUsername();
        performVerification(ChequeStatus.REJECTED.db(), remarks, verifierUsername);
    }

    /**
     * Saves the verification result to DB, updates the cheque model in-memory,
     * re-evaluates batch status, refreshes the cheque list, and navigates to the next pending cheque.
     */
    private void performVerification(String action, String remarks, String verifierUsername) {
        List<ChequeModel> list = getActivePopupList();
        if (list == null || list.isEmpty()) return;

        ChequeModel cheque   = list.get(popupCurrentIndex);
        long        chequeId = Long.parseLong(cheque.getId());

        // Persist the verification decision to DB via service
        verificationService.submitHighValueChequeVerification(chequeId, action, verifierUsername, remarks);

        // Update the in-memory model immediately so the UI reflects the new status without a DB reload
        ChequeStatus resolvedChequeStatus = ChequeStatus.fromDb(action);
        String updatedVerificationStatus = (resolvedChequeStatus == ChequeStatus.VERIFIED)
                ? ChequeStatus.VERIFIED.db()
                : ChequeStatus.REJECTED.db();
        cheque.setVerStatus(updatedVerificationStatus);

        // Re-evaluate batch-level status (PENDING/IN PROGRESS/VERIFIED) based on updated cheque counts
        verificationService.evaluateAndUpdateBatchVerificationStatus(cheque.getBatchId());

        // Refresh chip counts and cheque list so the new status is visible immediately
        refreshChequeFilterChips();
        buildFilteredPagedChequeRows();

        // Re-snapshot filtered list after status change so Prev/Next stays consistent
        popupFilteredChequeList = getFilteredCheques();

        // Auto-advance to the next pending cheque; stay on current if none found
        int nextIndex = findNextPendingInList(popupCurrentIndex, popupFilteredChequeList);
        popupCurrentIndex = (nextIndex >= 0) ? nextIndex : popupCurrentIndex;
        renderPopup(popupCurrentIndex);
    }

    /**
     * Searches forward then backward from currentIndex for the next cheque that is neither
     * VERIFIED nor REJECTED. Returns -1 if no pending cheque exists in the list.
     */
    private int findNextPendingInList(int currentIndex, List<ChequeModel> list) {
        if (list == null) return -1;
        // Search forward first (natural flow for a verifier working top-to-bottom)
        for (int i = currentIndex + 1; i < list.size(); i++) {
            ChequeStatus status = ChequeStatus.fromDb(list.get(i).getVerStatus());
            if (status != ChequeStatus.VERIFIED && status != ChequeStatus.REJECTED) return i;
        }
        // Fall back to searching backward if no pending cheque exists after current position
        for (int i = currentIndex - 1; i >= 0; i--) {
            ChequeStatus status = ChequeStatus.fromDb(list.get(i).getVerStatus());
            if (status != ChequeStatus.VERIFIED && status != ChequeStatus.REJECTED) return i;
        }
        return -1;
    }

    /**
     * Back button — hides cheque list, shows batch list, clears all cheque state,
     * and reloads the batch list fresh.
     */
    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        chequeListView.setVisible(false);
        batchListView.setVisible(true);
        // Null out both cheque lists so stale data from the previous batch is not referenced
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

        // Update session to reflect that user is now on the batch list view
        clearBatchViewState();
        loadHighValueBatches();
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    /** Returns the currently logged-in username from session; falls back to "Unknown" if session has no user */
    private String getVerifierUsername() {
        String username = SecurityUtil.getCurrentUserId();
        return (username == null || username.isBlank()) ? "Unknown" : username;
    }

    /**
     * Sets ZK datebox constraints so the To box can't be before From and vice versa.
     * Must be called after either date changes to keep the pair in a consistent valid state.
     */
    private void updateDateRangeConstraints(Datebox fromBox, Datebox toBox,
                                            LocalDate fromDate, LocalDate toDate) {
        // "after YYYYMMDD" prevents user picking a To date earlier than current From
        if (toBox != null) {
            toBox.setConstraint(fromDate != null ? "after " + formatYyyyMmDd(fromDate) : (String) null);
        }
        // "before YYYYMMDD" prevents user picking a From date later than current To
        if (fromBox != null) {
            fromBox.setConstraint(toDate != null ? "before " + formatYyyyMmDd(toDate) : (String) null);
        }
    }

    /** Formats a LocalDate as yyyyMMdd string — the format ZK datebox constraint strings require */
    private String formatYyyyMmDd(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /** Converts LocalDate to java.util.Date at midnight system time — required by ZK Datebox.setValue() */
    private Date toUtilDate(LocalDate localDate) {
        return (localDate == null) ? null
                : Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Splits a 9-digit MICR sort code into [cityCode, bankCode, branchCode] 3-digit parts.
     * Returns ["—","—","—"] for null, blank, or codes shorter than 3 digits.
     */
    private String[] splitMicrSortCode(String sortCode) {
        if (sortCode == null || sortCode.isBlank() || "——".equals(sortCode) || "0".equals(sortCode)) {
            return new String[]{"—", "—", "—"};
        }
        String code = sortCode.trim();
        // Full 9-digit code — split into 3 equal parts
        if (code.length() >= 9) return new String[]{code.substring(0, 3), code.substring(3, 6), code.substring(6, 9)};
        // 6-digit code — branch part missing
        if (code.length() >= 6) return new String[]{code.substring(0, 3), code.substring(3, 6), "—"};
        // Short code — only city part available
        return new String[]{code, "—", "—"};
    }

    /** Returns total page count; always at least 1 even when the list is empty */
    private int getTotalPageCount(int listSize, int pageSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / pageSize);
    }

    // These four methods decode the pipe-delimited encoded value stored in presentingBankId
    // Format: "hvCount|pendingCount|processedCount|referredCount"
    private int parseHighValueCount(BatchModel batch)  { return parseEncodedPart(batch.getPresentingBankId(), 0); }
    private int parsePendingCount(BatchModel batch)    { return parseEncodedPart(batch.getPresentingBankId(), 1); }
    private int parseProcessedCount(BatchModel batch)  { return parseEncodedPart(batch.getPresentingBankId(), 2); }
    private int parseReferredCount(BatchModel batch)   { return parseEncodedPart(batch.getPresentingBankId(), 3); }

    /**
     * Derives a human-readable batch display status from encoded cheque counts.
     * Returns PENDING if nothing processed, INPROGRESS if partially done, VERIFIED when all done.
     */
    private String getBatchDisplayStatus(BatchModel batch) {
        int hvCount        = parseHighValueCount(batch);
        int processedCount = parseProcessedCount(batch);
        if (processedCount == 0)      return "PENDING";
        if (processedCount < hvCount) return "INPROGRESS";
        return "VERIFIED";
    }

    /**
     * Parses one integer part from the pipe-delimited encoded string in presentingBankId.
     * Returns 0 if the string is null, malformed, or doesn't have enough parts.
     */
    private int parseEncodedPart(String encodedValue, int partIndex) {
        if (encodedValue == null || encodedValue.isEmpty()) return 0;
        try {
            String[] parts = encodedValue.split("\\|");
            return parts.length > partIndex ? Integer.parseInt(parts[partIndex]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Wraps a component inside a ZK Cell with an optional CSS sclass — used when building grid rows */
    private Cell cell(Component child, String sclass) {
        Cell c = new Cell();
        if (sclass != null && !sclass.isEmpty()) c.setSclass(sclass);
        c.appendChild(child);
        return c;
    }

    /** Returns "—" if value is null or blank — used for all display labels to avoid showing empty cells */
    private String safeValue(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

    /** Returns a lowercased status string safe for CSS class suffix use; falls back to "pending" if blank */
    private String safeStatusCss(String status) {
        return (status == null || status.isBlank()) ? "pending" : status.toLowerCase();
    }

    /** Formats a BigDecimal amount in Indian number format as "Rs. X,XX,XXX.XX"; returns "——" for null */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "——";
        NumberFormat formatter = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
        return "Rs. " + formatter.format(amount);
    }

    /** Formats a LocalDateTime using BATCH_DATE_FORMATTER (dd-MMM-yyyy); returns "—" for null */
    private String formatBatchDate(java.time.LocalDateTime dateTime) {
        if (dateTime == null) return "—";
        return dateTime.format(BATCH_DATE_FORMATTER);
    }
}