/*
 * Project : Navbharat CTS
 * File    : DashboardComposer.java
 * Package : com.cts.composer
 *
 * What this file does:
 *   Composer for /zul/dashboard.zul — combined Inward + Outward
 *   dashboard page with KPI cards, two small panels, a filterable
 *   + paginated recent batches list, and an analytics section
 *   (metric cards, charts, insights).
 *
 * Where the data comes from right now:
 *   Inward/Outward services are not built yet, so every number
 *   comes from a getXxx() method below that returns fixed
 *   (static) values. Each one is marked "STATIC DATA".
 *
 * How to switch to real data later:
 *   Replace what's INSIDE the relevant getXxx() method with a
 *   real service call. Nothing else in this class needs to change.
 *
 * NEW in this version:
 *   Recent Batches filter bar (search box, module combo, status
 *   combo, clear button) and pagination (prev/next) are now wired
 *   up and functional. Filtering + paging happen in-memory on top
 *   of getRecentBatches() — once a real service exists, you can
 *   either keep doing it in-memory here, or push filter params
 *   down into the service call instead.
 */
package com.cts.uam.composer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Button;

import com.cts.util.SecurityUtil;

public class MainDashboardComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(MainDashboardComposer.class.getName());

    // When the Inward and Outward services are ready, create them here
    // and use them inside the getXxx() static methods below, e.g.:
    // private final InwardDashboardService inwardDashboardService = ...
    // private final OutwardDashboardService outwardDashboardService = ...

    private static final int PAGE_SIZE = 5;

    // ── Top KPI cards ──────────────────────────────────────────
    @Wire("#dashDateTime")
    private Label dashDateTime;
    @Wire("#lblTotalBatchesKpi")
    private Label lblTotalBatchesKpi;
    @Wire("#lblPendingVerifyKpi")
    private Label lblPendingVerifyKpi;
    @Wire("#lblVerifiedKpi")
    private Label lblVerifiedKpi;
    @Wire("#lblDispatchedKpi")
    private Label lblDispatchedKpi;

    // ── Inward panel ───────────────────────────────────────────
    @Wire("#lblInwardTotalBatches")
    private Label lblInwardTotalBatches;
    @Wire("#lblInwardTotalCheques")
    private Label lblInwardTotalCheques;
    @Wire("#lblInwardTotalAmount")
    private Label lblInwardTotalAmount;
    @Wire("#lblInwardPending")
    private Label lblInwardPending;

    // ── Outward panel ──────────────────────────────────────────
    @Wire("#lblOutwardTotalBatches")
    private Label lblOutwardTotalBatches;
    @Wire("#lblOutwardTotalCheques")
    private Label lblOutwardTotalCheques;
    @Wire("#lblOutwardTotalAmount")
    private Label lblOutwardTotalAmount;
    @Wire("#lblOutwardPending")
    private Label lblOutwardPending;

    // ── Recent batches list + filter bar ────────────────────────
    @Wire("#lblRecentBatchCount")
    private Label lblRecentBatchCount;
    @Wire("#recentBatchListbox")
    private Listbox recentBatchListbox;
    @Wire("#lblRecentBatchPageHint")
    private Label lblRecentBatchPageHint;
    @Wire("#lblRecentBatchPageInfo")
    private Label lblRecentBatchPageInfo;
    @Wire("#txtRecentBatchSearch")
    private Textbox txtRecentBatchSearch;
    @Wire("#cmbRecentModule")
    private Combobox cmbRecentModule;
    @Wire("#cmbRecentStatus")
    private Combobox cmbRecentStatus;
    @Wire("#btnClearRecentFilters")
    private Button btnClearRecentFilters;
    @Wire("#btnRecentBatchPagePrev")
    private Button btnRecentBatchPagePrev;
    @Wire("#btnRecentBatchPageNext")
    private Button btnRecentBatchPageNext;

    // ── Analytics metric cards ─────────────────────────────────
    @Wire("#lblMetricAvgVerifyTime")
    private Label lblMetricAvgVerifyTime;
    @Wire("#lblMetricRejectionRate")
    private Label lblMetricRejectionRate;
    @Wire("#lblMetricMicrRepairRate")
    private Label lblMetricMicrRepairRate;
    @Wire("#lblMetricHighValueCheques")
    private Label lblMetricHighValueCheques;

    // ── Insights cards ─────────────────────────────────────────
    @Wire("#forecastValue")
    private Label lblForecastValue;
    @Wire("#forecastDesc")
    private Label lblForecastDesc;
    @Wire("#rejectionInsightText")
    private Label lblRejectionInsightText;
    @Wire("#lblRejectionMicr")
    private Label lblRejectionMicr;
    @Wire("#lblRejectionSignature")
    private Label lblRejectionSignature;
    @Wire("#lblRejectionInsufficientFunds")
    private Label lblRejectionInsufficientFunds;

    // ════════════════════════════════════════════════════════════
    // FILTER / PAGINATION STATE
    // (kept per-composer-instance, i.e. per page-load/session view)
    // ════════════════════════════════════════════════════════════

    /** Full unfiltered list, loaded once at startup. */
    private List<RecentBatchRow> allRecentBatches = new ArrayList<>();

    /** Currently selected module filter: "All" / "Inward" / "Outward". */
    private String currentModuleFilter = "All";

    /** Currently selected status filter: "All" / "Pending" / "PendingAtChecker" / "Verified". */
    private String currentStatusFilter = "All";

    /** Current free-text search on Batch ID (lower-cased, trimmed). */
    private String currentSearchText = "";

    /** Current page number, 1-based. */
    private int currentPage = 1;

    // ════════════════════════════════════════════════════════════
    // STARTUP — runs once when the page loads
    // ════════════════════════════════════════════════════════════

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // If nobody is logged in, don't try to load any data.
        if (SecurityUtil.getCurrentUser() == null) {
            LOG.warning("DashboardComposer: no logged-in user, skipping data load");
            return;
        }

        fillKpiCards(getKpiData());
        fillInwardPanel(getInwardStats());
        fillOutwardPanel(getOutwardStats());

        allRecentBatches = getRecentBatches();
        applyFiltersAndRender();

        fillMetricCards(getMetrics());
        fillInsights(getInsights());
        drawCharts(getChartsData());
    }

    // ════════════════════════════════════════════════════════════
    // FILTER BAR EVENT HANDLERS
    // ════════════════════════════════════════════════════════════

    /** Live search as the user types in the Batch ID search box. */
    @Listen("onChanging = #txtRecentBatchSearch")
    public void onSearchChanging(Event event) {
        Object value = event.getData();
        currentSearchText = value != null ? value.toString().trim().toLowerCase() : "";
        currentPage = 1; // reset to first page whenever the filter changes
        applyFiltersAndRender();
    }

    /** In case the user hits Enter / blurs instead of relying on instant typing. */
    @Listen("onChange = #txtRecentBatchSearch")
    public void onSearchChange(Event event) {
        currentSearchText = txtRecentBatchSearch != null
                ? txtRecentBatchSearch.getValue().trim().toLowerCase()
                : "";
        currentPage = 1;
        applyFiltersAndRender();
    }

    @Listen("onSelect = #cmbRecentModule")
    public void onModuleFilterSelect(Event event) {
        currentModuleFilter = selectedComboValue(cmbRecentModule, "All");
        currentPage = 1;
        applyFiltersAndRender();
    }

    @Listen("onSelect = #cmbRecentStatus")
    public void onStatusFilterSelect(Event event) {
        currentStatusFilter = selectedComboValue(cmbRecentStatus, "All");
        currentPage = 1;
        applyFiltersAndRender();
    }

    @Listen("onClick = #btnClearRecentFilters")
    public void onClearFiltersClick(Event event) {
        currentSearchText = "";
        currentModuleFilter = "All";
        currentStatusFilter = "All";
        currentPage = 1;

        if (txtRecentBatchSearch != null) {
            txtRecentBatchSearch.setValue("");
        }
        if (cmbRecentModule != null) {
            cmbRecentModule.setSelectedIndex(0); // "All Modules"
        }
        if (cmbRecentStatus != null) {
            cmbRecentStatus.setSelectedIndex(0); // "All Status"
        }

        applyFiltersAndRender();
    }

    @Listen("onClick = #btnRecentBatchPagePrev")
    public void onPrevPageClick(Event event) {
        if (currentPage > 1) {
            currentPage--;
            applyFiltersAndRender();
        }
    }

    @Listen("onClick = #btnRecentBatchPageNext")
    public void onNextPageClick(Event event) {
        int totalPages = computeTotalPages(filteredCount());
        if (currentPage < totalPages) {
            currentPage++;
            applyFiltersAndRender();
        }
    }

    // ════════════════════════════════════════════════════════════
    // FILTERING / PAGINATION LOGIC
    // ════════════════════════════════════════════════════════════

    /** Applies the current search/module/status filters, then re-renders the list + pagination bar. */
    private void applyFiltersAndRender() {
        List<RecentBatchRow> filtered = filterRows(allRecentBatches);

        int totalFiltered = filtered.size();
        int totalPages = computeTotalPages(totalFiltered);

        // clamp current page in case filtering shrank the result set
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }
        if (currentPage < 1) {
            currentPage = 1;
        }

        List<RecentBatchRow> pageRows = paginate(filtered, currentPage);

        renderRows(pageRows);
        renderPaginationInfo(totalFiltered, pageRows.size(), totalPages);
        toggleButton(btnRecentBatchPagePrev, currentPage <= 1);
        toggleButton(btnRecentBatchPageNext, currentPage >= totalPages);
    }

    private int filteredCount() {
        return filterRows(allRecentBatches).size();
    }

    private List<RecentBatchRow> filterRows(List<RecentBatchRow> source) {
        List<RecentBatchRow> result = new ArrayList<>();
        for (RecentBatchRow row : source) {
            if (!matchesModule(row)) continue;
            if (!matchesStatus(row)) continue;
            if (!matchesSearch(row)) continue;
            result.add(row);
        }
        return result;
    }

    private boolean matchesModule(RecentBatchRow row) {
        return "All".equalsIgnoreCase(currentModuleFilter)
                || row.module.equalsIgnoreCase(currentModuleFilter);
    }

    private boolean matchesStatus(RecentBatchRow row) {
        return "All".equalsIgnoreCase(currentStatusFilter)
                || row.status.equalsIgnoreCase(currentStatusFilter);
    }

    private boolean matchesSearch(RecentBatchRow row) {
        if (currentSearchText == null || currentSearchText.isEmpty()) {
            return true;
        }
        return row.batchId.toLowerCase().contains(currentSearchText);
    }

    private List<RecentBatchRow> paginate(List<RecentBatchRow> filtered, int page) {
        int fromIndex = (page - 1) * PAGE_SIZE;
        if (fromIndex >= filtered.size() || fromIndex < 0) {
            return new ArrayList<>();
        }
        int toIndex = Math.min(fromIndex + PAGE_SIZE, filtered.size());
        return filtered.subList(fromIndex, toIndex);
    }

    private int computeTotalPages(int totalRows) {
        if (totalRows <= 0) {
            return 1;
        }
        return (int) Math.ceil(totalRows / (double) PAGE_SIZE);
    }

    /** Reads the value= of the selected comboitem, falling back to a default if nothing is selected. */
    private String selectedComboValue(Combobox combobox, String fallback) {
        if (combobox == null) {
            return fallback;
        }
        Comboitem selected = combobox.getSelectedItem();
        if (selected == null || selected.getValue() == null) {
            return fallback;
        }
        return selected.getValue().toString();
    }

    private void toggleButton(Button button, boolean disabled) {
        if (button != null) {
            button.setDisabled(disabled);
        }
    }

    // ════════════════════════════════════════════════════════════
    // STATIC DATA — fixed numbers for now.
    // To go live, replace the inside of ONE method below with a
    // real service call. Don't change anything else in this file.
    // ════════════════════════════════════════════════════════════

    /** STATIC DATA — numbers for the 4 KPI cards at the top of the page. */
    private DashboardKpiData getKpiData() {
        return new DashboardKpiData(
                7, // total batches (inward + outward)
                3, // pending verification
                4, // verified / processed
                2 // dispatched batches
        );
    }

    /** STATIC DATA — numbers for the "Inward" panel. */
    private ModuleStats getInwardStats() {
        return new ModuleStats(
                4, // total batches
                45, // total cheques
                new BigDecimal("2455200"), // total amount
                6 // pending cheques
        );
    }

    /** STATIC DATA — numbers for the "Outward" panel. */
    private ModuleStats getOutwardStats() {
        return new ModuleStats(
                3, // total batches
                28, // total cheques
                new BigDecimal("2455200"), // total amount
                9 // pending cheques
        );
    }

    /** STATIC DATA — rows for the "Recent Batches" list (full set, before filter/paging). */
    private List<RecentBatchRow> getRecentBatches() {
        List<RecentBatchRow> rows = new ArrayList<>();
        rows.add(new RecentBatchRow("Inward", "Batch_005", "18 Jun", 10, "Pending"));
        rows.add(new RecentBatchRow("Outward", "BATCH20102", "20 Jun", 8, "Verified"));
        rows.add(new RecentBatchRow("Inward", "Batch_004", "16 Jun", 15, "PendingAtChecker"));
        rows.add(new RecentBatchRow("Outward", "BATCH20101", "20 Jun", 6, "Verified"));
        rows.add(new RecentBatchRow("Inward", "Batch_003", "17 Jun", 10, "PendingAtChecker"));
        rows.add(new RecentBatchRow("Outward", "BATCH20100", "19 Jun", 5, "Verified"));
        rows.add(new RecentBatchRow("Inward", "Batch_002", "15 Jun", 9, "Pending"));
        return rows;
    }

    /** STATIC DATA — numbers for the 4 analytics metric cards. */
    private MetricCardData getMetrics() {
        return new MetricCardData(
                "3.2h", // avg verify time
                "4.1%", // rejection rate
                "6.8%", // MICR repair rate
                "12" // high value cheques
        );
    }

    /** STATIC DATA — numbers for the charts (volume, amount x2, branch). */
    private ChartsData getChartsData() {
        return new ChartsData(
                new String[] { "15 Jun", "16 Jun", "17 Jun", "18 Jun", "19 Jun", "20 Jun", "21 Jun" },
                new int[] { 8, 12, 10, 15, 9, 14, 11 }, // volume — inward
                new int[] { 6, 9, 7, 8, 6, 10, 8 }, // volume — outward
                new int[] { 18, 22, 15, 28, 20, 25, 19 }, // amount in lakh — outward
                new int[] { 20, 19, 17, 24, 22, 21, 23 }, // amount in lakh — inward
                new String[] { "Connaught Place", "Karol Bagh", "Lajpat Nagar", "Dwarka" },
                new int[] { 14, 9, 11, 7 } // volume by branch
        );
    }

    /** STATIC DATA — text for the Forecast card and the Rejection Insight card. */
    private InsightsData getInsights() {
        return new InsightsData(
                "~78 cheques",
                "Based on last 30 days average, trending up 6% vs previous week",
                "Rejection rate up 2.1% vs last week, mostly from Batch_004 (MICR mismatch)",
                9, // MICR mismatch count
                4, // signature mismatch count
                2 // insufficient funds count
        );
    }

    // ════════════════════════════════════════════════════════════
    // SCREEN FILLING — puts the data above onto the page.
    // ════════════════════════════════════════════════════════════

    private void fillKpiCards(DashboardKpiData kpi) {
        setText(lblTotalBatchesKpi, String.valueOf(kpi.totalBatches));
        setText(lblPendingVerifyKpi, String.valueOf(kpi.pendingVerification));
        setText(lblVerifiedKpi, String.valueOf(kpi.verifiedProcessed));
        setText(lblDispatchedKpi, String.valueOf(kpi.dispatchedBatches));
    }

    private void fillInwardPanel(ModuleStats inward) {
        setText(lblInwardTotalBatches, String.valueOf(inward.totalBatches));
        setText(lblInwardTotalCheques, String.valueOf(inward.totalCheques));
        setText(lblInwardTotalAmount, formatAsRupees(inward.totalAmount));
        setText(lblInwardPending, String.valueOf(inward.pendingCheques));
    }

    private void fillOutwardPanel(ModuleStats outward) {
        setText(lblOutwardTotalBatches, String.valueOf(outward.totalBatches));
        setText(lblOutwardTotalCheques, String.valueOf(outward.totalCheques));
        setText(lblOutwardTotalAmount, formatAsRupees(outward.totalAmount));
        setText(lblOutwardPending, String.valueOf(outward.pendingCheques));
    }

    /** Renders one page's worth of rows into the listbox (replaces old rows). */
    private void renderRows(List<RecentBatchRow> rows) {
        if (recentBatchListbox == null)
            return;

        recentBatchListbox.getItems().clear();
        for (RecentBatchRow row : rows) {
            recentBatchListbox.appendChild(buildListRow(row));
        }
    }

    /** Updates the "Showing X of Y batches" hint and "Page N of M" label. */
    private void renderPaginationInfo(int totalFiltered, int rowsOnThisPage, int totalPages) {
        String summaryText = "Showing " + rowsOnThisPage + " of " + totalFiltered + " batches";
        setText(lblRecentBatchCount, summaryText);
        setText(lblRecentBatchPageHint, summaryText);
        setText(lblRecentBatchPageInfo, "Page " + currentPage + " of " + totalPages);
    }

    /**
     * Builds one table row for the Recent Batches list: MODULE / BATCH ID / CREATED
     * / CHEQUES / STATUS.
     */
    private Listitem buildListRow(RecentBatchRow row) {
        Listitem item = new Listitem();

        item.appendChild(new Listcell(row.module));
        item.appendChild(new Listcell(row.batchId));
        item.appendChild(new Listcell(row.createdDate));
        item.appendChild(new Listcell(String.valueOf(row.chequeCount)));

        Listcell statusCell = new Listcell();
        Label statusLabel = new Label(row.status);
        statusLabel.setSclass(statusBadgeStyle(row.status));
        statusCell.appendChild(statusLabel);
        item.appendChild(statusCell);

        return item;
    }

    /** Picks the right color style (CSS class) for a status badge. */
    private String statusBadgeStyle(String status) {
        if ("Verified".equals(status)) {
            return "cts-badge cts-badge-verified";
        }
        // Pending, PendingAtChecker, or anything else we don't know yet
        return "cts-badge cts-badge-pending";
    }

    private void fillMetricCards(MetricCardData metrics) {
        setText(lblMetricAvgVerifyTime, metrics.avgVerifyTime);
        setText(lblMetricRejectionRate, metrics.rejectionRate);
        setText(lblMetricMicrRepairRate, metrics.micrRepairRate);
        setText(lblMetricHighValueCheques, metrics.highValueCheques);
    }

    private void fillInsights(InsightsData insights) {
        setText(lblForecastValue, insights.forecastValue);
        setText(lblForecastDesc, insights.forecastDesc);
        setText(lblRejectionInsightText, insights.rejectionInsightText);
        setText(lblRejectionMicr, String.valueOf(insights.micrMismatchCount));
        setText(lblRejectionSignature, String.valueOf(insights.signatureMismatchCount));
        setText(lblRejectionInsufficientFunds, String.valueOf(insights.insufficientFundsCount));
    }

    /**
     * Sends the chart numbers to the browser so Chart.js can draw them.
     * dashboard.zul has a JavaScript function called
     * ctsRenderChartsWithData(...) that does the actual drawing — this
     * method just calls it and gives it the numbers as simple JSON text.
     */
    private void drawCharts(ChartsData charts) {
        String json = toJson(charts);
        Clients.evalJavaScript("ctsRenderChartsWithData('" + json.replace("'", "\\'") + "');");
    }

    /** Turns the chart numbers into a plain JSON text string for the browser. */
    private String toJson(ChartsData charts) {
        return "{"
                + "\"days\":" + jsonStrings(charts.days) + ","
                + "\"volumeInward\":" + jsonNumbers(charts.volumeInward) + ","
                + "\"volumeOutward\":" + jsonNumbers(charts.volumeOutward) + ","
                + "\"amountOutward\":" + jsonNumbers(charts.amountOutward) + ","
                + "\"amountInward\":" + jsonNumbers(charts.amountInward) + ","
                + "\"branchLabels\":" + jsonStrings(charts.branchLabels) + ","
                + "\"branchVolume\":" + jsonNumbers(charts.branchVolume)
                + "}";
    }

    /** Turns a list of words into JSON text, e.g. ["Mon","Tue"]. */
    private String jsonStrings(String[] values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0)
                text.append(",");
            text.append("\"").append(values[i].replace("\"", "\\\"")).append("\"");
        }
        return text.append("]").toString();
    }

    /** Turns a list of numbers into JSON text, e.g. [1,2,3]. */
    private String jsonNumbers(int[] values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0)
                text.append(",");
            text.append(values[i]);
        }
        return text.append("]").toString();
    }

    // ════════════════════════════════════════════════════════════
    // SMALL HELPERS
    // ════════════════════════════════════════════════════════════

    /** Sets a label's text, but never crashes if the label is missing. */
    private void setText(Label label, String value) {
        if (label != null) {
            label.setValue(value != null ? value : "0");
        }
    }

    /** Turns a number into a rupee string, like ₹24,55,200.00. */
    private String formatAsRupees(BigDecimal amount) {
        if (amount == null)
            return "₹0.00";
        return "₹" + String.format("%,.2f", amount);
    }

    // ════════════════════════════════════════════════════════════
    // SIMPLE DATA HOLDERS — just carry numbers from one method to
    // another. New service-based methods you write later should
    // return these same types.
    // ════════════════════════════════════════════════════════════

    /** The 4 numbers shown on the top KPI cards. */
    private static class DashboardKpiData {
        final int totalBatches;
        final int pendingVerification;
        final int verifiedProcessed;
        final int dispatchedBatches;

        DashboardKpiData(int totalBatches, int pendingVerification, int verifiedProcessed, int dispatchedBatches) {
            this.totalBatches = totalBatches;
            this.pendingVerification = pendingVerification;
            this.verifiedProcessed = verifiedProcessed;
            this.dispatchedBatches = dispatchedBatches;
        }
    }

    /**
     * The numbers shown on the Inward panel or the Outward panel (same shape for
     * both).
     */
    private static class ModuleStats {
        final int totalBatches;
        final int totalCheques;
        final BigDecimal totalAmount;
        final int pendingCheques;

        ModuleStats(int totalBatches, int totalCheques, BigDecimal totalAmount, int pendingCheques) {
            this.totalBatches = totalBatches;
            this.totalCheques = totalCheques;
            this.totalAmount = totalAmount;
            this.pendingCheques = pendingCheques;
        }
    }

    /** One row in the Recent Batches list. */
    private static class RecentBatchRow {
        final String module; // "Inward" or "Outward"
        final String batchId;
        final String createdDate;
        final int chequeCount;
        final String status;

        RecentBatchRow(String module, String batchId, String createdDate, int chequeCount, String status) {
            this.module = module;
            this.batchId = batchId;
            this.createdDate = createdDate;
            this.chequeCount = chequeCount;
            this.status = status;
        }
    }

    /**
     * Text shown on the 4 analytics metric cards (already formatted, e.g. "3.2h",
     * "4.1%").
     */
    private static class MetricCardData {
        final String avgVerifyTime;
        final String rejectionRate;
        final String micrRepairRate;
        final String highValueCheques;

        MetricCardData(String avgVerifyTime, String rejectionRate, String micrRepairRate, String highValueCheques) {
            this.avgVerifyTime = avgVerifyTime;
            this.rejectionRate = rejectionRate;
            this.micrRepairRate = micrRepairRate;
            this.highValueCheques = highValueCheques;
        }
    }

    /** Numbers for all 4 charts on the page. */
    private static class ChartsData {
        final String[] days;
        final int[] volumeInward;
        final int[] volumeOutward;
        final int[] amountOutward;
        final int[] amountInward;
        final String[] branchLabels;
        final int[] branchVolume;

        ChartsData(String[] days, int[] volumeInward, int[] volumeOutward,
                int[] amountOutward, int[] amountInward,
                String[] branchLabels, int[] branchVolume) {
            this.days = days;
            this.volumeInward = volumeInward;
            this.volumeOutward = volumeOutward;
            this.amountOutward = amountOutward;
            this.amountInward = amountInward;
            this.branchLabels = branchLabels;
            this.branchVolume = branchVolume;
        }
    }

    /** Text for the Forecast card and the Rejection Insight card. */
    private static class InsightsData {
        final String forecastValue;
        final String forecastDesc;
        final String rejectionInsightText;
        final int micrMismatchCount;
        final int signatureMismatchCount;
        final int insufficientFundsCount;

        InsightsData(String forecastValue, String forecastDesc, String rejectionInsightText,
                int micrMismatchCount, int signatureMismatchCount, int insufficientFundsCount) {
            this.forecastValue = forecastValue;
            this.forecastDesc = forecastDesc;
            this.rejectionInsightText = rejectionInsightText;
            this.micrMismatchCount = micrMismatchCount;
            this.signatureMismatchCount = signatureMismatchCount;
            this.insufficientFundsCount = insufficientFundsCount;
        }
    }
}