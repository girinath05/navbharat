/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — My Batches Screen
 *  File        : MyBatchesComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  ZK SelectorComposer that drives the "My Batches" screen
 *  (batchManagement.zul).
 *
 *  Responsibilities:
 *    1. Display a paginated, filterable list of all CTS batches
 *       regardless of status (Draft, Pending, Submitted, etc.).
 *    2. Allow the Maker to open any batch row to view / edit it
 *       in the Batch Detail screen.
 *    3. Allow the Maker to submit a fully-verified batch to the
 *       V1 verifier via the inline "Save Batch" button (shown
 *       only when every cheque in the batch has passed all edits).
 *    4. Navigate to the Scan Module with the Create Batch modal
 *       pre-opened when "Create Batch" is clicked.
 *
 * ──────────────────────────────────────────────────────────────
 *  NAVIGATION — WHO OPENS THIS SCREEN
 * ──────────────────────────────────────────────────────────────
 *  1. SidebarComposer.onMenuMyBatches()
 *       → DashboardComposer.loadPage("/zul/outward/batchManagement.zul")
 *
 *  2. BatchChequeEntryComposer.onViewBatches()
 *       → DashboardComposer.loadPage("/zul/outward/batchManagement.zul")
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS CLASS FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  Browser (ZUL)                Server (Composer)              DB / Service
 *  ──────────────────────────   ───────────────────────────    ─────────────────────────
 *  batchManagement.zul ──load──► MyBatchesComposer              Supabase PostgreSQL
 *    Toolbar (search/status/            ↕ BatchService            cts_batches table
 *             date filters)             ↕ BatchServiceImpl          countBatches()
 *    Listbox (paginated rows)                ↕ BatchDAOImpl          getBatchesPage()
 *    Pagination bar                              ↕ HibernateUtil      getReadyBatchIds()
 *    "Create Batch" button                       ↕ SessionFactory
 *
 * ──────────────────────────────────────────────────────────────
 *  DB QUERY STRATEGY (PERFORMANCE)
 * ──────────────────────────────────────────────────────────────
 *  ALL filtering and pagination is pushed to Postgres — never
 *  done in Java memory. Every render issues exactly 3 SQL calls:
 *
 *    1. countBatches(search, status, fromDate, toDate)
 *         → Returns total matching row count for pagination math.
 *
 *    2. getBatchesPage(search, status, fromDate, toDate, limit, page)
 *         → Returns exactly ≤ PAGE_SIZE (5) rows for this page.
 *
 *    3. getReadyBatchIds(pageIds)
 *         → Returns the subset of on-page batch IDs where every
 *           cheque has ver_status = 'Verified' (qualifies for
 *           submit). Runs against ≤ 5 IDs — never the full table.
 *
 *  Total: 3 queries per render, constant regardless of DB size.
 *
 * ──────────────────────────────────────────────────────────────
 *  LIFECYCLE  (ZK call order)
 * ──────────────────────────────────────────────────────────────
 *  doAfterCompose(comp)
 *    1. guardSession()       → redirect to login if no session
 *    2. cmbStatus init       → set default label "All Status"
 *    3. loadPage()           → count + fetch page + render rows
 *
 * ──────────────────────────────────────────────────────────────
 *  FILTER STATE MACHINE
 * ──────────────────────────────────────────────────────────────
 *  All four filter fields (search, status, fromDate, toDate) are
 *  held as lightweight String fields in this composer instance.
 *  On every filter change or page turn, loadPage() is called —
 *  it reads the current filter state, issues the 3 DB queries,
 *  and re-renders the table. No batch data is ever cached here.
 *
 *  Filter field → internal field → DB parameter mapping:
 *    txtSearch   → filterSearch (raw text, passed as ILIKE %x%)
 *    cmbStatus   → filterStatus (UI label → DB status via resolveDbStatus())
 *    dtFrom      → filterFrom   (java.util.Date → "YYYY-MM-DD" ISO string)
 *    dtTo        → filterTo     (java.util.Date → "YYYY-MM-DD" ISO string)
 *
 * ──────────────────────────────────────────────────────────────
 *  STATUS DISPLAY MAPPING
 * ──────────────────────────────────────────────────────────────
 *  The DB stores granular workflow statuses; the UI collapses
 *  them into three human-readable labels:
 *
 *    DB Status(es)                          → UI Label
 *    ─────────────────────────────────────────────────
 *    null / "Draft"                         → "Draft"
 *    "VerificationInProgressAtMaker"        → "Pending"
 *    "ReadyForVerification"
 *    "VerificationInProgress"
 *    "Verified" / "CxfGenerated"
 *    "Dispatched"                           → "Submitted"
 *
 * ──────────────────────────────────────────────────────────────
 *  SUBMIT ELIGIBILITY ("Save Batch" button visibility)
 * ──────────────────────────────────────────────────────────────
 *  The "Save Batch" action button appears in the ACTION column
 *  only when ALL cheques in the batch have ver_status = 'Verified'
 *  (determined by getReadyBatchIds() — a single IN-clause query).
 *
 *  When clicked, submitBatch() calls BatchService.submitBatch()
 *  which transitions the batch status:
 *    "VerificationInProgressAtMaker" → "ReadyForVerification"
 *
 *  After submit, the row status chip changes to "Submitted" and
 *  the "Save Batch" button disappears on the next loadPage().
 *
 * ──────────────────────────────────────────────────────────────
 *  SESSION ATTRIBUTES USED
 * ──────────────────────────────────────────────────────────────
 *  Read:
 *    "loggedUser"           → session guard (null = not logged in)
 *
 *  Written (for downstream screens):
 *    "selectedBatchId"      → read by BatchDetailComposer on load
 *    "autoOpenBatchModal"   → read by BatchChequeEntryComposer
 *                              to auto-open the Create Batch modal
 * ============================================================
 */

