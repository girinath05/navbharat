/*
 * ============================================================
 *  Project : Navbharat CTS Outward
 *  File    : OutwardDashboardComposer.java
 *  Package : com.cts.outward.composer
 *  Desc    : Composer for outwardDashboard.zul
 *
 *            Batch List filter bar (V2-style, instant filtering):
 *              - Search Batch ID   : instant textbox, filters as you type
 *              - Date range From/To: re-loads batches for the selected
 *                                    range (defaults to today on load
 *                                    and on Clear)
 *              - Status combo      : filters in-memory, no DB hit
 *              - Clear button      : resets all filters back to "today"
 *
 *            Stat cards now reflect the FILTERED batch list (live),
 *            computed in-memory from BatchModel.getStatus():
 *              Card 1 — Total Batches        (filtered count)
 *              Card 2 — Verification Stage   (ReadyForVerification,
 *                                             VerificationInProgress)
 *              Card 3 — Verified Batches     (Verified)
 *              Card 4 — Dispatched Batches   (CxfGenerated, Dispatched)
 *
 *            Pagination: manual Prev / Next + "Page X of Y" label,
 *            default 5 batches per page (same pattern as
 *            Verification2Composer's batch list pagination), now
 *            user-configurable via a plain "Rows per page" number box
 *            (typed value, clamped to [MIN_PAGE_SIZE, MAX_PAGE_SIZE]).
 * ============================================================
 */
package com.cts.outward.composer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;

import com.cts.composer.DashboardComposer;
import com.cts.outward.dao.OutwardDashboardDAOImpl;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.model.BatchModel;
import com.cts.outward.service.OutwardDashboardService;
import com.cts.outward.service.OutwardDashboardServiceImpl;
import com.cts.util.SecurityUtil;

