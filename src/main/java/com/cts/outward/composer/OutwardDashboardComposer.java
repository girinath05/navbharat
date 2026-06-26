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

    // Formatter used to display batch created date in the table (e.g. 26/06/2025)
    private static final DateTimeFormatter BATCH_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Default batches shown per page in the batch list — user can override
    // via the "Rows per page" combobox: pick a preset (5/10/25/50) or type
    // any custom number, clamped to [MIN_PAGE_SIZE, MAX_PAGE_SIZE]
    private static final int DEFAULT_PAGE_SIZE = 5;
    private static final int MIN_PAGE_SIZE     = 1;
    private static final int MAX_PAGE_SIZE     = 200;

    // ── Session keys — persist filter state across page navigation ───────
    // Each key stores one filter field in the ZK session so state survives navigating away and back
    private static final String SESS_SEARCH_TEXT   = "od_searchText";
    private static final String SESS_STATUS_FILTER = "od_statusFilter";
    private static final String SESS_DATE_FROM     = "od_dateFrom";
    private static final String SESS_DATE_TO       = "od_dateTo";
    private static final String SESS_CURRENT_PAGE  = "od_currentPage";
    private static final String SESS_PAGE_SIZE     = "od_pageSize";

    // ── Service ──────────────────────────────────────────────────────────
    // Manual DI: service wired with its DAO implementation (no Spring)
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
    // allBatchList holds the full DB result for the selected date range; filteredBatchList is the subset after filters
    private List<BatchModel> allBatchList      = new ArrayList<>();
    private List<BatchModel> filteredBatchList = new ArrayList<>();

    // Active values of each filter control — kept in sync with the UI and ZK session
    private String    batchSearchKeyword  = "";
    private String    batchStatusFilter   = "All";
    private LocalDate batchFilterDateFrom;
    private LocalDate batchFilterDateTo;
    private int       batchCurrentPage    = 1;
    private int       batchPageSize       = DEFAULT_PAGE_SIZE;

    // ════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Entry point called by ZK after all @Wire fields are injected.
     * Restores saved filter state from session, populates UI controls, then defers data load via onLater.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // Redirect to login immediately if no valid session exists
        guardSession();

        // Pull saved filter values from ZK session (or set defaults on first visit)
        restoreFiltersFromSession();

        // Push restored values back into each UI control so they match the in-memory state
        if (txtBatchIdSearch != null) txtBatchIdSearch.setValue(batchSearchKeyword);
        if (cmbBatchStatus   != null) selectComboByValue(cmbBatchStatus, batchStatusFilter);
        if (dteBatchDateFrom != null) dteBatchDateFrom.setValue(batchFilterDateFrom != null ? toUtilDate(batchFilterDateFrom) : null);
        if (dteBatchDateTo   != null) dteBatchDateTo.setValue(batchFilterDateTo     != null ? toUtilDate(batchFilterDateTo)   : null);

        // Refresh From/To constraints so the ZK datebox enforces valid ranges
        updateDateConstraints();
        if (intBatchPageSize != null) intBatchPageSize.setValue(batchPageSize);

        // Defer DB fetch to onLater so the ZUL page fully renders before data is loaded
        Events.postEvent("onLater", comp, null);
    }

    /**
     * Fires after the page renders completely; loads batch data and applies filters without losing the restored page number.
     */
    @Listen("onLater = #outwardDashboardRoot")
    public void onLater() {
        reloadBatchesForRange();
        applyFiltersPreservingPage();

        // Force listbox to full width and invalidate to flush any stale ZK render cache
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
        // Read typed text; treat null as empty string so downstream checks are safe
        batchSearchKeyword = (txtBatchIdSearch != null && txtBatchIdSearch.getValue() != null)
                ? txtBatchIdSearch.getValue() : "";
        saveFiltersToSession();
        // In-memory filter only — no DB hit needed for search text change
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
        // Convert java.util.Date from ZK datebox to LocalDate for consistent range math
        batchFilterDateFrom = (inputDate == null) ? null : toLocalDateFromUtil(inputDate);

        // Auto-correct: if From is now after To, snap To forward to equal From
        if (batchFilterDateFrom != null && batchFilterDateTo != null && batchFilterDateFrom.isAfter(batchFilterDateTo)) {
            batchFilterDateTo = batchFilterDateFrom;
            if (dteBatchDateTo != null) dteBatchDateTo.setValue(toUtilDate(batchFilterDateTo));
        }

        // Refresh datebox constraints so ZK enforces the updated boundary
        updateDateConstraints();

        saveFiltersToSession();
        // Date change alters the DB query range — full reload needed
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
        // Convert java.util.Date from ZK datebox to LocalDate for consistent range math
        batchFilterDateTo = (inputDate == null) ? null : toLocalDateFromUtil(inputDate);

        // Auto-correct: if To is now before From, snap From back to equal To
        if (batchFilterDateFrom != null && batchFilterDateTo != null && batchFilterDateTo.isBefore(batchFilterDateFrom)) {
            batchFilterDateFrom = batchFilterDateTo;
            if (dteBatchDateFrom != null) dteBatchDateFrom.setValue(toUtilDate(batchFilterDateFrom));
        }

        // Refresh datebox constraints so ZK enforces the updated boundary
        updateDateConstraints();

        saveFiltersToSession();
        // Date change alters the DB query range — full reload needed
        reloadBatchesForRange();
        applyFilters();
    }

    /** Status dropdown — filters in-memory only, no DB hit */
    @Listen("onSelect = #cmbBatchStatus")
    public void onBatchStatusChange() {
        Comboitem selectedComboItem = cmbBatchStatus != null ? cmbBatchStatus.getSelectedItem() : null;
        Object selectedStatusValue = (selectedComboItem != null) ? selectedComboItem.getValue() : null;
        // Fall back to "All" if nothing is selected (e.g., combo cleared programmatically)
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

        // Set both dateboxes to today after clearing constraints
        if (dteBatchDateFrom != null) dteBatchDateFrom.setValue(toUtilDate(today));
        if (dteBatchDateTo   != null) dteBatchDateTo.setValue(toUtilDate(today));

        // Reset all in-memory filter state to defaults
        batchSearchKeyword  = "";
        batchStatusFilter   = "All";
        batchFilterDateFrom = today;
        batchFilterDateTo   = today;

        // Re-apply constraints for the fresh "today = today" range
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

    /** Also fires when the user presses Enter inside the page-size box */
    @Listen("onOK = #intBatchPageSize")
    public void onBatchPageSizeEnter() {
        applyPageSizeFromBox();
    }

    /** Reads the raw integer from the box and delegates clamping/apply to applyPageSize() */
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
        // Clamp user input to the allowed range; keep current size if input is null/empty
        int clampedPageSize = (rawPageSize != null)
                ? Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, rawPageSize))
                : batchPageSize; // fallback: keep current size on empty input

        batchPageSize    = clampedPageSize;
        // Reset to page 1 whenever page size changes to avoid landing on a now-nonexistent page
        batchCurrentPage = 1;
        saveFiltersToSession();

        // Reflect the (possibly clamped) value back into the box so user sees the applied value
        if (intBatchPageSize != null) intBatchPageSize.setValue(batchPageSize);

        renderPage();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAGINATION LISTENERS (manual Prev / Next — same pattern as V2)
    // ════════════════════════════════════════════════════════════════════

    /** Moves to the previous page; does nothing if already on page 1 */
    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (batchCurrentPage > 1) {
            batchCurrentPage--;
            Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, batchCurrentPage);
            renderPage();
        }
    }

    /** Moves to the next page; does nothing if already on the last page */
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
            // No date range selected — load today's batches as the default
            combinedBatchList = dashboardService.getBatchesFilteredAsModels(null, null, LocalDate.now());
        } else {
            // Fill in whichever end of the range was left null with the other end
            if (rangeStartDate == null) rangeStartDate = rangeEndDate;
            if (rangeEndDate   == null) rangeEndDate   = rangeStartDate;

            // Defensive swap: ensure start is never after end before iterating
            if (rangeStartDate.isAfter(rangeEndDate)) {
                LocalDate temporarySwapDate = rangeStartDate;
                rangeStartDate = rangeEndDate;
                rangeEndDate   = temporarySwapDate;
            }

            // Iterate day-by-day and accumulate results into a single combined list
            for (LocalDate currentDate = rangeStartDate; !currentDate.isAfter(rangeEndDate); currentDate = currentDate.plusDays(1)) {
                List<BatchModel> dailyBatchList = dashboardService.getBatchesFilteredAsModels(null, null, currentDate);
                if (dailyBatchList != null) combinedBatchList.addAll(dailyBatchList);
            }
        }

        // Guard against service returning null — always keep allBatchList as a valid list
        allBatchList = (combinedBatchList != null) ? combinedBatchList : new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTERING
    // ════════════════════════════════════════════════════════════════════

    /** Called whenever a filter control changes — new filter criteria, so jump back to page 1 */
    private void applyFilters() {
        filteredBatchList = getFilteredBatches();
        // Reset to page 1 on every new filter so user doesn't land mid-list on stale page
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

    /**
     * Walks allBatchList and keeps only rows that pass all three active filters.
     * All filtering is in-memory — no DB hit.
     */
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

    /** Status dropdown filter — "All" passes every row; otherwise matches exact BatchStatus dbValue. */
    private boolean matchesStatus(BatchModel batchModel) {
        if (batchStatusFilter == null || "All".equalsIgnoreCase(batchStatusFilter)) return true;
        // "Dispatched" combo item covers both CxfGenerated and Dispatched db values
        if ("Dispatched".equals(batchStatusFilter) || "CxfGenerated".equals(batchStatusFilter))
            return isPostVerified(batchModel.getStatus());
        return BatchStatus.fromDb(batchStatusFilter) == BatchStatus.fromDb(batchModel.getStatus());
    }

    /** Returns true if the batch status is either CXF_CIBF_GENERATED or DISPATCHED (post-verification stages) */
    private boolean isPostVerified(String statusDbValue) {
        BatchStatus batchStatusEnum = BatchStatus.fromDb(statusDbValue);
        return batchStatusEnum == BatchStatus.CXF_CIBF_GENERATED || batchStatusEnum == BatchStatus.DISPATCHED;
    }

    /** Date range filter — matches if batch's created date falls within [batchFilterDateFrom, batchFilterDateTo] (inclusive) */
    private boolean matchesDateRange(BatchModel batchModel) {
        if (batchFilterDateFrom == null && batchFilterDateTo == null) return true;
        if (batchModel.getCreatedAt() == null) return false;

        LocalDate batchCreatedDate = batchModel.getCreatedAt().toLocalDate();
        // Reject if batch date is before From or after To boundary
        if (batchFilterDateFrom != null && batchCreatedDate.isBefore(batchFilterDateFrom)) return false;
        if (batchFilterDateTo   != null && batchCreatedDate.isAfter(batchFilterDateTo))    return false;
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    //  LIVE STAT CARDS — computed from the FILTERED list
    // ════════════════════════════════════════════════════════════════════

    /**
     * Counts batches per status group from the filtered list and pushes
     * totals into the four stat card labels — no extra DB call needed.
     */
    private void updateStats(List<BatchModel> filteredBatchList) {
        int totalBatchCount             = filteredBatchList.size();
        int verificationStageBatchCount = 0;
        int verifiedBatchCount          = 0;
        int dispatchedBatchCount        = 0;

        for (BatchModel batchModel : filteredBatchList) {
            // Resolve raw DB string to enum once per row for safe comparison
            BatchStatus batchStatusEnum = BatchStatus.fromDb(batchModel.getStatus());
            if (batchStatusEnum == BatchStatus.READY_FOR_VERIFICATION || batchStatusEnum == BatchStatus.VERIFICATION_IN_PROGRESS) {
                verificationStageBatchCount++;
            } else if (batchStatusEnum == BatchStatus.VERIFIED) {
                verifiedBatchCount++;
            } else if (batchStatusEnum == BatchStatus.CXF_CIBF_GENERATED || batchStatusEnum == BatchStatus.DISPATCHED) {
                dispatchedBatchCount++;
            }
        }

        // Push computed counts into the stat card labels
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

    /**
     * Calculates the slice of filteredBatchList for the current page,
     * renders it into the listbox, and updates pagination controls.
     */
    private void renderPage() {
        int totalPages = getTotalPages(filteredBatchList.size());

        // Clamp current page into valid range in case list size shrank after filtering
        if (batchCurrentPage > totalPages) batchCurrentPage = totalPages;
        if (batchCurrentPage < 1) batchCurrentPage = 1;

        // Calculate start/end index of the current page slice
        int pageStartIndex = (batchCurrentPage - 1) * batchPageSize;
        int pageEndIndex   = Math.min(pageStartIndex + batchPageSize, filteredBatchList.size());
        List<BatchModel> currentPageBatchList = filteredBatchList.subList(pageStartIndex, pageEndIndex);

        renderBatches(currentPageBatchList);

        // Update page info label and disable Prev/Next at boundaries
        if (lblBatchPageInfo != null) {
            lblBatchPageInfo.setValue("Page " + batchCurrentPage + " of " + totalPages);
        }
        if (btnBatchPagePrev != null) btnBatchPagePrev.setDisabled(batchCurrentPage <= 1);
        if (btnBatchPageNext != null) btnBatchPageNext.setDisabled(batchCurrentPage >= totalPages);

        // Update "Showing X–Y of Z batches" count label below the table
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

    /** Returns total page count; always at least 1 even when the list is empty */
    private int getTotalPages(int listSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / batchPageSize);
    }

    /**
     * Clears the listbox and rebuilds all rows for the given page of batches.
     * Each row gets a click listener so clicking anywhere on the row opens the batch.
     */
    private void renderBatches(List<BatchModel> batchPageList) {
        if (batchListbox == null) return;
        // Clear existing rows before re-rendering to avoid duplicates
        batchListbox.getItems().clear();

        for (BatchModel batchModel : batchPageList) {
            Listitem batchListItem = new Listitem();
            batchListItem.setSclass("od-batch-row");

            // Col 1 : BATCH ID — styled as a link; falls back to "—" if null
            Listcell batchIdCell = new Listcell();
            Label batchIdLabel = new Label(resolveDisplayValue(batchModel.getBatchId(), "—"));
            batchIdLabel.setSclass("od-batch-link");
            batchIdCell.appendChild(batchIdLabel);
            batchListItem.appendChild(batchIdCell);

            // Col 2 : CREATED DATE — formatted as dd/MM/yyyy; shows "—" if null
            String formattedCreatedDate = "—";
            if (batchModel.getCreatedAt() != null) {
                formattedCreatedDate = batchModel.getCreatedAt().format(BATCH_DATE_FORMATTER);
            }
            batchListItem.appendChild(new Listcell(formattedCreatedDate));

            // Col 3 : TOTAL CHEQUES
            batchListItem.appendChild(new Listcell(String.valueOf(batchModel.getTotalCheques())));

            // Col 4 : VERIFIED cheques (getSubmittedCheques maps to verified count in BatchModel)
            int verifiedChequeCount = batchModel.getSubmittedCheques();
            batchListItem.appendChild(new Listcell(verifiedChequeCount > 0 ? String.valueOf(verifiedChequeCount) : "0"));

            // Col 5 : PENDING cheques
            int pendingChequeCount = batchModel.getPendingCheques();
            batchListItem.appendChild(new Listcell(pendingChequeCount > 0 ? String.valueOf(pendingChequeCount) : "0"));

            // Col 6 : TOTAL AMOUNT — formatted as ₹X,XXX.XX
            Listcell totalAmountCell = new Listcell(formatAmount(batchModel.getTotalAmount()));
            totalAmountCell.setSclass("od-amt-cell");
            batchListItem.appendChild(totalAmountCell);

            // Col 7 : STATUS chip — label and CSS class both resolved via BatchStatus enum
            Listcell statusCell = new Listcell();
            Label statusLabel = new Label(statusLabel(batchModel.getStatus()));
            statusLabel.setSclass(statusChip(batchModel.getStatus()));
            statusCell.appendChild(statusLabel);
            batchListItem.appendChild(statusCell);

            // Col 8 : ACTION — View button navigates to the batch detail page
            Listcell actionCell = new Listcell();
            Button viewBatchButton = new Button("View");
            viewBatchButton.setSclass("od-btn-view");
            // Capture batchId as final local so the lambda can reference it safely
            final String batchIdentifier = batchModel.getBatchId();
            viewBatchButton.addEventListener("onClick", e -> openBatch(batchIdentifier));
            actionCell.appendChild(viewBatchButton);
            batchListItem.appendChild(actionCell);

            // Row-level click listener so the whole row is clickable, not just the button
            batchListItem.addEventListener("onClick", e -> openBatch(batchIdentifier));

            batchListbox.appendChild(batchListItem);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NAVIGATION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Saves the selected batch ID to session, records the current page as
     * the last visited, then navigates to the batch detail page.
     */
    private void openBatch(String batchId) {
        if (batchId == null) return;
        // Store selected batch ID so batch-detail.zul can read it on load
        Sessions.getCurrent().setAttribute("selectedBatchId", batchId);
        // Track last visited page so DashboardComposer can restore it on back-navigation
        Sessions.getCurrent().setAttribute(
            DashboardComposer.LAST_VISITED_PAGE_KEY,
            "/zul/outward/batch-detail.zul");
        DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    /** Redirects to login if no active session — called at the start of doAfterCompose */
    private void guardSession() {
        if (!SecurityUtil.isLoggedIn()) {
            Executions.sendRedirect("/zul/login.zul");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTER STATE PERSISTENCE (session-backed — survives navigating
    //  away to another page and back, only reset by the Clear button)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Reads all filter fields from ZK session into local variables.
     * On first visit (all keys null) sets safe defaults and saves them immediately.
     */
    private void restoreFiltersFromSession() {
        Object savedBatchSearch      = Sessions.getCurrent().getAttribute(SESS_SEARCH_TEXT);
        Object savedBatchStatus      = Sessions.getCurrent().getAttribute(SESS_STATUS_FILTER);
        Object savedBatchDateFrom    = Sessions.getCurrent().getAttribute(SESS_DATE_FROM);
        Object savedBatchDateTo      = Sessions.getCurrent().getAttribute(SESS_DATE_TO);
        Object savedBatchCurrentPage = Sessions.getCurrent().getAttribute(SESS_CURRENT_PAGE);
        Object savedBatchPageSize    = Sessions.getCurrent().getAttribute(SESS_PAGE_SIZE);

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

        // Restore each field; fall back to a safe default if the key was somehow removed
        batchSearchKeyword  = (savedBatchSearch      != null) ? savedBatchSearch.toString()        : "";
        batchStatusFilter   = (savedBatchStatus      != null) ? savedBatchStatus.toString()        : "All";
        batchFilterDateFrom = (savedBatchDateFrom    != null) ? (LocalDate) savedBatchDateFrom     : null;
        batchFilterDateTo   = (savedBatchDateTo      != null) ? (LocalDate) savedBatchDateTo       : null;
        batchCurrentPage    = (savedBatchCurrentPage != null) ? (Integer)   savedBatchCurrentPage  : 1;
        batchPageSize       = (savedBatchPageSize    != null) ? (Integer)   savedBatchPageSize     : DEFAULT_PAGE_SIZE;
    }

    /** Writes all current filter field values into ZK session for persistence across navigation */
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
            // Match by string value; return immediately when found
            if (comboItemValue != null && comboItemValue.toString().equals(value)) {
                combo.setSelectedItem(item);
                return;
            }
        }
        // No match found — fall back to first item ("All") to avoid blank selection
        if (!combo.getItems().isEmpty()) {
            combo.setSelectedIndex(0);
        }
    }

    /** Null-safe label setter; uses "0" as fallback so stat cards never show blank */
    private void setLabelValue(Label label, String value) {
        if (label != null) label.setValue(value != null ? value : "0");
    }

    /** Returns value if non-blank, otherwise returns fallback — used for display cells */
    private String resolveDisplayValue(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    /** Formats a BigDecimal amount as Indian rupee string (e.g. ₹1,23,456.78); returns ₹0.00 for null */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "₹0.00";
        return "₹" + String.format("%,.2f", amount);
    }

    /** Converts LocalDate to java.util.Date at midnight system time — required by ZK Datebox.setValue() */
    private Date toUtilDate(LocalDate inputDate) {
        return Date.from(inputDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** Converts java.util.Date from ZK Datebox back to LocalDate for filter comparison logic */
    private LocalDate toLocalDateFromUtil(Date inputDate) {
        return inputDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Sets ZK datebox constraints so "To" can't be before "From" and vice versa.
     * Must be called after either date changes to keep the pair consistent.
     */
    private void updateDateConstraints() {
        // "after YYYYMMDD" on dteBatchDateTo prevents user picking a To date before current From
        if (dteBatchDateTo != null) {
            dteBatchDateTo.setConstraint(batchFilterDateFrom != null ? "after " + formatDateConstraint(batchFilterDateFrom) : (String) null);
        }
        // "before YYYYMMDD" on dteBatchDateFrom prevents user picking a From date after current To
        if (dteBatchDateFrom != null) {
            dteBatchDateFrom.setConstraint(batchFilterDateTo != null ? "before " + formatDateConstraint(batchFilterDateTo) : (String) null);
        }
    }

    /** Formats a LocalDate as yyyyMMdd string — the format ZK datebox constraint strings require */
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
        // Override: show more descriptive label for PENDING in dashboard context
        if (batchStatusEnum == BatchStatus.PENDING) return "Pending (Maker)";
        // Guard: if DRAFT was returned as a fallback for an unrecognized value, show raw DB value instead
        if (batchStatusEnum == BatchStatus.DRAFT && !batchStatusEnum.db().equalsIgnoreCase(statusDbValue)) return statusDbValue;
        return batchStatusEnum.getLabel();
    }

    /**
     * Returns the CSS chip class for the given status DB value.
     * Unknown/unrecognized values fall back to ch-pending (neutral grey).
     */
    private String statusChip(String statusDbValue) {
        if (statusDbValue == null) return "chip ch-pending";
        BatchStatus batchStatusEnum = BatchStatus.fromDbValue(statusDbValue);
        // Unrecognized status — DRAFT used as fallback by enum; show neutral chip
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