package com.cts.outward.composer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.exception.BatchSubmitException;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;

/**
 * ZK SelectorComposer for {@code batchManagement.zul} — the "My Batches" screen.
 *
 * <p>Provides a server-side-paginated, filterable view of all CTS batches for the
 * currently logged-in Maker. Every DB query uses limit/offset pushed to Postgres;
 * no batch data is held in memory between renders.
 *
 * <h3>Navigation entry points</h3>
 * <ul>
 *   <li>{@code SidebarComposer.onMenuMyBatches()} — sidebar "My Batches" link</li>
 *   <li>{@code BatchChequeEntryComposer.onViewBatches()} — "View Batches" button</li>
 * </ul>
 *
 * @author Umesh M.
 * @see BatchService
 * @see BatchServiceImpl
 */
public class MyBatchesComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;

    /** Logger for service errors and submit audit trail. */
    private static final Logger LOG = Logger.getLogger(MyBatchesComposer.class.getName());

    /** Session attribute key set by LoginComposer on successful authentication. */
    private static final String SESS_LOGGED_USER = "loggedUser";

    /** Date formatter for displaying batch creation dates in DD/MM/YYYY format. */
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ══════════════════════════════════════════════════════════════════════
    // PAGINATION STATE
    // ══════════════════════════════════════════════════════════════════════

    /** Number of batch rows displayed per page in the table. */
    private static final int PAGE_SIZE = 5;

    /** Current active page index (1-based). Resets to 1 on any filter change. */
    private int currentPage = 1;

    /**
     * Total number of pages for the current filter set.
     * Computed from {@link #totalRows} / {@link #PAGE_SIZE} after each DB count query.
     */
    private int totalPages = 1;

    /**
     * Total number of rows matching the current filter set.
     * Populated by {@code BatchService.countBatches()} on every {@link #loadPage()} call.
     */
    private long totalRows = 0;

    // ══════════════════════════════════════════════════════════════════════
    // ACTIVE FILTER STATE
    // These lightweight String fields are the sole in-memory state.
    // They are sent to the DB on every loadPage() call via BatchService.
    // No batch list is ever stored in this composer.
    // ══════════════════════════════════════════════════════════════════════

    /** Free-text search term applied as ILIKE %term% on batchId and branchCode. */
    private String filterSearch = "";

    /**
     * DB status value to filter by; empty string = "show all statuses".
     * Set via {@link #resolveDbStatus(String)} from the UI combo label.
     */
    private String filterStatus = "";

    /**
     * ISO-8601 date string ("YYYY-MM-DD") for the lower bound of the date range filter.
     * Null means no lower bound. Converted from ZK Datebox via {@link #toIsoDate(Date)}.
     */
    private String filterFromDate = null;

    /**
     * ISO-8601 date string ("YYYY-MM-DD") for the upper bound of the date range filter.
     * Null means no upper bound. Converted from ZK Datebox via {@link #toIsoDate(Date)}.
     */
    private String filterToDate = null;

    // ══════════════════════════════════════════════════════════════════════
    // SERVICE LAYER (manual wiring — no DI framework in this project)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * BatchService: all batch read/write operations used by this composer:
     * <ul>
     *   <li>{@code countBatches()}     — total row count for pagination</li>
     *   <li>{@code getBatchesPage()}   — paginated batch slice</li>
     *   <li>{@code getReadyBatchIds()} — submit eligibility check</li>
     *   <li>{@code submitBatch()}      — status transition to ReadyForVerification</li>
     * </ul>
     */
    private final BatchService batchService = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());

    // ══════════════════════════════════════════════════════════════════════
    // WIRED ZK COMPONENTS — TOOLBAR / FILTER CONTROLS
    // ══════════════════════════════════════════════════════════════════════

    /** Displays "N batches" count badge above the table. */
    @Wire("  #lblBatchCount")
    private Label lblBatchCount;

    /** Free-text search input — fires onChanging (per-keystroke) for live filtering. */
    @Wire("#txtSearch")
    private Textbox txtSearch;

    /** Status filter combo — "All Status / Draft / Pending / Submitted". */
    @Wire("#cmbStatus")
    private Combobox cmbStatus;

    /** Date-range "from" picker — filters on batch.createdAt >= fromDate. */
    @Wire("#dtFrom")
    private Datebox dtFrom;

    /** Date-range "to" picker — filters on batch.createdAt <= toDate. */
    @Wire("#dtTo")
    private Datebox dtTo;

    // ══════════════════════════════════════════════════════════════════════
    // WIRED ZK COMPONENTS — BATCH TABLE
    // ══════════════════════════════════════════════════════════════════════

    /** The main paginated batch table. Rows are rebuilt on every loadPage() call. */
    @Wire("#lbBatches")
    private Listbox lbBatches;

    // ══════════════════════════════════════════════════════════════════════
    // WIRED ZK COMPONENTS — PAGINATION BAR
    // ══════════════════════════════════════════════════════════════════════

    /** Navigates to the first page of results. Disabled when currentPage == 1. */
    @Wire("#btnPgFirst")
    private Button btnPgFirst;

    /** Navigates to the previous page. Disabled when currentPage == 1. */
    @Wire("#btnPgPrev")
    private Button btnPgPrev;

    /** Navigates to the next page. Disabled when currentPage == totalPages. */
    @Wire("#btnPgNext")
    private Button btnPgNext;

    /** Navigates to the last page of results. Disabled when currentPage == totalPages. */
    @Wire("#btnPgLast")
    private Button btnPgLast;

    /** Displays "Page X of Y" in the pagination bar. */
    @Wire("#lblPgInfo")
    private Label lblPgInfo;

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE — doAfterCompose
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ZK lifecycle entry point — called once after the ZUL component tree is fully
     * parsed and all {@code @Wire} fields are injected by ZK.
     *
     * <h3>Initialization order</h3>
     * <ol>
     *   <li>{@link #guardSession()} — redirect to login if not authenticated</li>
     *   <li>Set cmbStatus default label to "All Status"</li>
     *   <li>{@link #loadPage()} — issues 3 DB queries and renders first page</li>
     * </ol>
     *
     * <h3>Called by</h3>
     * ZK framework automatically after component tree construction for
     * {@code batchManagement.zul}.
     *
     * @param comp root ZK component of batchManagement.zul
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        guardSession();
        if (cmbStatus != null) cmbStatus.setValue("All Status");
        loadPage();
    }

    // ══════════════════════════════════════════════════════════════════════
    // FILTER EVENT LISTENERS
    // Each listener updates the relevant filter field, resets to page 1,
    // and calls loadPage() to re-query the DB.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Live-search handler — fires on every keystroke in the search box.
     *
     * <p>Updates {@link #filterSearch} and reloads from page 1 immediately,
     * giving the user instant feedback without requiring a form submit.
     *
     * <h3>Triggered by</h3>
     * {@code onChanging = #txtSearch} in batchManagement.zul
     *
     * <h3>Call chain</h3>
     * <pre>
     * txtSearch keystroke
     *   → onSearch(InputEvent)
     *       → filterSearch = event.getValue()
     *       → currentPage = 1
     *       → loadPage()  [3 DB queries + re-render]
     * </pre>
     *
     * @param event ZK InputEvent carrying the current text-box value
     */
    @Listen("onChanging = #txtSearch")
    public void onSearch(org.zkoss.zk.ui.event.InputEvent event) {
        filterSearch = event.getValue() != null ? event.getValue().trim() : "";
        currentPage = 1;
        loadPage();
    }

    /**
     * Status combo selection handler.
     *
     * <p>Maps the UI display label ("Draft" / "Pending" / "Submitted" / "All Status")
     * to the corresponding DB status value via {@link #resolveDbStatus(String)},
     * then reloads from page 1.
     *
     * <h3>Triggered by</h3>
     * {@code onSelect = #cmbStatus} in batchManagement.zul
     *
     * <h3>Call chain</h3>
     * <pre>
     * cmbStatus selection
     *   → onStatusFilter()
     *       → filterStatus = resolveDbStatus(cmbStatus.getValue())
     *       → currentPage = 1
     *       → loadPage()
     * </pre>
     */
    @Listen("onSelect = #cmbStatus")
    public void onStatusFilter() {
        filterStatus = resolveDbStatus(cmbStatus != null ? cmbStatus.getValue() : null);
        currentPage = 1;
        loadPage();
    }

    /**
     * "From date" picker change handler.
     *
     * <p>Converts the selected {@link Date} to an ISO-8601 string and stores it in
     * {@link #filterFromDate} for inclusion in the next DB query.
     *
     * <h3>Triggered by</h3>
     * {@code onChange = #dtFrom} in batchManagement.zul
     *
     * <h3>Call chain</h3>
     * <pre>
     * dtFrom selection
     *   → onFromDateFilter()
     *       → filterFromDate = toIsoDate(dtFrom.getValue())  ["YYYY-MM-DD" or null]
     *       → currentPage = 1
     *       → loadPage()
     * </pre>
     */
    @Listen("onChange = #dtFrom")
    public void onFromDateFilter() {
        filterFromDate = toIsoDate(dtFrom != null ? dtFrom.getValue() : null);
        currentPage = 1;
        loadPage();
    }

    /**
     * "To date" picker change handler.
     *
     * <p>Converts the selected {@link Date} to an ISO-8601 string and stores it in
     * {@link #filterToDate} for inclusion in the next DB query.
     *
     * <h3>Triggered by</h3>
     * {@code onChange = #dtTo} in batchManagement.zul
     *
     * <h3>Call chain</h3>
     * <pre>
     * dtTo selection
     *   → onToDateFilter()
     *       → filterToDate = toIsoDate(dtTo.getValue())
     *       → currentPage = 1
     *       → loadPage()
     * </pre>
     */
    @Listen("onChange = #dtTo")
    public void onToDateFilter() {
        filterToDate = toIsoDate(dtTo != null ? dtTo.getValue() : null);
        currentPage = 1;
        loadPage();
    }

    /**
     * Clears both date pickers and removes the date range filter.
     *
     * <p>Resets {@link #filterFromDate} and {@link #filterToDate} to null, clears
     * the ZK Datebox UI values, and reloads from page 1.
     *
     * <h3>Triggered by</h3>
     * {@code onClick = #btnClearDate} in batchManagement.zul
     */
    @Listen("onClick = #btnClearDate")
    public void onClearDate() {
        if (dtFrom != null) dtFrom.setValue((Date) null);
        if (dtTo != null) dtTo.setValue((Date) null);
        filterFromDate = null;
        filterToDate = null;
        currentPage = 1;
        loadPage();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PAGINATION EVENT LISTENERS
    // Each listener updates currentPage and calls loadPage().
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Navigates to the first page.
     * Triggered by: {@code onClick = #btnPgFirst}
     */
    @Listen("onClick = #btnPgFirst")
    public void onPgFirst() {
        currentPage = 1;
        loadPage();
    }

    /**
     * Navigates to the previous page (no-op if already on page 1).
     * Triggered by: {@code onClick = #btnPgPrev}
     */
    @Listen("onClick = #btnPgPrev")
    public void onPgPrev() {
        if (currentPage > 1) {
            currentPage--;
            loadPage();
        }
    }

    /**
     * Navigates to the next page (no-op if already on the last page).
     * Triggered by: {@code onClick = #btnPgNext}
     */
    @Listen("onClick = #btnPgNext")
    public void onPgNext() {
        if (currentPage < totalPages) {
            currentPage++;
            loadPage();
        }
    }

    /**
     * Navigates to the last page.
     * Triggered by: {@code onClick = #btnPgLast}
     */
    @Listen("onClick = #btnPgLast")
    public void onPgLast() {
        currentPage = totalPages;
        loadPage();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TOP-LEVEL ACTIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Navigates to the Scan Module with the Create Batch modal pre-opened.
     *
     * <p>Sets the {@code "autoOpenBatchModal"} session flag, which is read by
     * {@code BatchChequeEntryComposer.doAfterCompose()} to automatically open the
     * Step 1 "Create Batch" modal without requiring an extra button click.
     *
     * <h3>Triggered by</h3>
     * {@code onClick = #btnCreateBatch} in batchManagement.zul
     *
     * <h3>Call chain</h3>
     * <pre>
     * "Create Batch" button click
     *   → onCreateBatch()
     *       → session["autoOpenBatchModal"] = true
     *       → DashboardComposer.loadPage("/zul/outward/scanModule.zul")
     *           → BatchChequeEntryComposer.doAfterCompose()
     *               → openBatchModal()   [auto-triggered by flag]
     * </pre>
     */
    @Listen("onClick = #btnCreateBatch")
    public void onCreateBatch() {
        Sessions.getCurrent().setAttribute("autoOpenBatchModal", true);
        com.cts.composer.DashboardComposer.navigateTo("/zul/outward/cheque-scan.zul");
    }

    // ══════════════════════════════════════════════════════════════════════
    // CORE LOAD — DB QUERY + RENDER
    // Single entry point for all page loads, filter changes, and page turns.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Issues the minimum necessary DB queries to render the current page of results.
     *
     * <p>This is the central method of this composer — every filter change, page turn,
     * and initial load flows through here. It performs exactly 3 DB queries:
     *
     * <ol>
     *   <li><b>countBatches()</b> — determines total matching rows for pagination math</li>
     *   <li><b>getBatchesPage()</b> — fetches exactly ≤ PAGE_SIZE rows for the current page</li>
     *   <li><b>getReadyBatchIds()</b> — checks which on-page batches are eligible for submit</li>
     * </ol>
     *
     * <h3>Called by</h3>
     * <ul>
     *   <li>{@link #doAfterCompose(Component)} — initial page load</li>
     *   <li>{@link #onSearch(org.zkoss.zk.ui.event.InputEvent)} — live text filter</li>
     *   <li>{@link #onStatusFilter()} — status combo change</li>
     *   <li>{@link #onFromDateFilter()} / {@link #onToDateFilter()} — date range change</li>
     *   <li>{@link #onClearDate()} — date filter clear</li>
     *   <li>{@link #onPgFirst()} / {@link #onPgPrev()} / {@link #onPgNext()} / {@link #onPgLast()}</li>
     *   <li>{@link #submitBatch(String)} — reload after status change</li>
     * </ul>
     *
     * <h3>Filter parameters used</h3>
     * <ul>
     *   <li>{@link #filterSearch} — free-text on batchId/branchCode</li>
     *   <li>{@link #filterStatus} — DB status string or "" for all</li>
     *   <li>{@link #filterFromDate} — ISO date string or null</li>
     *   <li>{@link #filterToDate} — ISO date string or null</li>
     * </ul>
     */
    private void loadPage() {
        // ── Step 1: Count total matching rows (needed for pagination math) ──
        try {
            totalRows = batchService.countBatches(filterSearch, filterStatus, filterFromDate, filterToDate);
        } catch (Exception countException) {
            LOG.severe("countBatches error: " + countException.getMessage());
            totalRows = 0;
        }

        // Compute total pages; minimum of 1 even when there are no results
        totalPages = (totalRows == 0) ? 1 : (int) Math.ceil((double) totalRows / PAGE_SIZE);

        // Clamp current page to valid range (can go out of range after filter narrows results)
        if (currentPage > totalPages) currentPage = totalPages;
        if (currentPage < 1) currentPage = 1;

        // ── Step 2: Fetch the page slice from DB ────────────────────────────
        List<BatchEntity> currentPageBatches;
        try {
            currentPageBatches = batchService.getBatchesPage(
                filterSearch, filterStatus, filterFromDate, filterToDate,
                PAGE_SIZE, currentPage
            );
        } catch (Exception pageException) {
            LOG.severe("getBatchesPage error: " + pageException.getMessage());
            currentPageBatches = Collections.emptyList();
        }

        // ── Step 3: Render the page (includes one more query for readyIds) ──
        renderPage(currentPageBatches);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TABLE RENDER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Rebuilds all Listitem rows in {@code lbBatches} for the given page slice,
     * and updates the pagination bar controls.
     *
     * <p>Fetches submit-eligibility for all on-page batch IDs in a single batched
     * query ({@code getReadyBatchIds()}) — never per-row. The "Save Batch" button
     * is rendered only for rows whose batch ID appears in the returned set.
     *
     * <h3>Table columns rendered</h3>
     * <ol>
     *   <li>Batch ID (clickable link → navigates to Batch Detail)</li>
     *   <li>Cheque Count</li>
     *   <li>Total Amount (right-aligned, formatted with ₹)</li>
     *   <li>Created Date (DD/MM/YYYY)</li>
     *   <li>Status chip (colour-coded: Draft/Pending/Submitted)</li>
     *   <li>Action (View & Edit link + optional "Save Batch" button)</li>
     * </ol>
     *
     * <h3>Called by</h3>
     * {@link #loadPage()} after fetching the page slice.
     *
     * @param currentPageBatches page slice returned by {@code BatchService.getBatchesPage()}
     */
    private void renderPage(List<BatchEntity> currentPageBatches) {
        if (lbBatches == null) return;

        // Clear all previous rows before rebuilding
        lbBatches.getItems().clear();

        // ── Update batch count badge ────────────────────────────────────────
        if (lblBatchCount != null)
            lblBatchCount.setValue(totalRows + " batch" + (totalRows != 1 ? "es" : ""));

        // ── Update pagination bar ───────────────────────────────────────────
        if (lblPgInfo != null)
            lblPgInfo.setValue("Page " + currentPage + " of " + totalPages);
        if (btnPgFirst != null) btnPgFirst.setDisabled(currentPage <= 1);
        if (btnPgPrev != null) btnPgPrev.setDisabled(currentPage <= 1);
        if (btnPgNext != null) btnPgNext.setDisabled(currentPage >= totalPages);
        if (btnPgLast != null) btnPgLast.setDisabled(currentPage >= totalPages);

        if (currentPageBatches == null || currentPageBatches.isEmpty()) return;

        // ── Pre-fetch submit eligibility for all on-page IDs (ONE query) ───
        // Avoids N+1 problem: instead of checking each batch's cheque statuses
        // row by row, a single IN-clause query returns all eligible batch IDs.
        List<String> onPageBatchIds = currentPageBatches.stream()
            .map(BatchEntity::getBatchId)
            .filter(id -> id != null)
            .collect(Collectors.toList());

        Set<String> submitEligibleBatchIds = Collections.emptySet();
        try {
            submitEligibleBatchIds = batchService.getReadyBatchIds(onPageBatchIds);
        } catch (Exception readyException) {
            LOG.warning("getReadyBatchIds error: " + readyException.getMessage());
            // Non-fatal — "Save Batch" buttons simply won't appear this render
        }

        // ── Build one Listitem per batch ────────────────────────────────────
        for (BatchEntity batchEntity : currentPageBatches) {
            appendBatchRow(batchEntity, submitEligibleBatchIds);
        }
    }

    /**
     * Builds and appends one Listitem row for the given batch to {@code lbBatches}.
     *
     * <p>The entire row (except the "Save Batch" button) is wired with an onClick
     * listener that navigates to the Batch Detail screen. The "Save Batch" button
     * uses {@code stopPropagation()} to prevent the row click from also firing.
     *
     * <h3>Called by</h3>
     * {@link #renderPage(List)} — once per batch in the current page slice.
     *
     * @param batchEntity            the batch to render
     * @param submitEligibleBatchIds set of batchIds that qualify for submission
     *                               (all cheques verified); used to conditionally
     *                               render the "Save Batch" action button
     */
    private void appendBatchRow(BatchEntity batchEntity, Set<String> submitEligibleBatchIds) {
        Listitem row = new Listitem();
        row.setSclass("mb-row");

        final String batchId = batchEntity.getBatchId() != null ? batchEntity.getBatchId() : "";

        
        //❤️❤️❤️❤️❤️🙏🙏🙏🙏🙏🙏🙏🙏
        //❤️❤️❤️❤️❤️🙏🙏🙏🙏🙏🙏🙏🙏
        // Entire row click → navigate to Batch Detail ❤️❤️❤️❤️❤️🙏🙏🙏🙏🙏🙏🙏🙏
        if (!batchId.isEmpty()) {
            row.addEventListener("onClick", e -> openBatch(batchId));
        }

        // ── Column 1: Batch ID (styled as clickable link) ───────────────────
        Listcell idCell = new Listcell();
        Label idLabel = new Label(!batchId.isEmpty() ? batchId : "—");
        idLabel.setSclass("mb-link");
        idCell.appendChild(idLabel);
        row.appendChild(idCell);

        // ── Column 2: Total cheque count ────────────────────────────────────
        row.appendChild(buildPlainCell(String.valueOf(batchEntity.getTotalCheques())));

        // ── Column 3: Total amount (right-aligned, ₹ formatted) ─────────────
        row.appendChild(buildAmountCell(formatAmount(batchEntity.getTotalAmount())));

        // ── Column 4: Created date (DD/MM/YYYY) ─────────────────────────────
        String formattedDate = batchEntity.getCreatedAt() != null
            ? batchEntity.getCreatedAt().format(DISPLAY_DATE_FORMAT)
            : "—";
        row.appendChild(buildPlainCell(formattedDate));

        // ── Column 5: Status chip (colour-coded) ────────────────────────────
        String displayStatus = resolveDisplayStatus(batchEntity.getStatus());
        Listcell statusCell = new Listcell();
        Label statusLabel = new Label(displayStatus);
        statusLabel.setSclass(resolveStatusChipCssClass(displayStatus));
        statusCell.appendChild(statusLabel);
        row.appendChild(statusCell);

        // ── Column 6: Action (View link + conditional "Save Batch" button) ──
        Listcell actionCell = new Listcell();

        if ("Submitted".equals(displayStatus)) {
            // Submitted batches show "View" only — no edit or submit action
            Label viewLabel = new Label("View");
            viewLabel.setSclass("mb-action-link");
            actionCell.appendChild(viewLabel);
        } else {
            Label editLabel = new Label("View & Edit");
            editLabel.setSclass("mb-action-link");

            boolean isBatchEligibleForSubmit = !batchId.isEmpty() && submitEligibleBatchIds.contains(batchId);

            if (isBatchEligibleForSubmit) {
                // Show "Save Batch" button alongside the edit link
                // Button uses stopPropagation() so row click does not also fire
                Button submitButton = new Button("Save Batch");
                submitButton.setSclass("btn btn-primary btn-sm");
                final String capturedBatchId = batchId;
                submitButton.addEventListener("onClick", e -> {
                    e.stopPropagation();
                    submitBatch(capturedBatchId);
                });

                Hbox actionContainer = new Hbox();
                actionContainer.setAlign("center");
                actionContainer.setSpacing("12px");
                actionContainer.appendChild(editLabel);
                actionContainer.appendChild(submitButton);
                actionCell.appendChild(actionContainer);
            } else {
                actionCell.appendChild(editLabel);
            }
        }
        row.appendChild(actionCell);
        lbBatches.appendChild(row);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ROW ACTIONS — OPEN BATCH / SUBMIT BATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Stores the selected batchId in the HTTP session and navigates to the
     * Batch Detail screen.
     *
     * <p>The session attribute {@code "selectedBatchId"} is the handoff mechanism
     * to {@code BatchDetailComposer.doAfterCompose()}, which reads it on load.
     *
     * <h3>Called by</h3>
     * Row onClick listener registered in {@link #appendBatchRow(BatchEntity, Set)}.
     *
     * <h3>Call chain</h3>
     * <pre>
     * Row click
     *   → openBatch(batchId)
     *       → session["selectedBatchId"] = batchId
     *       → DashboardComposer.loadPage("/zul/outward/batch-detail.zul")
     *           → BatchDetailComposer.doAfterCompose()
     *               → loadBatchFromSession()   [reads selectedBatchId]
     * </pre>
     *
     * @param batchId the batchId of the clicked row
     */
    private void openBatch(String batchId) {
        Sessions.getCurrent().setAttribute("selectedBatchId", batchId);
        com.cts.composer.DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
    }

    /**
     * Submits the given batch to the V1 verification queue.
     *
     * <p>Delegates to {@code BatchService.submitBatch()} which transitions the
     * batch status from {@code "VerificationInProgressAtMaker"} to
     * {@code "ReadyForVerification"} in the DB. After the status change, the
     * page is reloaded so the row reflects the new "Submitted" state and the
     * "Save Batch" button disappears.
     *
     * <h3>Called by</h3>
     * "Save Batch" button onClick in {@link #appendBatchRow(BatchEntity, Set)}.
     *
     * <h3>Call chain</h3>
     * <pre>
     * "Save Batch" button click
     *   → submitBatch(batchId)
     *       → BatchServiceImpl.submitBatch(batchId)
     *           → BatchDAOImpl.updateStatus(batchId, "ReadyForVerification")
     *       → Clients.showNotification() [success or error]
     *       → loadPage()  [reloads to reflect new status]
     * </pre>
     *
     * <h3>Error handling</h3>
     * <ul>
     *   <li>{@code BatchSubmitException} — business rule violation (e.g., unverified
     *       cheques still present); shown as a user-visible error notification.</li>
     *   <li>General {@code Exception} — DB or network failure; logged and shown
     *       as an error notification.</li>
     * </ul>
     *
     * @param batchId the batchId to submit for verification
     */
    private void submitBatch(String batchId) {
        try {
            batchService.submitBatch(batchId);
            Clients.showNotification(
                "✅ Batch " + batchId + " submitted for Verification.",
                "info", null, "middle_center", 2500
            );
        } catch (BatchSubmitException businessRuleException) {
            Clients.showNotification(
                "❌ Cannot submit — " + businessRuleException.getMessage(),
                "error", null, "middle_center", 3500
            );
        } catch (Exception unexpectedException) {
            LOG.warning("submitBatch error: " + unexpectedException.getMessage());
            Clients.showNotification(
                "❌ Submit failed: " + unexpectedException.getMessage(),
                "error", null, "middle_center", 4000
            );
        }
        // Reload to reflect the updated status in the table
        loadPage();
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS — STATUS MAPPING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Maps a granular DB status value to a simplified UI display label.
     *
     * <p>The workflow has many intermediate DB statuses; the My Batches screen
     * collapses them into three labels the Maker cares about:
     *
     * <pre>
     *  DB Status                           → UI Label
     *  ──────────────────────────────────────────────
     *  null / "Draft"                      → "Draft"
     *  "VerificationInProgressAtMaker"     → "Pending"
     *  "ReadyForVerification"
     *  "VerificationInProgress"
     *  "Verified" / "CxfGenerated"
     *  "Dispatched"                        → "Submitted"
     * </pre>
     *
     * <h3>Called by</h3>
     * {@link #appendBatchRow(BatchEntity, Set)} — once per rendered row.
     *
     * @param rawDbStatus the raw status string from the {@code cts_batches} table
     * @return one of: "Draft", "Pending", "Submitted"
     */
    private String resolveDisplayStatus(String rawDbStatus) {
        BatchStatus batchStatus = BatchStatus.fromDb(rawDbStatus);
        return switch (batchStatus) {
            case READY_FOR_VERIFICATION,
                 VERIFICATION_IN_PROGRESS,
                 VERIFIED,
                 CXF_CIBF_GENERATED,
                 DISPATCHED              -> "Submitted";
            case PENDING                 -> "Pending";
            default                      -> "Draft";
        };
    }

    /**
     * Maps a UI display label to the corresponding primary DB status value.
     * Used to convert the combo-box selection into a DB-level filter parameter.
     *
     * <p>This is the reverse of {@link #resolveDisplayStatus(String)}.
     * "All Status" and null/blank → empty string (no status filter applied).
     *
     * <h3>Called by</h3>
     * {@link #onStatusFilter()} when the status combo selection changes.
     *
     * @param uiDisplayLabel the label shown in cmbStatus ("Draft" / "Pending" /
     *                       "Submitted" / "All Status")
     * @return DB status string to pass to BatchService, or "" for no filter
     */
    private String resolveDbStatus(String uiDisplayLabel) {
        if (uiDisplayLabel == null || uiDisplayLabel.trim().isEmpty()
                || "All Status".equalsIgnoreCase(uiDisplayLabel.trim())) {
            return "";
        }
        return switch (uiDisplayLabel.trim()) {
            case "Submitted" -> BatchStatus.READY_FOR_VERIFICATION.db();
            case "Pending"   -> BatchStatus.PENDING.db();
            default          -> BatchStatus.DRAFT.db();
        };
    }

    /**
     * Returns the CSS sclass string for the status chip label in the table.
     *
     * <p>Chip classes are defined in {@code my-batches.css}:
     * <ul>
     *   <li>{@code "chip ch-submitted"} — blue/green for Submitted</li>
     *   <li>{@code "chip ch-progress"}  — amber/yellow for Pending</li>
     *   <li>{@code "chip ch-pending"}   — grey for Draft</li>
     * </ul>
     *
     * <h3>Called by</h3>
     * {@link #appendBatchRow(BatchEntity, Set)} — once per row.
     *
     * @param uiDisplayLabel the resolved display label ("Draft" / "Pending" / "Submitted")
     * @return CSS sclass string for the ZK Label inside the status cell
     */
    private String resolveStatusChipCssClass(String uiDisplayLabel) {
        if (uiDisplayLabel == null) return "chip ch-pending";
        return switch (uiDisplayLabel) {
            case "Submitted" -> "chip ch-submitted";
            case "Pending"   -> "chip ch-progress";
            default          -> "chip ch-pending";
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS — SESSION GUARD
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Redirects to the login page if no authenticated user is found in session.
     *
     * <p>The {@code "loggedUser"} attribute is set by {@code LoginComposer} on
     * successful login and removed on logout or session timeout.
     *
     * <h3>Called by</h3>
     * {@link #doAfterCompose(Component)} — first action before any DB access.
     */
    private void guardSession() {
        if (!com.cts.util.SecurityUtil.isLoggedIn()) {
            Executions.sendRedirect("/zul/login.zul");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS — DATE CONVERSION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Converts a {@link java.util.Date} (from a ZK Datebox) to an ISO-8601
     * date string ("YYYY-MM-DD") suitable for direct use in SQL WHERE clauses.
     *
     * <p>Returns null if the input date is null, which signals BatchDAOImpl
     * to omit the date bound from the query.
     *
     * <h3>Called by</h3>
     * {@link #onFromDateFilter()} and {@link #onToDateFilter()}.
     *
     * @param utilDate the date value from the ZK Datebox; null if not set
     * @return "YYYY-MM-DD" string, or null if input is null
     */
    private String toIsoDate(Date utilDate) {
        if (utilDate == null) return null;
        LocalDate localDate = utilDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return localDate.toString(); // ISO-8601 format
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS — ZK CELL BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates a plain text Listcell. Displays "—" for null values.
     *
     * @param text cell content; null renders as "—"
     * @return new Listcell with the given text
     */
    private Listcell buildPlainCell(String text) {
        return new Listcell(text != null ? text : "—");
    }

    /**
     * Creates a right-aligned amount Listcell styled with the {@code amt-cell} CSS class.
     *
     * @param formattedAmount pre-formatted amount string (with ₹ symbol)
     * @return new Listcell styled for currency display
     */
    private Listcell buildAmountCell(String formattedAmount) {
        Listcell amountCell = new Listcell(formattedAmount);
        amountCell.setSclass("amt-cell");
        return amountCell;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS — AMOUNT FORMATTING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Formats a BigDecimal amount as a ₹-prefixed, comma-separated Indian numeral.
     *
     * <p>Example: 150000.00 → "₹1,50,000.00"
     *
     * <h3>Called by</h3>
     * {@link #appendBatchRow(BatchEntity, Set)} for the total amount column.
     *
     * @param amount the batch total amount; null renders as "₹0.00"
     * @return formatted amount string with ₹ prefix
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "₹0.00";
        return "₹" + String.format("%,.2f", amount);
    }
}