public class OutwardDashboardComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(OutwardDashboardComposer.class.getName());
    private static final DateTimeFormatter BATCH_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Default batches shown per page in the batch list — user can override
    // via the "Rows per page" combobox: pick a preset (5/10/25/50) or type
    // any custom number, clamped to [MIN_PAGE_SIZE, MAX_PAGE_SIZE]
    private static final int DEFAULT_PAGE_SIZE = 5;
    private static final int MIN_PAGE_SIZE     = 1;
    private static final int MAX_PAGE_SIZE     = 200;

    // ── Session keys — persist filter state across page navigation ───────
    private static final String SESS_SEARCH_TEXT   = "od_searchText";
    private static final String SESS_STATUS_FILTER = "od_statusFilter";
    private static final String SESS_DATE_FROM     = "od_dateFrom";
    private static final String SESS_DATE_TO       = "od_dateTo";
    private static final String SESS_CURRENT_PAGE  = "od_currentPage";
    private static final String SESS_PAGE_SIZE     = "od_pageSize";

    // ── Service ──────────────────────────────────────────────────────────
    private final OutwardDashboardService dashboardService =
            new OutwardDashboardServiceImpl(new OutwardDashboardDAOImpl());

    // ── Stat card labels ──────────────────────────────────────────────────
    @Wire private Label lblTotalBatches;          // Card 1 — total (filtered)
    @Wire private Label lblVerificationBatches;   // Card 2 — in verification
    @Wire private Label lblVerifiedBatches;       // Card 3 — verified batches
    @Wire private Label lblDispatchedBatches;     // Card 4 — dispatched batches

    // ── Filter bar ────────────────────────────────────────────────────────
    @Wire private Textbox  txtBatchIdSearch;
    @Wire private Combobox cmbBatchStatus;
    @Wire private Datebox  dteBatchDateFrom;
    @Wire private Datebox  dteBatchDateTo;
    @Wire private Button   btnClearBatchFilters;

    // ── Batch list / pagination ─────────────────────────────────────────────
    @Wire private Label    lblBatchCount;
    @Wire private Listbox  batchListbox;
    @Wire private Button   btnBatchPagePrev;
    @Wire private Label    lblBatchPageInfo;
    @Wire private Button   btnBatchPageNext;
    @Wire private Intbox   intBatchPageSize;

    // ── In-memory data + filter state ───────────────────────────────────────
    private List<BatchModel> allBatchList      = new ArrayList<>();
    private List<BatchModel> filteredBatchList = new ArrayList<>();

    private String    batchSearchKeyword  = "";
    private String    batchStatusFilter   = "All";
    private LocalDate batchFilterDateFrom;
    private LocalDate batchFilterDateTo;
    private int       batchCurrentPage    = 1;
    private int       batchPageSize       = DEFAULT_PAGE_SIZE;

    // ════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        guardSession();

        restoreFiltersFromSession();

        if (txtBatchIdSearch != null) txtBatchIdSearch.setValue(batchSearchKeyword);
        if (cmbBatchStatus   != null) selectComboByValue(cmbBatchStatus, batchStatusFilter);
        if (dteBatchDateFrom != null) dteBatchDateFrom.setValue(batchFilterDateFrom != null ? toUtilDate(batchFilterDateFrom) : null);
        if (dteBatchDateTo   != null) dteBatchDateTo.setValue(batchFilterDateTo     != null ? toUtilDate(batchFilterDateTo)   : null);
        updateDateConstraints();
        if (intBatchPageSize != null) intBatchPageSize.setValue(batchPageSize);

        Events.postEvent("onLater", comp, null);
    }

    @Listen("onLater = #outwardDashboardRoot")
    public void onLater() {
        reloadBatchesForRange();
        applyFiltersPreservingPage();
        if (batchListbox != null) {
            batchListbox.setWidth("100%");
            batchListbox.invalidate();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTER LISTENERS
    // ════════════════════════════════════════════════════════════════════

    /** Instant search — fires on every keystroke (textbox has instant="true") */
    @Listen("onChange = #txtBatchIdSearch")
    public void onBatchSearchChange() {
        batchSearchKeyword = (txtBatchIdSearch != null && txtBatchIdSearch.getValue() != null)
                ? txtBatchIdSearch.getValue() : "";
        saveFiltersToSession();
        applyFilters();
    }

    /**
     * Date range changed -> re-fetch batches for the new range, then re-filter.
     * Also keeps From/To consistent: if the newly picked From lands after the
     * current To, To is pulled forward to match (rather than leaving an
     * invalid "From after To" combination sitting in the filter), and the To
     * box's constraint is refreshed so it can't be set earlier than From.
     */
    @Listen("onChange = #dteBatchDateFrom")
    public void onBatchDateFromChange() {
        Date inputDate = dteBatchDateFrom != null ? dteBatchDateFrom.getValue() : null;
        batchFilterDateFrom = (inputDate == null) ? null : toLocalDateFromUtil(inputDate);

        if (batchFilterDateFrom != null && batchFilterDateTo != null && batchFilterDateFrom.isAfter(batchFilterDateTo)) {
            batchFilterDateTo = batchFilterDateFrom;
            if (dteBatchDateTo != null) dteBatchDateTo.setValue(toUtilDate(batchFilterDateTo));
        }
        updateDateConstraints();

        saveFiltersToSession();
        reloadBatchesForRange();
        applyFilters();
    }

    /**
     * Mirror of onDateFromChange: if the newly picked To lands before the
     * current From, From is pulled back to match, and From's constraint is
     * refreshed so it can't be set later than To.
     */
    @Listen("onChange = #dteBatchDateTo")
    public void onBatchDateToChange() {
        Date inputDate = dteBatchDateTo != null ? dteBatchDateTo.getValue() : null;
        batchFilterDateTo = (inputDate == null) ? null : toLocalDateFromUtil(inputDate);

        if (batchFilterDateFrom != null && batchFilterDateTo != null && batchFilterDateTo.isBefore(batchFilterDateFrom)) {
            batchFilterDateFrom = batchFilterDateTo;
            if (dteBatchDateFrom != null) dteBatchDateFrom.setValue(toUtilDate(batchFilterDateFrom));
        }
        updateDateConstraints();

        saveFiltersToSession();
        reloadBatchesForRange();
        applyFilters();
    }

    /** Status dropdown — filters in-memory only, no DB hit */
    @Listen("onSelect = #cmbBatchStatus")
    public void onBatchStatusChange() {
        Comboitem selectedComboItem = cmbBatchStatus != null ? cmbBatchStatus.getSelectedItem() : null;
        Object selectedStatusValue = (selectedComboItem != null) ? selectedComboItem.getValue() : null;
        batchStatusFilter = (selectedStatusValue != null) ? selectedStatusValue.toString() : "All";
        saveFiltersToSession();
        applyFilters();
    }

    /** Clear — resets search/status/date range back to "today" */
    @Listen("onClick = #btnClearBatchFilters")
    public void onClearBatchFilters() {
        if (txtBatchIdSearch != null) txtBatchIdSearch.setValue("");
        if (cmbBatchStatus   != null) cmbBatchStatus.setSelectedIndex(0); // "All"

        LocalDate today = LocalDate.now();

        // Drop any constraint left over from the previous range *before*
        // resetting the values — otherwise resetting to "today" can itself
        // get rejected by a stale "before <old To date>" / "after <old From
        // date>" bound that hasn't been refreshed yet.
        if (dteBatchDateFrom != null) dteBatchDateFrom.setConstraint((String) null);
        if (dteBatchDateTo   != null) dteBatchDateTo.setConstraint((String) null);

        if (dteBatchDateFrom != null) dteBatchDateFrom.setValue(toUtilDate(today));
        if (dteBatchDateTo   != null) dteBatchDateTo.setValue(toUtilDate(today));

        batchSearchKeyword  = "";
        batchStatusFilter   = "All";
        batchFilterDateFrom = today;
        batchFilterDateTo   = today;
        updateDateConstraints();

        saveFiltersToSession();
        reloadBatchesForRange();
        applyFilters();
    }

    /**
     * Rows-per-page number box changed (fires on blur — losing focus by
     * clicking elsewhere). Clamps to [MIN_PAGE_SIZE, MAX_PAGE_SIZE]; an
     * empty/cleared box falls back to the current page size rather than
     * resetting to the default, so a stray clear doesn't surprise the user.
     */
    @Listen("onChange = #intBatchPageSize")
    public void onBatchPageSizeChange() {
        applyPageSizeFromBox();
    }

    @Listen("onOK = #intBatchPageSize")
    public void onBatchPageSizeEnter() {
        applyPageSizeFromBox();
    }

    private void applyPageSizeFromBox() {
        Integer rawPageSize = (intBatchPageSize != null) ? intBatchPageSize.getValue() : null;
        applyPageSize(rawPageSize);
    }

    /**
     * Validates/clamps the raw page size, applies it, resets to page 1,
     * persists to session, snaps the box back to the value actually
     * applied (in case it was clamped), then re-renders.
     */
    private void applyPageSize(Integer rawPageSize) {
        int clampedPageSize = (rawPageSize != null)
                ? Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, rawPageSize))
                : batchPageSize; // fallback: keep current size on empty input

        batchPageSize   = clampedPageSize;
        batchCurrentPage = 1;
        saveFiltersToSession();

        // Reflect the (possibly clamped) value back into the box
        if (intBatchPageSize != null) intBatchPageSize.setValue(batchPageSize);

        renderPage();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAGINATION LISTENERS (manual Prev / Next — same pattern as V2)
    // ════════════════════════════════════════════════════════════════════

    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (batchCurrentPage > 1) {
            batchCurrentPage--;
            Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, batchCurrentPage);
            renderPage();
        }
    }

    @Listen("onClick = #btnBatchPageNext")
    public void onBatchPageNext() {
        int totalPages = getTotalPages(filteredBatchList.size());
        if (batchCurrentPage < totalPages) {
            batchCurrentPage++;
            Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, batchCurrentPage);
            renderPage();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  DATA LOADING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Re-loads {@code allBatches} for the currently selected [dateFrom, dateTo]
     * range. Re-uses the existing single-date DAO method for each day in the
     * range (no DAO/Service changes needed). If no range is set, defaults to
     * today.
     */
    private void reloadBatchesForRange() {
        List<BatchModel> combinedBatchList = new ArrayList<>();

        LocalDate rangeStartDate = batchFilterDateFrom;
        LocalDate rangeEndDate   = batchFilterDateTo;

        if (rangeStartDate == null && rangeEndDate == null) {
            combinedBatchList = dashboardService.getBatchesFilteredAsModels(null, null, LocalDate.now());
        } else {
            if (rangeStartDate == null) rangeStartDate = rangeEndDate;
            if (rangeEndDate   == null) rangeEndDate   = rangeStartDate;
            if (rangeStartDate.isAfter(rangeEndDate)) {
                LocalDate temporarySwapDate = rangeStartDate;
                rangeStartDate = rangeEndDate;
                rangeEndDate   = temporarySwapDate;
            }
            for (LocalDate currentDate = rangeStartDate; !currentDate.isAfter(rangeEndDate); currentDate = currentDate.plusDays(1)) {
                List<BatchModel> dailyBatchList = dashboardService.getBatchesFilteredAsModels(null, null, currentDate);
                if (dailyBatchList != null) combinedBatchList.addAll(dailyBatchList);
            }
        }

        allBatchList = (combinedBatchList != null) ? combinedBatchList : new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTERING
    // ════════════════════════════════════════════════════════════════════

    /** Called whenever a filter control changes — new filter criteria, so jump back to page 1 */
    private void applyFilters() {
        filteredBatchList = getFilteredBatches();
        batchCurrentPage = 1;
        Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, batchCurrentPage);
        updateStats(filteredBatchList);
        renderPage();
    }

    /** Called on initial page load / restore from session — keeps the restored page number */
    private void applyFiltersPreservingPage() {
        filteredBatchList = getFilteredBatches();
        updateStats(filteredBatchList);
        renderPage();
    }

    private List<BatchModel> getFilteredBatches() {
        List<BatchModel> filteredResult = new ArrayList<>();
        for (BatchModel batchModel : allBatchList) {
            if (!matchesSearch(batchModel))    continue;
            if (!matchesStatus(batchModel))    continue;
            if (!matchesDateRange(batchModel)) continue;
            filteredResult.add(batchModel);
        }
        return filteredResult;
    }

    /** Search box filter — matches if Batch ID contains the typed text (case-insensitive) */
    private boolean matchesSearch(BatchModel batchModel) {
        if (batchSearchKeyword == null || batchSearchKeyword.isBlank()) return true;
        String batchId = batchModel.getBatchId();
        if (batchId == null) return false;
        return batchId.toLowerCase().contains(batchSearchKeyword.trim().toLowerCase());
    }

    /** Status dropdown filter — "All" or exact BatchStatus dbValue match. */
    private boolean matchesStatus(BatchModel batchModel) {
        if (batchStatusFilter == null || "All".equalsIgnoreCase(batchStatusFilter)) return true;
        if ("Dispatched".equals(batchStatusFilter) || "CxfGenerated".equals(batchStatusFilter))
            return isPostVerified(batchModel.getStatus());
        return BatchStatus.fromDb(batchStatusFilter) == BatchStatus.fromDb(batchModel.getStatus());
    }

    private boolean isPostVerified(String statusDbValue) {
        BatchStatus batchStatusEnum = BatchStatus.fromDb(statusDbValue);
        return batchStatusEnum == BatchStatus.CXF_CIBF_GENERATED || batchStatusEnum == BatchStatus.DISPATCHED;
    }

    /** Date range filter — matches if batch's created date falls within [batchFilterDateFrom, batchFilterDateTo] (inclusive) */
    private boolean matchesDateRange(BatchModel batchModel) {
        if (batchFilterDateFrom == null && batchFilterDateTo == null) return true;
        if (batchModel.getCreatedAt() == null) return false;

        LocalDate batchCreatedDate = batchModel.getCreatedAt().toLocalDate();
        if (batchFilterDateFrom != null && batchCreatedDate.isBefore(batchFilterDateFrom)) return false;
        if (batchFilterDateTo   != null && batchCreatedDate.isAfter(batchFilterDateTo))    return false;
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    //  LIVE STAT CARDS — computed from the FILTERED list
    // ════════════════════════════════════════════════════════════════════

    private void updateStats(List<BatchModel> filteredBatchList) {
        int totalBatchCount           = filteredBatchList.size();
        int verificationStageBatchCount = 0;
        int verifiedBatchCount          = 0;
        int dispatchedBatchCount        = 0;

        for (BatchModel batchModel : filteredBatchList) {
            BatchStatus batchStatusEnum = BatchStatus.fromDb(batchModel.getStatus());
            if (batchStatusEnum == BatchStatus.READY_FOR_VERIFICATION || batchStatusEnum == BatchStatus.VERIFICATION_IN_PROGRESS) {
                verificationStageBatchCount++;
            } else if (batchStatusEnum == BatchStatus.VERIFIED) {
                verifiedBatchCount++;
            } else if (batchStatusEnum == BatchStatus.CXF_CIBF_GENERATED || batchStatusEnum == BatchStatus.DISPATCHED) {
                dispatchedBatchCount++;
            }
        }

        setLabelValue(lblTotalBatches,        String.valueOf(totalBatchCount));
        setLabelValue(lblVerificationBatches, String.valueOf(verificationStageBatchCount));
        setLabelValue(lblVerifiedBatches,     String.valueOf(verifiedBatchCount));
        setLabelValue(lblDispatchedBatches,   String.valueOf(dispatchedBatchCount));
    }

    // ════════════════════════════════════════════════════════════════════
    //  RENDER — current page of the filtered list
    //  Column order:
    //    1. BATCH ID   2. CREATED DATE   3. TOTAL CHEQUES
    //    4. VERIFIED   5. PENDING        6. TOTAL AMOUNT
    //    7. STATUS     8. ACTION
    // ════════════════════════════════════════════════════════════════════

    private void renderPage() {
        int totalPages = getTotalPages(filteredBatchList.size());
        if (batchCurrentPage > totalPages) batchCurrentPage = totalPages;
        if (batchCurrentPage < 1) batchCurrentPage = 1;

        int pageStartIndex = (batchCurrentPage - 1) * batchPageSize;
        int pageEndIndex   = Math.min(pageStartIndex + batchPageSize, filteredBatchList.size());
        List<BatchModel> currentPageBatchList = filteredBatchList.subList(pageStartIndex, pageEndIndex);

        renderBatches(currentPageBatchList);

        if (lblBatchPageInfo != null) {
            lblBatchPageInfo.setValue("Page " + batchCurrentPage + " of " + totalPages);
        }
        if (btnBatchPagePrev != null) btnBatchPagePrev.setDisabled(batchCurrentPage <= 1);
        if (btnBatchPageNext != null) btnBatchPageNext.setDisabled(batchCurrentPage >= totalPages);

        if (lblBatchCount != null) {
            int totalFilteredCount = filteredBatchList.size();
            if (totalFilteredCount == 0) {
                lblBatchCount.setValue("Showing 0 batches");
            } else {
                int displayStartRow = pageStartIndex + 1;
                int displayEndRow   = pageEndIndex;
                lblBatchCount.setValue(
                    "Showing " + displayStartRow + "–" + displayEndRow
                    + " of " + totalFilteredCount + " batches");
            }
        }
    }

    private int getTotalPages(int listSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / batchPageSize);
    }

    private void renderBatches(List<BatchModel> batchPageList) {
        if (batchListbox == null) return;
        batchListbox.getItems().clear();

        for (BatchModel batchModel : batchPageList) {
            Listitem batchListItem = new Listitem();
            batchListItem.setSclass("od-batch-row");

            // Col 1 : BATCH ID
            Listcell batchIdCell = new Listcell();
            Label batchIdLabel = new Label(resolveDisplayValue(batchModel.getBatchId(), "—"));
            batchIdLabel.setSclass("od-batch-link");
            batchIdCell.appendChild(batchIdLabel);
            batchListItem.appendChild(batchIdCell);

            // Col 2 : CREATED DATE
            String formattedCreatedDate = "—";
            if (batchModel.getCreatedAt() != null) {
                formattedCreatedDate = batchModel.getCreatedAt().format(BATCH_DATE_FORMATTER);
            }
            batchListItem.appendChild(new Listcell(formattedCreatedDate));

            // Col 3 : TOTAL CHEQUES
            batchListItem.appendChild(new Listcell(String.valueOf(batchModel.getTotalCheques())));

            // Col 4 : VERIFIED cheques
            int verifiedChequeCount = batchModel.getSubmittedCheques();
            batchListItem.appendChild(new Listcell(verifiedChequeCount > 0 ? String.valueOf(verifiedChequeCount) : "0"));

            // Col 5 : PENDING cheques
            int pendingChequeCount = batchModel.getPendingCheques();
            batchListItem.appendChild(new Listcell(pendingChequeCount > 0 ? String.valueOf(pendingChequeCount) : "0"));

            // Col 6 : TOTAL AMOUNT
            Listcell totalAmountCell = new Listcell(formatAmount(batchModel.getTotalAmount()));
            totalAmountCell.setSclass("od-amt-cell");
            batchListItem.appendChild(totalAmountCell);

            // Col 7 : STATUS chip
            Listcell statusCell = new Listcell();
            Label statusLabel = new Label(statusLabel(batchModel.getStatus()));
            statusLabel.setSclass(statusChip(batchModel.getStatus()));
            statusCell.appendChild(statusLabel);
            batchListItem.appendChild(statusCell);

            // Col 8 : ACTION — View button
            Listcell actionCell = new Listcell();
            Button viewBatchButton = new Button("View");
            viewBatchButton.setSclass("od-btn-view");
            final String batchIdentifier = batchModel.getBatchId();
            viewBatchButton.addEventListener("onClick", e -> openBatch(batchIdentifier));
            actionCell.appendChild(viewBatchButton);
            batchListItem.appendChild(actionCell);

            batchListItem.addEventListener("onClick", e -> openBatch(batchIdentifier));

            batchListbox.appendChild(batchListItem);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NAVIGATION
    private void openBatch(String batchId) {
        if (batchId == null) return;
        Sessions.getCurrent().setAttribute("selectedBatchId", batchId);
        Sessions.getCurrent().setAttribute(
            DashboardComposer.LAST_VISITED_PAGE_KEY,
            "/zul/outward/batch-detail.zul");
        DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private void guardSession() {
        if (!SecurityUtil.isLoggedIn()) {
            Executions.sendRedirect("/zul/login.zul");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTER STATE PERSISTENCE (session-backed — survives navigating
    //  away to another page and back, only reset by the Clear button)
    // ════════════════════════════════════════════════════════════════════

    private void restoreFiltersFromSession() {
        Object savedBatchSearch     = Sessions.getCurrent().getAttribute(SESS_SEARCH_TEXT);
        Object savedBatchStatus     = Sessions.getCurrent().getAttribute(SESS_STATUS_FILTER);
        Object savedBatchDateFrom   = Sessions.getCurrent().getAttribute(SESS_DATE_FROM);
        Object savedBatchDateTo     = Sessions.getCurrent().getAttribute(SESS_DATE_TO);
        Object savedBatchCurrentPage = Sessions.getCurrent().getAttribute(SESS_CURRENT_PAGE);
        Object savedBatchPageSize   = Sessions.getCurrent().getAttribute(SESS_PAGE_SIZE);

        if (savedBatchSearch == null && savedBatchStatus == null
                && savedBatchDateFrom == null && savedBatchDateTo == null) {
            // First time landing on this page this session — default to today
            LocalDate today = LocalDate.now();
            batchSearchKeyword  = "";
            batchStatusFilter   = "All";
            batchFilterDateFrom = today;
            batchFilterDateTo   = today;
            batchCurrentPage    = 1;
            batchPageSize       = DEFAULT_PAGE_SIZE;
            saveFiltersToSession();
            return;
        }

        batchSearchKeyword  = (savedBatchSearch      != null) ? savedBatchSearch.toString()        : "";
        batchStatusFilter   = (savedBatchStatus      != null) ? savedBatchStatus.toString()        : "All";
        batchFilterDateFrom = (savedBatchDateFrom    != null) ? (LocalDate) savedBatchDateFrom     : null;
        batchFilterDateTo   = (savedBatchDateTo      != null) ? (LocalDate) savedBatchDateTo       : null;
        batchCurrentPage    = (savedBatchCurrentPage != null) ? (Integer)   savedBatchCurrentPage  : 1;
        batchPageSize       = (savedBatchPageSize    != null) ? (Integer)   savedBatchPageSize     : DEFAULT_PAGE_SIZE;
    }

    private void saveFiltersToSession() {
        Sessions.getCurrent().setAttribute(SESS_SEARCH_TEXT,   batchSearchKeyword);
        Sessions.getCurrent().setAttribute(SESS_STATUS_FILTER, batchStatusFilter);
        Sessions.getCurrent().setAttribute(SESS_DATE_FROM,     batchFilterDateFrom);
        Sessions.getCurrent().setAttribute(SESS_DATE_TO,       batchFilterDateTo);
        Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE,  batchCurrentPage);
        Sessions.getCurrent().setAttribute(SESS_PAGE_SIZE,     batchPageSize);
    }

    /** Selects the comboitem whose value matches, falling back to index 0 ("All") */
    private void selectComboByValue(Combobox combo, String value) {
        if (combo == null) return;
        for (Object child : combo.getItems()) {
            Comboitem item = (Comboitem) child;
            Object comboItemValue = item.getValue();
            if (comboItemValue != null && comboItemValue.toString().equals(value)) {
                combo.setSelectedItem(item);
                return;
            }
        }
        if (!combo.getItems().isEmpty()) {
            combo.setSelectedIndex(0);
        }
    }

    private void setLabelValue(Label label, String value) {
        if (label != null) label.setValue(value != null ? value : "0");
    }

    private String resolveDisplayValue(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "₹0.00";
        return "₹" + String.format("%,.2f", amount);
    }

    private Date toUtilDate(LocalDate inputDate) {
        return Date.from(inputDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private LocalDate toLocalDateFromUtil(Date inputDate) {
        return inputDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void updateDateConstraints() {
        if (dteBatchDateTo != null) {
            dteBatchDateTo.setConstraint(batchFilterDateFrom != null ? "after " + formatDateConstraint(batchFilterDateFrom) : (String) null);
        }
        if (dteBatchDateFrom != null) {
            dteBatchDateFrom.setConstraint(batchFilterDateTo != null ? "before " + formatDateConstraint(batchFilterDateTo) : (String) null);
        }
    }

    private String formatDateConstraint(LocalDate inputDate) {
        return inputDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /**
     * Human-readable label for the status chip in the batch table.
     * Backed by BatchStatus.fromDb(...).getLabel() — the single source
     * of truth for dbValue -> label mapping is now the enum, not this method.
     *
     * NOTE: PENDING's enum label is plain "Pending" — kept as "Pending (Maker)"
     * here instead, since that's clearer in this specific dashboard context
     * (distinguishes it from other "pending" states a user might assume exist).
     * Remove this override if you'd rather match the enum label everywhere.
     *
     * BatchStatus.fromDbValue() falls back to DRAFT for any value it doesn't
     * recognize — the `bs.db().equalsIgnoreCase(s)` check below guards against
     * that silently mislabeling a genuinely unknown/future status as "Draft".
     */
    private String statusLabel(String statusDbValue) {
        if (statusDbValue == null) return "—";
        BatchStatus batchStatusEnum = BatchStatus.fromDbValue(statusDbValue);
        if (batchStatusEnum == BatchStatus.PENDING) return "Pending (Maker)";
        if (batchStatusEnum == BatchStatus.DRAFT && !batchStatusEnum.db().equalsIgnoreCase(statusDbValue)) return statusDbValue;
        return batchStatusEnum.getLabel();
    }

    private String statusChip(String statusDbValue) {
        if (statusDbValue == null) return "chip ch-pending";
        BatchStatus batchStatusEnum = BatchStatus.fromDbValue(statusDbValue);
        if (batchStatusEnum == BatchStatus.DRAFT && !batchStatusEnum.db().equalsIgnoreCase(statusDbValue)) return "chip ch-pending";

        return switch (batchStatusEnum) {
            case READY_FOR_VERIFICATION   -> "chip ch-verification";
            case VERIFICATION_IN_PROGRESS -> "chip ch-in-progress";
            case VERIFIED                 -> "chip ch-verified";
            case CXF_CIBF_GENERATED, DISPATCHED -> "chip ch-dispatched";
            case DRAFT, PENDING            -> "chip ch-draft";
        };
    }
}