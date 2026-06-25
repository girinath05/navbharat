/*
 * File        : VerificationOneComposer.java
 * Package     : com.cts.outward.composer
 * Description : ZK SelectorComposer (controller) for the Verification I (Checker) screen.
 *
 *               This class is responsible ONLY for UI concerns:
 *               ─────────────────────────────────────────────────────────────
 *               • Wiring ZK components to Java fields (@Wire).
 *               • Listening to ZK events (@Listen) and calling the service.
 *               • Building Listitem / Listcell rows for the batch and cheque tables.
 *               • Translating service results (booleans, enums) into ZK
 *                 sclass strings and Labels.
 *               • Controlling which panel (Phase 1 vs Phase 2) or popup is visible.
 *
 *               What this class must NOT do
 *               ─────────────────────────────────────────────────────────────
 *               • Directly compare ChequeStatus or BatchStatus enum values to
 *                 make business decisions — call the service for that.
 *               • Contain any "if status == X then Y" rules that belong in the
 *                 domain model.
 *               • Access the database or call CBS directly.
 *
 *               Two-phase navigation
 *               ─────────────────────────────────────────────────────────────
 *               Phase 1 — Batch list:  the verifier sees all V1 batches in a
 *                         searchable, paginated table and clicks "Process" /
 *                         "Resume" / "View" to enter Phase 2.
 *               Phase 2 — Cheque list: shows every cheque in the selected batch.
 *                         Clicking "Open" on a pending cheque opens the popup.
 *               Popup   — Verification popup: displays the cheque image and
 *                         detail fields; provides Accept / Reject / Refer buttons.
 *
 *               User Resolution
 *               ─────────────────────────────────────────────────────────────
 *               The logged-in username is resolved fresh from the ZK session on
 *               every action (Accept / Reject / Refer) via getSessionUser().
 *               This matches the pattern used in VerificationIIComposer and
 *               ensures the correct user is recorded even if the session changes
 *               (e.g. token refresh) after the composer was initialised.
 */
package com.cts.outward.composer;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.zkoss.zk.ui.Component;
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
import com.cts.outward.model.CbsAccountDetails.LookupState;
import com.cts.outward.service.CBSServiceImpl;
import com.cts.outward.service.VerificationOneService;
import com.cts.outward.service.VerificationOneServiceImpl;

