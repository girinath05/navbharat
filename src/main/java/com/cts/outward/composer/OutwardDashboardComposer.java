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
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
    @Wire private Textbox  txtBatchId;
    @Wire private Combobox cmbStatus;
    @Wire private Datebox  filterDateFrom;
    @Wire private Datebox  filterDateTo;
    @Wire private Button   btnClear;

    // ── Batch list / pagination ─────────────────────────────────────────────
    @Wire private Label    lblBatchCount;
    @Wire private Listbox  batchListbox;
    @Wire private Button   btnBatchPagePrev;
    @Wire private Label    lblBatchPageInfo;
    @Wire private Button   btnBatchPageNext;
    @Wire private Intbox   intPageSize;

    // ── In-memory data + filter state ───────────────────────────────────────
    private List<BatchModel> allBatches      = new ArrayList<>();
    private List<BatchModel> filteredBatches = new ArrayList<>();

    private String    searchText   = "";
    private String    statusFilter = "All";
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private int       currentPage  = 1;
    private int       pageSize     = DEFAULT_PAGE_SIZE;

    // ════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        guardSession();

        restoreFiltersFromSession();

        if (txtBatchId    != null) txtBatchId.setValue(searchText);
        if (cmbStatus     != null) selectComboByValue(cmbStatus, statusFilter);
        if (filterDateFrom != null) filterDateFrom.setValue(dateFrom != null ? toDate(dateFrom) : null);
        if (filterDateTo   != null) filterDateTo.setValue(dateTo   != null ? toDate(dateTo)   : null);
        updateDateConstraints();
        if (intPageSize    != null) intPageSize.setValue(pageSize);

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
    @Listen("onChange = #txtBatchId")
    public void onSearchChange() {
        searchText = (txtBatchId != null && txtBatchId.getValue() != null)
                ? txtBatchId.getValue() : "";
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
    @Listen("onChange = #filterDateFrom")
    public void onDateFromChange() {
        Date d = filterDateFrom != null ? filterDateFrom.getValue() : null;
        dateFrom = (d == null) ? null : toLocalDate(d);

        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            dateTo = dateFrom;
            if (filterDateTo != null) filterDateTo.setValue(toDate(dateTo));
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
    @Listen("onChange = #filterDateTo")
    public void onDateToChange() {
        Date d = filterDateTo != null ? filterDateTo.getValue() : null;
        dateTo = (d == null) ? null : toLocalDate(d);

        if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
            dateFrom = dateTo;
            if (filterDateFrom != null) filterDateFrom.setValue(toDate(dateFrom));
        }
        updateDateConstraints();

        saveFiltersToSession();
        reloadBatchesForRange();
        applyFilters();
    }

    /** Status dropdown — filters in-memory only, no DB hit */
    @Listen("onSelect = #cmbStatus")
    public void onStatusChange() {
        Comboitem sel = cmbStatus != null ? cmbStatus.getSelectedItem() : null;
        Object val = (sel != null) ? sel.getValue() : null;
        statusFilter = (val != null) ? val.toString() : "All";
        saveFiltersToSession();
        applyFilters();
    }

    /** Clear — resets search/status/date range back to "today" */
    @Listen("onClick = #btnClear")
    public void onClear() {
        if (txtBatchId != null) txtBatchId.setValue("");
        if (cmbStatus  != null) cmbStatus.setSelectedIndex(0); // "All"

        LocalDate today = LocalDate.now();

        // Drop any constraint left over from the previous range *before*
        // resetting the values — otherwise resetting to "today" can itself
        // get rejected by a stale "before <old To date>" / "after <old From
        // date>" bound that hasn't been refreshed yet.
        if (filterDateFrom != null) filterDateFrom.setConstraint((String) null);
        if (filterDateTo   != null) filterDateTo.setConstraint((String) null);

        if (filterDateFrom != null) filterDateFrom.setValue(toDate(today));
        if (filterDateTo   != null) filterDateTo.setValue(toDate(today));

        searchText   = "";
        statusFilter = "All";
        dateFrom     = today;
        dateTo       = today;
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
    @Listen("onChange = #intPageSize")
    public void onPageSizeChange() {
        applyPageSizeFromBox();
    }

    /**
     * Rows-per-page number box — Enter key pressed while the box still has
     * focus. ZK's onChange alone only fires on blur, so without this the
     * value would only apply once the user clicks elsewhere; onOK makes
     * pressing Enter apply it immediately.
     */
    @Listen("onOK = #intPageSize")
    public void onPageSizeEnter() {
        applyPageSizeFromBox();
    }

    private void applyPageSizeFromBox() {
        Integer val = (intPageSize != null) ? intPageSize.getValue() : null;
        applyPageSize(val);
    }

    /**
     * Validates/clamps the raw page size, applies it, resets to page 1,
     * persists to session, snaps the box back to the value actually
     * applied (in case it was clamped), then re-renders.
     */
    private void applyPageSize(Integer raw) {
        int newSize = (raw != null)
                ? Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, raw))
                : pageSize; // fallback: keep current size on empty input

        pageSize    = newSize;
        currentPage = 1;
        saveFiltersToSession();

        // Reflect the (possibly clamped) value back into the box
        if (intPageSize != null) intPageSize.setValue(pageSize);

        renderPage();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAGINATION LISTENERS (manual Prev / Next — same pattern as V2)
    // ════════════════════════════════════════════════════════════════════

    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (currentPage > 1) {
            currentPage--;
            Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, currentPage);
            renderPage();
        }
    }

    @Listen("onClick = #btnBatchPageNext")
    public void onBatchPageNext() {
        int totalPages = getTotalPages(filteredBatches.size());
        if (currentPage < totalPages) {
            currentPage++;
            Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, currentPage);
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
        List<BatchModel> combined = new ArrayList<>();

        LocalDate from = dateFrom;
        LocalDate to   = dateTo;

        if (from == null && to == null) {
            combined = dashboardService.getBatchesFilteredAsModels(null, null, LocalDate.now());
        } else {
            if (from == null) from = to;
            if (to   == null) to   = from;
            if (from.isAfter(to)) {
                LocalDate tmp = from;
                from = to;
                to   = tmp;
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                List<BatchModel> dayBatches = dashboardService.getBatchesFilteredAsModels(null, null, d);
                if (dayBatches != null) combined.addAll(dayBatches);
            }
        }

        allBatches = (combined != null) ? combined : new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════
    //  FILTERING
    // ════════════════════════════════════════════════════════════════════

    /** Called whenever a filter control changes — new filter criteria, so jump back to page 1 */
    private void applyFilters() {
        filteredBatches = getFilteredBatches();
        currentPage = 1;
        Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, currentPage);
        updateStats(filteredBatches);
        renderPage();
    }

    /** Called on initial page load / restore from session — keeps the restored page number */
    private void applyFiltersPreservingPage() {
        filteredBatches = getFilteredBatches();
        updateStats(filteredBatches);
        renderPage();
    }

    private List<BatchModel> getFilteredBatches() {
        List<BatchModel> out = new ArrayList<>();
        for (BatchModel b : allBatches) {
            if (!matchesSearch(b))    continue;
            if (!matchesStatus(b))    continue;
            if (!matchesDateRange(b)) continue;
            out.add(b);
        }
        return out;
    }

    /** Search box filter — matches if Batch ID contains the typed text (case-insensitive) */
    private boolean matchesSearch(BatchModel b) {
        if (searchText == null || searchText.isBlank()) return true;
        String id = b.getBatchId();
        if (id == null) return false;
        return id.toLowerCase().contains(searchText.trim().toLowerCase());
    }

    /** Status dropdown filter — "All" or exact BatchStatus dbValue match.
     *  Special case: selecting "CxfGenerated" in the dropdown matches ALL
     *  post-verified statuses (CxfGenerated, Dispatched, ACK_PENDING, ACK_RECEIVED)
     *  because from the dashboard perspective they are all "post-verification" batches.
     */
    private boolean matchesStatus(BatchModel b) {
        if (statusFilter == null || "All".equalsIgnoreCase(statusFilter)) return true;
        // "CxfGenerated" dropdown option covers all post-verified statuses
        if ("Dispatched".equals(statusFilter) || "CxfGenerated".equals(statusFilter)) return isPostVerified(b.getStatus());
        return BatchStatus.fromDb(statusFilter) == BatchStatus.fromDb(b.getStatus());
    }

    /**
     * Returns true for any status that comes AFTER Verified in the batch lifecycle
     * (CXF generated or fully dispatched).
     */
    private boolean isPostVerified(String s) {
        BatchStatus bs = BatchStatus.fromDb(s);
        return bs == BatchStatus.CXF_CIBF_GENERATED || bs == BatchStatus.DISPATCHED;
    }

    /** Date range filter — matches if batch's created date falls within [from, to] (inclusive) */
    private boolean matchesDateRange(BatchModel b) {
        if (dateFrom == null && dateTo == null) return true;
        if (b.getCreatedAt() == null) return false;

        LocalDate created = b.getCreatedAt().toLocalDate();
        if (dateFrom != null && created.isBefore(dateFrom)) return false;
        if (dateTo   != null && created.isAfter(dateTo))   return false;
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    //  LIVE STAT CARDS — computed from the FILTERED list
    // ════════════════════════════════════════════════════════════════════

    private void updateStats(List<BatchModel> filtered) {
        int total            = filtered.size();
        int verificationStage = 0;
        int verified          = 0;
        int dispatched        = 0;

        for (BatchModel b : filtered) {
            BatchStatus bs = BatchStatus.fromDb(b.getStatus());
            if (bs == BatchStatus.READY_FOR_VERIFICATION || bs == BatchStatus.VERIFICATION_IN_PROGRESS) {
                verificationStage++;
            } else if (bs == BatchStatus.VERIFIED) {
                verified++;
            } else if (bs == BatchStatus.CXF_CIBF_GENERATED || bs == BatchStatus.DISPATCHED) {
                // CxfGenerated, Dispatched — both count toward the "Dispatched" card
                dispatched++;
            }
        }

        safe(lblTotalBatches,        String.valueOf(total));
        safe(lblVerificationBatches, String.valueOf(verificationStage));
        safe(lblVerifiedBatches,     String.valueOf(verified));
        safe(lblDispatchedBatches,   String.valueOf(dispatched));
    }

    // ════════════════════════════════════════════════════════════════════
    //  RENDER — current page of the filtered list
    //  Column order:
    //    1. BATCH ID   2. CREATED DATE   3. TOTAL CHEQUES
    //    4. VERIFIED   5. PENDING        6. TOTAL AMOUNT
    //    7. STATUS     8. ACTION
    // ════════════════════════════════════════════════════════════════════

    private void renderPage() {
        int totalPages = getTotalPages(filteredBatches.size());
        if (currentPage > totalPages) currentPage = totalPages;
        if (currentPage < 1) currentPage = 1;

        int from = (currentPage - 1) * pageSize;
        int to   = Math.min(from + pageSize, filteredBatches.size());
        List<BatchModel> pageItems = filteredBatches.subList(from, to);

        renderBatches(pageItems);

        if (lblBatchPageInfo != null) {
            lblBatchPageInfo.setValue("Page " + currentPage + " of " + totalPages);
        }
        if (btnBatchPagePrev != null) btnBatchPagePrev.setDisabled(currentPage <= 1);
        if (btnBatchPageNext != null) btnBatchPageNext.setDisabled(currentPage >= totalPages);

        if (lblBatchCount != null) {
            int totalFiltered = filteredBatches.size();
            if (totalFiltered == 0) {
                lblBatchCount.setValue("Showing 0 batches");
            } else {
                int displayFrom = from + 1;          // 1-based start row on this page
                int displayTo   = to;                // last row on this page
                lblBatchCount.setValue(
                    "Showing " + displayFrom + "–" + displayTo
                    + " of " + totalFiltered + " batches");
            }
        }
    }

    private int getTotalPages(int listSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / pageSize);
    }

    private void renderBatches(List<BatchModel> list) {
        if (batchListbox == null) return;
        batchListbox.getItems().clear();

        for (BatchModel b : list) {
            Listitem row = new Listitem();
            row.setSclass("od-batch-row");

            // Col 1 : BATCH ID
            Listcell idCell = new Listcell();
            Label idLbl = new Label(nvl(b.getBatchId(), "—"));
            idLbl.setSclass("od-batch-link");
            idCell.appendChild(idLbl);
            row.appendChild(idCell);

            // Col 2 : CREATED DATE
            String dateStr = "—";
            if (b.getCreatedAt() != null) {
                dateStr = b.getCreatedAt().format(DTF);
            }
            row.appendChild(new Listcell(dateStr));

            // Col 3 : TOTAL CHEQUES
            row.appendChild(new Listcell(String.valueOf(b.getTotalCheques())));

            // Col 4 : VERIFIED cheques (previously called "PROCESSED")
            int submitted = b.getSubmittedCheques();
            row.appendChild(new Listcell(submitted > 0 ? String.valueOf(submitted) : "0"));

            // Col 5 : PENDING cheques
            int pending = b.getPendingCheques();
            row.appendChild(new Listcell(pending > 0 ? String.valueOf(pending) : "0"));

            // Col 6 : TOTAL AMOUNT
            Listcell amtCell = new Listcell(fmtAmt(b.getTotalAmount()));
            amtCell.setSclass("od-amt-cell");
            row.appendChild(amtCell);

            // Col 7 : STATUS chip
            Listcell stCell = new Listcell();
            Label stLbl = new Label(statusLabel(b.getStatus()));
            stLbl.setSclass(statusChip(b.getStatus()));
            stCell.appendChild(stLbl);
            row.appendChild(stCell);

            // Col 8 : ACTION — View button
            Listcell actCell = new Listcell();
            Button viewBtn = new Button("View");
            viewBtn.setSclass("od-btn-view");
            final String bId = b.getBatchId();
            viewBtn.addEventListener("onClick", e -> openBatch(bId));
            actCell.appendChild(viewBtn);
            row.appendChild(actCell);

            row.addEventListener("onClick", e -> openBatch(bId));

            batchListbox.appendChild(row);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NAVIGATION
    // ════════════════════════════════════════════════════════════════════

    private void openBatch(String batchId) {
        if (batchId == null) return;
        Sessions.getCurrent().setAttribute("selectedBatchId", batchId);
        Sessions.getCurrent().setAttribute(DashboardComposer.LAST_VISITED_PAGE_KEY,
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
        Object savedSearch = Sessions.getCurrent().getAttribute(SESS_SEARCH_TEXT);
        Object savedStatus = Sessions.getCurrent().getAttribute(SESS_STATUS_FILTER);
        Object savedFrom   = Sessions.getCurrent().getAttribute(SESS_DATE_FROM);
        Object savedTo     = Sessions.getCurrent().getAttribute(SESS_DATE_TO);
        Object savedPage   = Sessions.getCurrent().getAttribute(SESS_CURRENT_PAGE);
        Object savedSize   = Sessions.getCurrent().getAttribute(SESS_PAGE_SIZE);

        if (savedSearch == null && savedStatus == null && savedFrom == null && savedTo == null) {
            // First time landing on this page this session — default to today
            LocalDate today = LocalDate.now();
            searchText   = "";
            statusFilter = "All";
            dateFrom     = today;
            dateTo       = today;
            currentPage  = 1;
            pageSize     = DEFAULT_PAGE_SIZE;
            saveFiltersToSession();
            return;
        }

        searchText   = (savedSearch != null) ? savedSearch.toString() : "";
        statusFilter = (savedStatus != null) ? savedStatus.toString() : "All";
        dateFrom     = (savedFrom   != null) ? (LocalDate) savedFrom : null;
        dateTo       = (savedTo     != null) ? (LocalDate) savedTo   : null;
        currentPage  = (savedPage   != null) ? (Integer) savedPage   : 1;
        pageSize     = (savedSize   != null) ? (Integer) savedSize   : DEFAULT_PAGE_SIZE;
    }

    private void saveFiltersToSession() {
        Sessions.getCurrent().setAttribute(SESS_SEARCH_TEXT, searchText);
        Sessions.getCurrent().setAttribute(SESS_STATUS_FILTER, statusFilter);
        Sessions.getCurrent().setAttribute(SESS_DATE_FROM, dateFrom);
        Sessions.getCurrent().setAttribute(SESS_DATE_TO, dateTo);
        Sessions.getCurrent().setAttribute(SESS_CURRENT_PAGE, currentPage);
        Sessions.getCurrent().setAttribute(SESS_PAGE_SIZE, pageSize);
    }

    /** Selects the comboitem whose value matches, falling back to index 0 ("All") */
    private void selectComboByValue(Combobox combo, String value) {
        if (combo == null) return;
        for (Object child : combo.getItems()) {
            Comboitem item = (Comboitem) child;
            Object v = item.getValue();
            if (v != null && v.toString().equals(value)) {
                combo.setSelectedItem(item);
                return;
            }
        }
        if (!combo.getItems().isEmpty()) {
            combo.setSelectedIndex(0);
        }
    }

    private void safe(Label l, String v) {
        if (l != null) l.setValue(v != null ? v : "0");
    }

    private String nvl(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private String fmtAmt(BigDecimal amt) {
        if (amt == null) return "₹0.00";
        return "₹" + String.format("%,.2f", amt);
    }

    private Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private LocalDate toLocalDate(Date d) {
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Keeps the From/To dateboxes from accepting an invalid range: To can't
     * be set earlier than From, and From can't be set later than To. Uses
     * ZK Datebox's built-in "after yyyyMMdd" / "before yyyyMMdd" constraint
     * syntax (both bounds inclusive of the given date).
     */
    private void updateDateConstraints() {
        if (filterDateTo != null) {
            filterDateTo.setConstraint(dateFrom != null ? "after " + yyyymmdd(dateFrom) : (String) null);
        }
        if (filterDateFrom != null) {
            filterDateFrom.setConstraint(dateTo != null ? "before " + yyyymmdd(dateTo) : (String) null);
        }
    }

    private String yyyymmdd(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
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
    private String statusLabel(String s) {
        if (s == null) return "—";
        BatchStatus bs = BatchStatus.fromDbValue(s);
        if (bs == BatchStatus.PENDING) return "Pending (Maker)";
        if (bs == BatchStatus.DRAFT && !bs.db().equalsIgnoreCase(s)) return s; // unrecognized — show raw value
        return bs.getLabel();
    }

    /**
     * CSS sclass for the status chip.
     * Switches on the BatchStatus enum constant rather than raw strings.
     */
    private String statusChip(String s) {
        if (s == null) return "chip ch-pending";
        BatchStatus bs = BatchStatus.fromDbValue(s);
        if (bs == BatchStatus.DRAFT && !bs.db().equalsIgnoreCase(s)) return "chip ch-pending"; // unrecognized status

        return switch (bs) {
            case READY_FOR_VERIFICATION   -> "chip ch-verification";
            case VERIFICATION_IN_PROGRESS -> "chip ch-in-progress";
            case VERIFIED                 -> "chip ch-verified";
            case CXF_CIBF_GENERATED, DISPATCHED -> "chip ch-dispatched";
            case DRAFT, PENDING            -> "chip ch-draft";
        };
    }
}