public class VerificationOneComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;

    /** URL path of the servlet that streams cheque images from the file store. */
    private static final String CHEQUE_IMAGE_SERVLET = "/chequeImage";

    /**
     * Fallback username used if the security context cannot provide a logged-in user.
     * Applied only when getSessionUser() finds no session — should not occur in normal flow.
     */
    private static final String DEFAULT_USER = "SYSTEM";

    private static final Logger LOG = Logger.getLogger(VerificationOneComposer.class.getName());

    /** Number of batch rows shown per page in the Phase-1 table. */
    private static final int BATCH_PAGE_SIZE  = 5;

    /** Number of cheque rows shown per page in the Phase-2 table. */
    private static final int CHEQUE_PAGE_SIZE = 5;

    /*
     * The service is the single point of entry for all business logic.
     * The composer never touches the DAO or CBS layers directly.
     */
    private final VerificationOneService verificationService = new VerificationOneServiceImpl(
            new BatchDAOImpl(),
            new ChequeDAOImpl(),
            new CBSServiceImpl(new CBSDAOImpl()));

    // ══════════════════════════════════════════════════════════════════════
    // WIRED ZK COMPONENTS  — Phase 1: Batch list panel
    // ══════════════════════════════════════════════════════════════════════

    /** Outer container for the Phase-1 batch list.  Hidden during Phase 2. */
    @Wire private Div     panelBatchList;

    /** Table that holds batch rows built by renderBatchPage(). */
    @Wire private Listbox listBatches;

    /** Shows "Showing X – Y of Z batches". */
    @Wire private Label   labelBatchPagingInfo;

    /** Shows "Page X of Y". */
    @Wire private Label   labelBatchPageNumber;

    /** Navigates to the previous page of batches. */
    @Wire private Button  buttonBatchPrev;

    /** Navigates to the next page of batches. */
    @Wire private Button  buttonBatchNext;

    // ── Phase 1: Batch filter controls ───────────────────────────────────

    /** Free-text search box — filters by Batch ID. */
    @Wire private Textbox  searchBatch;

    /** Date-range lower bound for the batch creation date filter. */
    @Wire private Datebox  dateFromBatch;

    /** Date-range upper bound for the batch creation date filter. */
    @Wire private Datebox  dateToBatch;

    /** Drop-down to filter batches by their current status. */
    @Wire private Combobox comboBatchStatus;

    /** Clears all Phase-1 filter controls and reloads the full list. */
    @Wire private Button   buttonBatchFilterClear;

    // ══════════════════════════════════════════════════════════════════════
    // WIRED ZK COMPONENTS  — Phase 2: Cheque list panel
    // ══════════════════════════════════════════════════════════════════════

    /** Outer container for the Phase-2 cheque list.  Hidden during Phase 1. */
    @Wire private Div     panelChequeList;

    /** Table that holds cheque rows built by renderChequePage(). */
    @Wire private Listbox listCheques;

    /** Shows the count of V1_PENDING cheques in the active batch. */
    @Wire private Label   counterPending;

    /** Shows the count of VERIFIED (accepted) cheques in the active batch. */
    @Wire private Label   counterAccepted;

    /** Shows the count of REJECTED cheques in the active batch. */
    @Wire private Label   counterRejected;

    /** Shows the count of REFERRED cheques in the active batch (may be null in some ZUL versions). */
    @Wire private Label   counterReferred;

    /** Shows the Batch ID of the batch currently being worked on. */
    @Wire private Label   labelActiveBatchId;

    /** Shows "Showing X – Y of Z cheques". */
    @Wire private Label   labelChequePagingInfo;

    /** Shows "Page X of Y". */
    @Wire private Label   labelChequePageNumber;

    /** Navigates to the previous page of cheques. */
    @Wire private Button  buttonChequePrev;

    /** Navigates to the next page of cheques. */
    @Wire private Button  buttonChequeNext;

    // ── Phase 2: Cheque filter controls ──────────────────────────────────

    /** Free-text search box — filters cheques by number, payee name, or amount. */
    @Wire private Textbox  searchCheque;

    /** Date-range lower bound for the cheque date filter. */
    @Wire private Datebox  dateFromCheque;

    /** Date-range upper bound for the cheque date filter. */
    @Wire private Datebox  dateToCheque;

    /** Drop-down to filter cheques by their current status (Pending / Accepted / Rejected / Referred). */
    @Wire private Combobox comboChequeStatus;

    /** Clears all Phase-2 filter controls and re-renders the full cheque list. */
    @Wire private Button   buttonChequeFilterClear;

    // ══════════════════════════════════════════════════════════════════════
    // WIRED ZK COMPONENTS  — Verification popup
    // ══════════════════════════════════════════════════════════════════════

    /** The popup panel that contains the cheque detail fields and action buttons. */
    @Wire private Div   popupChequeVerify;

    /** Semi-transparent overlay behind the popup that blocks interaction with the list below. */
    @Wire private Div   popupBackdrop;

    /** Small pill label showing the active Batch ID inside the popup header. */
    @Wire private Label popupBatchPill;

    /** Shows "3 / 10" — which cheque is currently open out of how many are visible. */
    @Wire private Label popupRecordPosition;

    /** Displays the scanned cheque image (front or rear). */
    @Wire private Image popupChequeImage;

    /** Placeholder text shown when no image is available for the cheque. */
    @Wire private Label popupImagePlaceholder;

    // ── MICR / sort-code fields shown in the popup ──────────────────────

    /** First 3 digits of the 9-digit sort code — represents the city code. */
    @Wire private Label fieldChequeNo;
    @Wire private Label fieldCityCode;
    @Wire private Label fieldBankCode;
    @Wire private Label fieldBranchCode;
    @Wire private Label fieldTcCode;

    // ── Payee and amount fields ───────────────────────────────────────────

    @Wire private Label fieldPayeeName;

    /** Account holder name returned by CBS for the account on the cheque. */
    @Wire private Label fieldCbsAccountName;

    /** "Match" or "Mismatch" — whether the CBS name matches the cheque payee name. */
    @Wire private Label fieldCbsPayeeMatch;

    @Wire private Label fieldAccountNo;
    @Wire private Label fieldChequeDate;

    /** "Active" or "Inactive" — account status from CBS. */
    @Wire private Label fieldCbsAccountStatus;

    /** "Yes" or "No" — whether the CBS account was opened recently. */
    @Wire private Label fieldCbsNewAccount;

    @Wire private Label fieldAmount;
    @Wire private Label fieldAmountInWords;

    // ── Popup navigation and action controls ─────────────────────────────

    /** Opens the previous cheque in the filtered list without closing the popup. */
    @Wire private Button   buttonPopupPrev;

    /** Opens the next cheque in the filtered list without closing the popup. */
    @Wire private Button   buttonPopupNext;

    /** Accepts the cheque (CBS validation is run first). */
    @Wire private Button   buttonAccept;

    /** Drop-down listing the available rejection reasons. Must be selected before rejecting. */
    @Wire private Combobox comboRejectReason;

    /** Rejects the cheque using the reason chosen in comboRejectReason. */
    @Wire private Button   buttonReject;

    /** Drop-down listing the available refer reasons. Must be selected before referring. */
    @Wire private Combobox comboReferReason;

    /** Refers the cheque to Verification II using the reason chosen in comboReferReason. */
    @Wire private Button   buttonRefer;

    /** Toggles the cheque image between front and rear scans. */
    @Wire private Button   buttonToggleImageSide;

    // ══════════════════════════════════════════════════════════════════════
    // COMPOSER STATE  (in-memory fields kept between ZK event calls)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * NOTE: currentUser field has been intentionally removed.
     *
     * Previously the username was resolved once in doAfterCompose() and cached
     * here as a String field.  That approach risks using a stale username if the
     * session is refreshed after the composer is initialised.
     *
     * The username is now resolved fresh from the ZK session on every action
     * (Accept / Reject / Refer) via getSessionUser().  This matches the pattern
     * used in VerificationIIComposer.getVerifierUsername() and guarantees the
     * correct user is written to the audit trail at the exact moment of the action.
     */

    /** The Batch ID the verifier is currently working on (null when in Phase 1). */
    private String activeBatchId;

    /**
     * Complete list of V1 cheques for the active batch, loaded once when the
     * batch is opened and kept in memory for the duration of the session.
     *
     * IMPORTANT: this list is NEVER filtered in-place.  Filters are applied
     * to produce filteredChequeList; this list remains the authoritative source.
     * Renamed from "pendingChequeList" to avoid confusion — it holds ALL V1
     * cheques (pending + accepted + rejected + referred), not only pending ones.
     */
    private List<ChequeEntity> allChequesForBatch = new ArrayList<>();

    /**
     * The subset of allChequesForBatch that passes the current filter selections.
     * Re-computed by applyChequeFiltersAndRender() on every filter change or action.
     * The cheque table and popup always operate on this list.
     */
    private List<ChequeEntity> filteredChequeList = new ArrayList<>();

    /**
     * Complete batch summary list from the service, loaded once on screen init
     * and on each return to Phase 1.  Filtering is applied in memory to produce
     * filteredBatchList.
     */
    private List<BatchSummary> allBatchSummaries  = new ArrayList<>();

    /**
     * The subset of allBatchSummaries that passes the current filter selections.
     * Re-computed by applyBatchFiltersAndRender() on every filter change.
     */
    private List<BatchSummary> filteredBatchList  = new ArrayList<>();

    /** Current page number in the Phase-1 batch table (1-based). */
    private int batchCurrentPage  = 1;

    /** Total number of pages in the Phase-1 batch table (at least 1 even when empty). */
    private int batchTotalPages   = 1;

    /** Current page number in the Phase-2 cheque table (1-based). */
    private int chequeCurrentPage = 1;

    /** Total number of pages in the Phase-2 cheque table (at least 1 even when empty). */
    private int chequeTotalPages  = 1;

    /**
     * Index into filteredChequeList of the cheque currently displayed in the popup.
     * Updated by openChequePopup() and the Prev / Next popup buttons.
     */
    private int     popupChequeIndex   = 0;

    /**
     * True when the rear (back) side of the cheque image is currently showing.
     * False means the front side is showing.
     */
    private boolean isRearImageVisible = false;

    /**
     * True when the active batch has status VERIFIED — means the verifier opened
     * it in "View" mode and the Accept / Reject / Refer buttons must be hidden.
     */
    private boolean isBatchReadOnly = false;

    // ══════════════════════════════════════════════════════════════════════
    // SESSION USER HELPER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolves the currently logged-in verifier's username fresh from the ZK session
     * on every call.
     *
     * This mirrors VerificationIIComposer.getVerifierUsername() and replaces the old
     * approach of caching the username in a field during doAfterCompose().
     *
     * Why read fresh on every action?
     *   • The ZK session can be refreshed (token re-issue, re-login in another tab)
     *     after the composer was initialised.  A cached field would silently record
     *     the wrong username on the audit trail.
     *   • SecurityUtil.getCurrentUserId() is a lightweight session attribute read —
     *     there is no DB round-trip or meaningful overhead to justify caching it.
     *
     * Falls back to DEFAULT_USER ("SYSTEM") if the session holds no logged-in user,
     * which should only happen during integration tests or misconfigured deployments.
     *
     * @return the username of the currently logged-in user, or "SYSTEM" as fallback
     */
    private String getSessionUser() {
        String username = com.cts.util.SecurityUtil.getCurrentUserId();
        return (username == null || username.isBlank() || "unknown".equals(username))
                ? DEFAULT_USER
                : username;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called by ZK after all @Wire components have been injected.
     * This is the entry point for initial screen setup.
     *
     * Order of operations:
     *   1. Log the currently logged-in user (resolved live from session).
     *   2. Configure table components to not auto-resize (vflex="false").
     *   3. Set the status filter to its default "ALL" option.
     *   4. Reset all in-memory state to clean values.
     *   5. Hide the popup and backdrop.
     *   6. Show Phase 1 and load the batch list.
     *
     * NOTE: The username is NO LONGER stored as a field here.  It is resolved
     * fresh via getSessionUser() at the moment each action button is clicked.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // Log the current user at startup for diagnostics — resolved live from session,
        // not stored as a field (see getSessionUser() for the rationale).
        LOG.info("VerificationOneComposer initialised — logged-in user: " + getSessionUser());

        // Set the status drop-down to "ALL" (index 0) so no filter is active by default
        if (comboBatchStatus.getItemCount() > 0) {
            comboBatchStatus.setSelectedIndex(0);
        }

        resetAllState();
        popupChequeVerify.setVisible(false);
        popupBackdrop.setVisible(false);

        showPhase(1);
        loadBatchList();
    }

    /**
     * Resets every piece of in-memory state to its initial empty value.
     * Called on composer startup and whenever the verifier returns to Phase 1
     * after working on a batch.
     */
    private void resetAllState() {
        activeBatchId      = null;
        allChequesForBatch = new ArrayList<>();
        allBatchSummaries  = new ArrayList<>();
        filteredBatchList  = new ArrayList<>();
        filteredChequeList = new ArrayList<>();
        popupChequeIndex   = 0;
        batchCurrentPage   = 1;
        chequeCurrentPage  = 1;
    }

    /**
     * Switches the visible panel between Phase 1 (batch list) and Phase 2 (cheque list).
     *
     * @param phase 1 to show the batch list, 2 to show the cheque list
     */
    private void showPhase(int phase) {
        panelBatchList.setVisible(phase == 1);
        panelChequeList.setVisible(phase == 2);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 1 — BATCH LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fetches the full batch summary list from the service, applies the current
     * filter state, and renders the first page of results.
     * Called on initial load and on every return to Phase 1.
     */
    private void loadBatchList() {
        allBatchSummaries = verificationService.getVerifiableBatchSummaries();
        applyBatchFiltersAndRender();
    }

    /**
     * Reads the current values of all four Phase-1 filter controls, delegates
     * filtering to the service, recalculates pagination, and re-renders the
     * first page of the filtered result.
     *
     * Called on every filter control change event so the table updates instantly
     * without the verifier pressing a separate "Search" button.
     */
    private void applyBatchFiltersAndRender() {
        String searchText  = searchBatch   != null ? searchBatch.getValue().trim() : "";
        String statusValue = getComboSelectedValue(comboBatchStatus);
        Date   fromDate    = dateFromBatch != null ? dateFromBatch.getValue() : null;
        Date   toDate      = dateToBatch   != null ? dateToBatch.getValue()   : null;

        // Filtering is a pure business concern — delegate to the service
        filteredBatchList = verificationService.filterBatchSummaries(
                allBatchSummaries, searchText, statusValue, fromDate, toDate);

        batchTotalPages  = Math.max(1, (int) Math.ceil((double) filteredBatchList.size() / BATCH_PAGE_SIZE));
        batchCurrentPage = 1; // always jump back to page 1 after a filter change
        renderBatchPage();
    }

    /**
     * Clears the Phase-1 table and fills it with the rows for the current page.
     *
     * Each row shows: Batch ID | Total | Pending | Processed | Date | Status | Action button.
     * The action button label changes based on batch status:
     *   "Process" — batch has not been opened yet (READY_FOR_VERIFICATION)
     *   "Resume"  — batch is partially worked on (VERIFICATION_IN_PROGRESS)
     *   "View"    — batch is fully verified; opens in read-only mode (VERIFIED)
     */
    private void renderBatchPage() {
        listBatches.getItems().clear();

        int totalBatches = filteredBatchList.size();
        int pageStart    = (batchCurrentPage - 1) * BATCH_PAGE_SIZE;
        int pageEnd      = Math.min(pageStart + BATCH_PAGE_SIZE, totalBatches);

        for (BatchSummary summary : filteredBatchList.subList(pageStart, pageEnd)) {
            Listitem row = new Listitem();
            row.appendChild(cell(summary.getBatchId()));
            row.appendChild(cellCenter(String.valueOf(summary.getTotalCheques())));
            row.appendChild(cellCenter(String.valueOf(summary.getPendingCount())));
            row.appendChild(cellCenter(String.valueOf(summary.getProcessedCount())));
            row.appendChild(cell(verificationService.formatDisplayDate(summary.getCreatedAt())));

            // Colour-coded status badge — CSS class is resolved by a UI helper in this composer
            Listcell statusCell  = new Listcell();
            Label    statusLabel = new Label(summary.getStatus().getLabel());
            statusLabel.setSclass("batch-pill " + resolveBatchStatusPillClass(summary.getStatus()));
            statusCell.appendChild(statusLabel);
            row.appendChild(statusCell);

            // Button label communicates what will happen when clicked
            // Label mapping is a UI display decision — kept here in the composer, not the service
            String actionButtonLabel = resolveBatchActionButtonLabel(summary.getStatus());

            Listcell actionCell    = new Listcell();
            Button   actionButton  = new Button(actionButtonLabel);
            actionButton.setSclass("v1-action-process-btn");
            final String batchId = summary.getBatchId();
            actionButton.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK,
                    event -> openBatchChequeList(batchId));
            actionCell.appendChild(actionButton);
            row.appendChild(actionCell);

            row.setParent(listBatches);
        }

        // Update paging labels and enable / disable navigation buttons
        if (totalBatches == 0) {
            labelBatchPagingInfo.setValue("Showing 0 \u2013 0 of 0 batches");
        } else {
            labelBatchPagingInfo.setValue(
                    "Showing " + (pageStart + 1) + " \u2013 " + pageEnd
                    + " of " + totalBatches + " batches");
        }
        labelBatchPageNumber.setValue("Page " + batchCurrentPage + " of " + batchTotalPages);
        buttonBatchPrev.setDisabled(batchCurrentPage <= 1);
        buttonBatchNext.setDisabled(batchCurrentPage >= batchTotalPages);
    }

    // ── Phase 1: Filter event listeners ─────────────────────────────────
    // Each listener simply calls applyBatchFiltersAndRender() so the table
    // updates live as the verifier types or changes a filter control.

    /** Fires on every keystroke in the batch search box (live filtering). */
    @Listen("onChanging = #searchBatch")
    public void onBatchSearchChanging(org.zkoss.zk.ui.event.InputEvent event) {
        applyBatchFiltersAndRender();
    }

    /** Fires when the batch search box loses focus (handles paste / autofill). */
    @Listen("onChange = #searchBatch")
    public void onBatchSearchChange() { applyBatchFiltersAndRender(); }

    /** Fires when the user types into the status combo box. */
    @Listen("onChange = #comboBatchStatus")
    public void onBatchStatusFilterChange() { applyBatchFiltersAndRender(); }

    /** Fires when the user picks an item from the status combo drop-down. */
    @Listen("onSelect = #comboBatchStatus")
    public void onBatchStatusFilterSelect() { applyBatchFiltersAndRender(); }

    @Listen("onChange = #dateFromBatch")
    public void onBatchFromDateChange() { applyBatchFiltersAndRender(); }

    @Listen("onChange = #dateToBatch")
    public void onBatchToDateChange() { applyBatchFiltersAndRender(); }

    /** Resets every Phase-1 filter control to its default and reloads the full batch list. */
    @Listen("onClick = #buttonBatchFilterClear")
    public void onBatchFilterClear() {
        searchBatch.setValue("");
        dateFromBatch.setValue(null);
        dateToBatch.setValue(null);
        if (comboBatchStatus.getItemCount() > 0) comboBatchStatus.setSelectedIndex(0);
        batchCurrentPage = 1;
        applyBatchFiltersAndRender();
    }

    @Listen("onClick = #buttonBatchPrev")
    public void onBatchPrev() {
        if (batchCurrentPage > 1) { batchCurrentPage--; renderBatchPage(); }
    }

    @Listen("onClick = #buttonBatchNext")
    public void onBatchNext() {
        if (batchCurrentPage < batchTotalPages) { batchCurrentPage++; renderBatchPage(); }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — CHEQUE LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Transitions from Phase 1 to Phase 2 for the selected batch.
     *
     * Steps:
     *   1. Ask the service whether this batch is read-only (VERIFIED).
     *   2. If not read-only, trigger the READY → IN_PROGRESS status transition.
     *   3. Load all V1 cheques for the batch into in-memory state.
     *   4. If no cheques exist, show a message and stay in Phase 1.
     *   5. Clear the cheque filter controls (left over from a previous batch).
     *   6. Render the cheque table and switch to Phase 2.
     *
     * @param batchId the Batch ID chosen by the verifier in the batch table
     */
    private void openBatchChequeList(String batchId) {
        // Ask the service — composer does not compare BatchStatus values directly
        isBatchReadOnly = verificationService.isBatchReadOnly(batchId);

        if (!isBatchReadOnly) {
            // Marks the batch as in-progress on first open; no-op on subsequent opens
            verificationService.openBatchForVerification(batchId);
        }

        List<ChequeEntity> loadedCheques = verificationService.getAllV1ChequesForBatch(batchId);

        if (loadedCheques.isEmpty()) {
            String message = (isBatchReadOnly ? "No V1 cheques found for batch " : "No V1 cheques in batch ")
                    + batchId + ".";
            String title   = isBatchReadOnly ? "No Data" : "Nothing to Verify";
            Messagebox.show(message, title, Messagebox.OK, Messagebox.INFORMATION);
            return; // stay in Phase 1
        }

        activeBatchId      = batchId;
        allChequesForBatch = loadedCheques;
        chequeCurrentPage  = 1;

        // Clear cheque filter controls so they do not carry over from a previous batch
        clearChequeFilters();

        labelActiveBatchId.setValue(batchId);
        refreshStatusCounters();
        applyChequeFiltersAndRender();
        showPhase(2);
    }

    /**
     * Reads the current values of all four Phase-2 filter controls, delegates
     * filtering to the service, recalculates pagination, and re-renders the
     * first page of the filtered cheque list.
     *
     * Called on every filter change and after every accept / reject / refer action.
     */
    private void applyChequeFiltersAndRender() {
        String searchText  = searchCheque   != null ? searchCheque.getValue().trim() : "";
        String statusValue = getComboSelectedValue(comboChequeStatus);
        Date   fromDate    = dateFromCheque != null ? dateFromCheque.getValue() : null;
        Date   toDate      = dateToCheque   != null ? dateToCheque.getValue()   : null;

        // Filtering is a pure business concern — delegate to the service
        filteredChequeList = verificationService.filterCheques(
                allChequesForBatch, searchText, statusValue, fromDate, toDate);

        chequeTotalPages  = Math.max(1, (int) Math.ceil((double) filteredChequeList.size() / CHEQUE_PAGE_SIZE));
        chequeCurrentPage = 1;
        renderChequePage();
    }

    /**
     * Clears the Phase-2 table and fills it with cheque rows for the current page.
     *
     * Each row shows: # | Cheque No | Payee Name | Amount | Cheque Date | Status | Open button.
     * The "Open" button is disabled for cheques that are not V1_PENDING, or when
     * the batch is read-only (VERIFIED).
     */
    private void renderChequePage() {
        listCheques.getItems().clear();

        int totalCheques = filteredChequeList.size();
        int pageStart    = (chequeCurrentPage - 1) * CHEQUE_PAGE_SIZE;
        int pageEnd      = Math.min(pageStart + CHEQUE_PAGE_SIZE, totalCheques);

        for (int i = 0; i < pageEnd - pageStart; i++) {
            ChequeEntity cheque      = filteredChequeList.get(pageStart + i);
            final int    absoluteIdx = pageStart + i; // index into filteredChequeList for popup

            Listitem row = new Listitem();
            row.appendChild(cellCenter(String.valueOf(absoluteIdx + 1)));
            row.appendChild(cellMono(blankToEmDash(cheque.getChequeNo())));
            row.appendChild(cell(blankToEmDash(cheque.getPayeeName())));
            row.appendChild(cellAmt(formatAmount(cheque.getAmount())));
            row.appendChild(cell(blankToEmDash(cheque.getChequeDate())));

            // Status badge — colour resolved by a UI helper in this composer
            ChequeStatus chequeStatus = ChequeStatus.fromDb(cheque.getStatus());
            Listcell statusCell  = new Listcell();
            Label    statusLabel = new Label(chequeStatus.getLabel());
            statusLabel.setSclass("v1-row-status-badge " + resolveChequeBadgeClass(chequeStatus));
            statusCell.appendChild(statusLabel);
            row.appendChild(statusCell);

            // "Open" is only enabled for pending cheques in a non-read-only batch
            Listcell actionCell = new Listcell();
            Button   openButton = new Button("Open");
            openButton.setSclass("v1-action-open-btn");
            openButton.setDisabled(isBatchReadOnly || chequeStatus != ChequeStatus.V1_PENDING);
            openButton.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK,
                    event -> openChequePopup(absoluteIdx));
            actionCell.appendChild(openButton);
            row.appendChild(actionCell);

            row.setParent(listCheques);
        }

        // Update paging labels and navigation buttons
        if (totalCheques == 0) {
            labelChequePagingInfo.setValue("Showing 0 \u2013 0 of 0 cheques");
        } else {
            labelChequePagingInfo.setValue(
                    "Showing " + (pageStart + 1) + " \u2013 " + pageEnd
                    + " of " + totalCheques + " cheques");
        }
        labelChequePageNumber.setValue("Page " + chequeCurrentPage + " of " + chequeTotalPages);
        buttonChequePrev.setDisabled(chequeCurrentPage <= 1);
        buttonChequeNext.setDisabled(chequeCurrentPage >= chequeTotalPages);
    }

    // ── Phase 2: Filter event listeners ──────────────────────────────────

    @Listen("onChanging = #searchCheque")
    public void onChequeSearchChanging(org.zkoss.zk.ui.event.InputEvent event) {
        applyChequeFiltersAndRender();
    }

    @Listen("onChange = #searchCheque")
    public void onChequeSearchChange() { applyChequeFiltersAndRender(); }

    @Listen("onChange = #comboChequeStatus")
    public void onChequeStatusFilterChange() { applyChequeFiltersAndRender(); }

    @Listen("onSelect = #comboChequeStatus")
    public void onChequeStatusFilterSelect() { applyChequeFiltersAndRender(); }

    @Listen("onChange = #dateFromCheque")
    public void onChequeFromDateChange() { applyChequeFiltersAndRender(); }

    @Listen("onChange = #dateToCheque")
    public void onChequeToDateChange() { applyChequeFiltersAndRender(); }

    /** Resets every Phase-2 filter control and re-renders the full cheque list. */
    @Listen("onClick = #buttonChequeFilterClear")
    public void onChequeFilterClear() {
        clearChequeFilters();
        chequeCurrentPage = 1;
        applyChequeFiltersAndRender();
    }

    @Listen("onClick = #buttonChequePrev")
    public void onChequePrev() {
        if (chequeCurrentPage > 1) { chequeCurrentPage--; renderChequePage(); }
    }

    @Listen("onClick = #buttonChequeNext")
    public void onChequeNext() {
        if (chequeCurrentPage < chequeTotalPages) { chequeCurrentPage++; renderChequePage(); }
    }

    /**
     * Resets all Phase-2 filter input controls to their empty / default state.
     * Extracted as a helper because it is called both from the "Clear" button
     * listener and from openBatchChequeList() when switching batches.
     */
    private void clearChequeFilters() {
        if (searchCheque      != null) searchCheque.setValue("");
        if (dateFromCheque    != null) dateFromCheque.setValue(null);
        if (dateToCheque      != null) dateToCheque.setValue(null);
        if (comboChequeStatus != null) {
            comboChequeStatus.setValue("");
            comboChequeStatus.setSelectedItem(null);
        }
    }

    /**
     * Updates the four status-counter labels (Pending / Accepted / Rejected / Referred)
     * above the cheque table.  Counts are always taken from the FULL in-memory list
     * (allChequesForBatch), not the filtered subset, so the counters always reflect
     * the true batch totals regardless of which filter is active.
     */
    private void refreshStatusCounters() {
        counterPending.setValue(
                verificationService.countByStatus(allChequesForBatch, ChequeStatus.V1_PENDING)  + " Pending");
        counterAccepted.setValue(
                verificationService.countByStatus(allChequesForBatch, ChequeStatus.VERIFIED)   + " Accepted");
        counterRejected.setValue(
                verificationService.countByStatus(allChequesForBatch, ChequeStatus.REJECTED)   + " Rejected");
        if (counterReferred != null) {
            counterReferred.setValue(
                    verificationService.countByStatus(allChequesForBatch, ChequeStatus.REFERRED) + " Referred");
        }
    }

    /**
     * Returns to Phase 1 (the batch list) from Phase 2 (the cheque list).
     * Closes any open popup, resets cheque-related state, and reloads the
     * batch list so updated pending counts are shown.
     */
    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        closeVerificationPopup();
        activeBatchId      = null;
        allChequesForBatch = new ArrayList<>();
        filteredChequeList = new ArrayList<>();
        chequeCurrentPage  = 1;
        batchCurrentPage   = 1;
        isBatchReadOnly    = false;
        showPhase(1);
        loadBatchList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // VERIFICATION POPUP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Opens the verification popup for the cheque at the given index in
     * filteredChequeList.
     *
     * @param indexInFilteredList zero-based position in the current filtered list
     */
    private void openChequePopup(int indexInFilteredList) {
        if (filteredChequeList == null
                || indexInFilteredList < 0
                || indexInFilteredList >= filteredChequeList.size())
            return;

        popupChequeIndex   = indexInFilteredList;
        isRearImageVisible = false; // always start with the front image
        renderVerificationPopup();
        popupBackdrop.setVisible(true);
        popupChequeVerify.setVisible(true);
    }

    /**
     * Hides the verification popup and its backdrop.
     * Called by the Close button, by action handlers after an action, and
     * when navigating back to Phase 1.
     */
    private void closeVerificationPopup() {
        popupChequeVerify.setVisible(false);
        popupBackdrop.setVisible(false);
    }

    @Listen("onClick = #btnClosePopup")
    public void onClosePopup() { closeVerificationPopup(); }

    /**
     * Populates every field in the verification popup with data from the
     * cheque currently indicated by popupChequeIndex.
     *
     * Steps:
     *   1. Fill in the MICR / sort-code fields.
     *   2. Fill in payee name, account, date, amount fields.
     *   3. Fetch CBS account details from the service and populate CBS fields.
     *   4. Show or hide the action buttons depending on isBatchReadOnly.
     *   5. Load the front cheque image.
     */
    private void renderVerificationPopup() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null) return;

        popupBatchPill.setValue("Batch: " + activeBatchId);
        popupRecordPosition.setValue((popupChequeIndex + 1) + " / " + filteredChequeList.size());

        fieldChequeNo.setValue(blankToEmDash(cheque.getChequeNo()));

        // Sort code is stored as a 9-digit string: digits 0-2 = city, 3-5 = bank, 6-8 = branch
        String sortCode = cheque.getSortCode() != null
                ? cheque.getSortCode().replaceAll("[^0-9]", "")
                : "";
        fieldCityCode.setValue(extractSortCodeSegment(sortCode, 0, 3));
        fieldBankCode.setValue(extractSortCodeSegment(sortCode, 3, 6));
        fieldBranchCode.setValue(extractSortCodeSegment(sortCode, 6, 9));
        fieldTcCode.setValue(blankToEmDash(cheque.getTransactionCode()));

        fieldPayeeName.setValue(blankToEmDash(cheque.getPayeeName()));
        fieldAccountNo.setValue(blankToEmDash(cheque.getPayeeAccountNo()));
        fieldChequeDate.setValue(blankToEmDash(cheque.getChequeDate()));
        fieldAmount.setValue(formatAmount(cheque.getAmount()));
        fieldAmountInWords.setValue(blankToEmDash(cheque.getAmountInWords()));

        // CBS lookup — pure business result from the service; composer maps it to sclass strings
        CbsAccountDetails cbsDetails = verificationService.getCbsAccountDetails(
                cheque.getPayeeAccountNo(), cheque.getPayeeName());
        populateCbsFields(cbsDetails);

        // Reset reason drop-downs so stale selections from the previous cheque are cleared
        comboRejectReason.setValue("");
        comboRejectReason.setSelectedItem(null);
        comboReferReason.setValue("");
        comboReferReason.setSelectedItem(null);

        // Action buttons are hidden for read-only (fully verified) batches
        boolean showActionButtons = !isBatchReadOnly;
        buttonAccept.setVisible(showActionButtons);
        buttonReject.setVisible(showActionButtons);
        buttonRefer.setVisible(showActionButtons);
        comboRejectReason.setVisible(showActionButtons);
        comboReferReason.setVisible(showActionButtons);

        isRearImageVisible = false;
        loadChequeImage(cheque, "front");
        buttonToggleImageSide.setLabel("\u21c4 Show BACK");
    }

    /**
     * Translates a CbsAccountDetails result object (returned by the service) into
     * ZK Label values and sclass strings for the CBS section of the popup.
     *
     * CSS class mapping rules (presentation only — must not live in the service):
     *   Account status:   active → "ch-active"    | inactive → "ch-inactive" | unknown → "ch-cbs-unknown"
     *   New account flag: Yes   → "ch-new-acc"    | No       → "ch-not-new"  | unknown → "ch-cbs-unknown"
     *   Payee name match: Match → "cbs-match-ok"  | Mismatch → "cbs-match-fail" | unknown → ""
     *
     * @param cbsDetails the result object produced by VerificationOneService.getCbsAccountDetails()
     */
    private void populateCbsFields(CbsAccountDetails cbsDetails) {
        fieldCbsAccountName.setValue(cbsDetails.getAccountHolderName());

        // Account active status
        fieldCbsAccountStatus.setValue(cbsDetails.getAccountStatus());
        if (cbsDetails.getLookupState() == LookupState.FOUND) {
            fieldCbsAccountStatus.setSclass(cbsDetails.isAccountActive() ? "ch-active" : "ch-inactive");
        } else {
            fieldCbsAccountStatus.setSclass("ch-cbs-unknown");
        }

        // New account flag
        fieldCbsNewAccount.setValue(cbsDetails.getIsNewAccount());
        if (cbsDetails.getLookupState() == LookupState.FOUND) {
            fieldCbsNewAccount.setSclass(cbsDetails.isNewAccountFlag() ? "ch-new-acc" : "ch-not-new");
        } else {
            fieldCbsNewAccount.setSclass("ch-cbs-unknown");
        }

        // Payee name match
        fieldCbsPayeeMatch.setValue(cbsDetails.getPayeeMatchLabel());
        if (cbsDetails.getLookupState() == LookupState.FOUND) {
            fieldCbsPayeeMatch.setSclass(cbsDetails.isPayeeNamesMatch() ? "cbs-match-ok" : "cbs-match-fail");
        } else {
            fieldCbsPayeeMatch.setSclass("");
        }
    }

    /**
     * Sets the src attribute of the cheque image component to load the specified
     * side of the cheque from the image servlet.
     *
     * A cache-busting timestamp query parameter is appended so the browser always
     * fetches the latest image rather than serving a stale cached copy.
     *
     * @param cheque the entity whose image should be loaded
     * @param side   "front" or "rear"
     */
    private void loadChequeImage(ChequeEntity cheque, String side) {
        if (cheque.getId() == null) {
            // No image available for this cheque — show the placeholder text instead
            popupImagePlaceholder.setValue("No image available");
            popupImagePlaceholder.setVisible(true);
            popupChequeImage.setVisible(false);
            return;
        }
        // Cache-busting: append current time so each request is treated as unique by the browser
        popupChequeImage.setSrc(CHEQUE_IMAGE_SERVLET
                + "?id="   + cheque.getId()
                + "&side=" + side
                + "&t="    + System.currentTimeMillis());
        popupChequeImage.setVisible(true);
        popupImagePlaceholder.setVisible(false);
    }

    /** Toggles the cheque image between front and rear scans. */
    @Listen("onClick = #buttonToggleImageSide")
    public void onToggleImageSide() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null) return;
        isRearImageVisible = !isRearImageVisible;
        loadChequeImage(cheque, isRearImageVisible ? "rear" : "front");
        buttonToggleImageSide.setLabel(isRearImageVisible ? "\u21c4 Show FRONT" : "\u21c4 Show BACK");
    }

    /** Moves to the previous cheque in the filtered list without closing the popup. */
    @Listen("onClick = #buttonPopupPrev")
    public void onPopupPrev() {
        if (popupChequeIndex > 0) {
            popupChequeIndex--;
            isRearImageVisible = false;
            renderVerificationPopup();
        }
    }

    /** Moves to the next cheque in the filtered list without closing the popup. */
    @Listen("onClick = #buttonPopupNext")
    public void onPopupNext() {
        if (popupChequeIndex < filteredChequeList.size() - 1) {
            popupChequeIndex++;
            isRearImageVisible = false;
            renderVerificationPopup();
        }
    }

    // ── Accept / Reject / Refer action handlers ───────────────────────────

    /**
     * Handles the Accept button click.
     * Resolves the logged-in username fresh from the ZK session at click-time
     * via getSessionUser() — not from a cached field — to ensure the correct
     * user is written to the audit trail.
     * Delegates CBS validation and persistence to the service.
     * On success, applies the status change in memory and advances to the next cheque.
     * On failure, shows the validation error message in a dialog.
     */
    @Listen("onClick = #buttonAccept")
    public void onAccept() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null) return;

        // Resolve the username fresh from the ZK session at the moment of the action.
        // This matches the pattern in VerificationIIComposer.getVerifierUsername().
        String actionUser = getSessionUser();
        LOG.fine("Accept action — session user: " + actionUser
                + " | chequeId: " + cheque.getId()
                + " | batchId: "  + activeBatchId);

        // The service validates CBS rules and persists the accept decision
        String validationError = verificationService.validateAndAcceptCheque(
                cheque.getId(), cheque.getPayeeAccountNo(), actionUser);

        if (validationError != null) {
            // Validation failed — show the reason to the verifier and keep the popup open
            Messagebox.show(validationError, "CBS Validation Failed",
                    Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }
        applyActionAndAdvance(ChequeStatus.VERIFIED.db());
    }

    /**
     * Handles the Reject button click.
     * Resolves the logged-in username fresh from the ZK session at click-time
     * via getSessionUser().
     * A rejection reason must be selected from the drop-down before rejecting.
     */
    @Listen("onClick = #buttonReject")
    public void onReject() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null) return;

        String rejectionReason = getComboSelectedValue(comboRejectReason);
        if (rejectionReason == null) {
            Messagebox.show("Please select a rejection reason before rejecting.",
                    "Reason Required", Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }

        // Resolve the username fresh from the ZK session at the moment of the action.
        String actionUser = getSessionUser();
        LOG.fine("Reject action — session user: " + actionUser
                + " | chequeId: " + cheque.getId()
                + " | reason: "   + rejectionReason);

        verificationService.rejectCheque(cheque.getId(), actionUser, rejectionReason);
        applyActionAndAdvance(ChequeStatus.REJECTED.db());
    }

    /**
     * Handles the Refer button click.
     * Resolves the logged-in username fresh from the ZK session at click-time
     * via getSessionUser().
     * A refer reason must be selected from the drop-down before referring.
     */
    @Listen("onClick = #buttonRefer")
    public void onRefer() {
        ChequeEntity cheque = getPopupCheque();
        if (cheque == null) return;

        String referReason = getComboSelectedValue(comboReferReason);
        if (referReason == null) {
            Messagebox.show("Please select a refer reason before referring.",
                    "Reason Required", Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }

        // Resolve the username fresh from the ZK session at the moment of the action.
        String actionUser = getSessionUser();
        LOG.fine("Refer action — session user: " + actionUser
                + " | chequeId: " + cheque.getId()
                + " | reason: "   + referReason);

        verificationService.referCheque(cheque.getId(), actionUser, referReason);
        // Use REFERRED (not V2_PENDING) for the in-memory status so the badge shows "Referred"
        // for the rest of this session, even though the DB now stores V2_PENDING
        applyActionAndAdvance(ChequeStatus.REFERRED.db());
    }

    /**
     * Common handler called after every successful accept / reject / refer action:
     *   1. Updates the entity in the in-memory list (no extra DB round-trip needed).
     *   2. Asks the service to check whether the batch is now fully complete.
     *   3. Refreshes the status counters and cheque table.
     *   4. Closes the popup.
     *   5. Automatically opens the next pending cheque, or shows a completion message.
     *
     * @param newStatus the DB status string to apply (e.g. "VERIFIED", "REJECTED", "REFERRED")
     */
    private void applyActionAndAdvance(String newStatus) {
        ChequeEntity actioned = getPopupCheque();
        if (actioned != null) {
            // Business rules for in-memory update live in the service
            verificationService.applyActionToInMemoryCheque(actioned, newStatus);
        }

        // Ask service whether all cheques are done; it will update batch status if so
        verificationService.checkAndFinalizeBatch(activeBatchId);

        refreshStatusCounters();
        applyChequeFiltersAndRender();
        closeVerificationPopup();
        openNextPendingOrShowCompletion();
    }

    /**
     * After an action, finds the next V1_PENDING cheque and opens it automatically.
     * If the next pending cheque is hidden by the current filter, the filter is reset
     * first so the cheque becomes visible.
     * If no pending cheques remain, shows a "Batch Complete" message.
     */
    private void openNextPendingOrShowCompletion() {
        // The service knows the business rule for "what is the next pending cheque"
        int nextPendingIndexInMasterList =
                verificationService.findNextPendingChequeIndex(allChequesForBatch);

        if (nextPendingIndexInMasterList == -1) {
            // -1 means every cheque has been actioned
            Messagebox.show(
                    "All cheques in batch " + activeBatchId + " have been actioned.",
                    "Batch Complete", Messagebox.OK, Messagebox.INFORMATION);
            return;
        }

        // Check whether the next pending cheque is visible in the current filtered list
        ChequeEntity nextPendingCheque = allChequesForBatch.get(nextPendingIndexInMasterList);
        for (int j = 0; j < filteredChequeList.size(); j++) {
            if (filteredChequeList.get(j) == nextPendingCheque) {
                openChequePopup(j); // cheque is in the filtered list — open it directly
                return;
            }
        }

        // Next pending cheque is hidden by the current filter — reset the filter first
        resetChequeFiltersToShowAll(nextPendingIndexInMasterList);
    }

    /**
     * Resets filteredChequeList to the full master list and opens the popup at the
     * given index.  Called when the next pending cheque is not visible under the
     * current filter — the filter is silently cleared so the verifier is not stuck.
     *
     * @param indexInMasterList position of the next pending cheque in allChequesForBatch
     */
    private void resetChequeFiltersToShowAll(int indexInMasterList) {
        filteredChequeList = new ArrayList<>(allChequesForBatch);
        chequeTotalPages   = Math.max(1,
                (int) Math.ceil((double) filteredChequeList.size() / CHEQUE_PAGE_SIZE));
        chequeCurrentPage  = 1;
        renderChequePage();
        openChequePopup(indexInMasterList);
    }

    /**
     * Returns the ChequeEntity currently shown in the popup.
     * Returns null if the popup index is out of range (defensive guard).
     */
    private ChequeEntity getPopupCheque() {
        if (filteredChequeList == null
                || popupChequeIndex < 0
                || popupChequeIndex >= filteredChequeList.size())
            return null;
        return filteredChequeList.get(popupChequeIndex);
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI HELPERS  (presentation only — no business rules)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the display label for the action button in the Phase-1 batch table.
     * The label tells the verifier what will happen when they click the button.
     *
     *   VERIFIED                 → "View"    (batch is fully done; opens in read-only mode)
     *   VERIFICATION_IN_PROGRESS → "Resume"  (work started but not finished)
     *   anything else            → "Process" (batch has not been opened yet)
     *
     * This is a UI display decision (which English word to show), not a business
     * rule — it belongs in the composer, not the service.
     *
     * @param batchStatus current status of the batch row
     * @return display label string for the action button
     */
    private String resolveBatchActionButtonLabel(BatchStatus batchStatus) {
        switch (batchStatus) {
            case VERIFIED:                 return "View";
            case VERIFICATION_IN_PROGRESS: return "Resume";
            default:                       return "Process";
        }
    }

    /**
     * Returns the CSS class name for the colour-coded status pill shown in the
     * Phase-1 batch table.  Colour rules are presentation decisions and belong
     * in the composer (not the service).
     *
     *   VERIFICATION_IN_PROGRESS → blue  (work in progress)
     *   READY_FOR_VERIFICATION   → amber (waiting to be started)
     *   VERIFIED / post-verified  → green (complete)
     *   anything else             → gray
     *
     * @param batchStatus the current status of the batch row
     * @return a CSS class name string to append to the label's sclass
     */
    private String resolveBatchStatusPillClass(BatchStatus batchStatus) {
        switch (batchStatus) {
            case VERIFICATION_IN_PROGRESS: return "batch-pill-blue";
            case READY_FOR_VERIFICATION:   return "batch-pill-amber";
            case VERIFIED:
            case CXF_CIBF_GENERATED:
            case DISPATCHED:               return "batch-pill-green";
            default:                       return "batch-pill-gray";
        }
    }

    /**
     * Returns the CSS class name for the colour-coded status badge shown in the
     * Phase-2 cheque table and popup.
     *
     *   VERIFIED   → green
     *   REJECTED   → red
     *   REFERRED   → blue
     *   V1_PENDING → amber
     *   anything else → gray
     *
     * @param chequeStatus the current status of the cheque row
     * @return a CSS class name string to append to the label's sclass
     */
    private String resolveChequeBadgeClass(ChequeStatus chequeStatus) {
        switch (chequeStatus) {
            case VERIFIED:   return "badge-green";
            case REJECTED:   return "badge-red";
            case REFERRED:   return "badge-blue";
            case V1_PENDING: return "badge-amber";
            default:         return "badge-gray";
        }
    }

    /**
     * Extracts a slice of the 9-digit MICR sort code for display in the
     * City Code / Bank Code / Branch Code fields of the popup.
     *
     * Returns an em-dash if the sort code is shorter than the requested start
     * position, so the popup never shows an empty or blank field.
     *
     * @param sortCode  the cleaned sort code string (digits only, 9 chars expected)
     * @param fromIndex start of the slice (inclusive)
     * @param toIndex   end of the slice (exclusive)
     * @return the sort code slice, or "—" if out of range
     */
    private String extractSortCodeSegment(String sortCode, int fromIndex, int toIndex) {
        if (sortCode == null || sortCode.isEmpty() || sortCode.length() <= fromIndex)
            return "\u2014"; // em-dash
        return sortCode.substring(fromIndex, Math.min(toIndex, sortCode.length()));
    }

    /**
     * Returns the value of the item currently selected in a Combobox.
     *
     * ZK Combobox has two ways to carry a value:
     *   (a) selectedItem — set when the user picks from the drop-down list
     *   (b) raw text in the input field — set when the user types manually
     *
     * This helper checks (a) first and falls back to (b), returning null if
     * neither carries a non-blank value.
     *
     * @param combobox the ZK Combobox component to read
     * @return the selected value string, or null if nothing is selected / typed
     */
    private String getComboSelectedValue(Combobox combobox) {
        if (combobox == null) return null;
        if (combobox.getSelectedItem() != null) {
            Object value = combobox.getSelectedItem().getValue();
            return value != null ? value.toString() : null;
        }
        String rawText = combobox.getValue();
        return (rawText != null && !rawText.isBlank()) ? rawText.trim() : null;
    }

    // ── Listcell factory helpers ──────────────────────────────────────────
    // These small helpers keep the renderBatchPage / renderChequePage methods
    // clean by removing repetitive Listcell construction code.

    /** Creates a standard left-aligned Listcell; shows em-dash for null input. */
    private Listcell cell(String text) {
        return new Listcell(text == null ? "\u2014" : text);
    }

    /** Creates a centre-aligned Listcell — used for numeric count columns. */
    private Listcell cellCenter(String text) {
        Listcell listCell = cell(text);
        listCell.setStyle("text-align:center");
        return listCell;
    }

    /** Creates a monospace-font Listcell — used for the cheque number column. */
    private Listcell cellMono(String text) {
        Listcell listCell = cell(text);
        listCell.setSclass("mono");
        return listCell;
    }

    /** Creates a right-aligned Listcell styled for currency amounts. */
    private Listcell cellAmt(String text) {
        Listcell listCell = cell(text);
        listCell.setSclass("amt");
        return listCell;
    }

    // ── Value formatting helpers ──────────────────────────────────────────

    /**
     * Formats a BigDecimal amount as Indian currency (e.g. "Rs. 1,23,456.00").
     * Returns "Rs. 0.00" for null amounts so the table cell is never empty.
     *
     * @param amount the cheque amount; may be null
     * @return formatted currency string
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "Rs. 0.00";
        return "Rs. " + NumberFormat.getNumberInstance(new Locale("en", "IN")).format(amount);
    }

    /**
     * Returns the input string unchanged, or an em-dash ("—") if the string
     * is null or blank.  Used to ensure no popup or table field ever shows
     * empty or whitespace content.
     *
     * @param value the raw string value from the entity
     * @return the value, or "—" if absent
     */
    private String blankToEmDash(String value) {
        return (value == null || value.isBlank()) ? "\u2014" : value;
    }
}