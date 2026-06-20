package com.cts.outward.composer;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.service.VerificationIIService;
import com.cts.outward.service.VerificationIIServiceImpl;
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
 * VerificationIIComposer  —  Multi-Select Toggle Filter
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
 *  Examples:
 *   HV clicked         → only HV cheques
 *   HV + Pending       → only HV pending cheques
 *   Pending + Rejected → all cheques that are pending OR rejected (any type)
 *   HV clicked again   → HV turns off → show all cheques again
 *
 *  Search filter  : matches cheque no. or payee name (case-insensitive substring)
 *  Date filter    : filters on cheque date (dd/MM/yyyy String field)
 *  Clear button   : resets all chips + search + date in one click
 *
 *  FIX — Popup navigation respects active filters
 *  ─────────────────────────────────────────────────
 *  popupFilteredList captures the filtered cheque list at the moment a cheque
 *  is opened. Prev / Next and findNextPending all navigate within that list,
 *  so if "High Value" filter is active the popup only cycles HV cheques.
 *  After Accept / Reject the filtered list is refreshed so status changes are
 *  reflected immediately in navigation.
 */
public class VerificationIIComposer extends SelectorComposer<Component> {

    private final VerificationIIService service = new VerificationIIServiceImpl();

    private static final int BATCH_PAGE_SIZE  = 5;
    private static final int CHEQUE_PAGE_SIZE = 5;

    // ── Wired — page-level components ────────────────────────────────────────

    @Wire protected Label  totalBatchesChip;
    @Wire protected Label  totalHvChip;
    @Wire protected Label  totalRefChip;

    // Screen 1
    @Wire protected Div    batchListView;
    @Wire protected Rows   batchRows;
    @Wire protected Div    batchEmptyState;
    @Wire protected Label  batchCountLabel;

    @Wire protected Textbox  batchSearchBox;
    @Wire protected Datebox  batchDateFromBox;
    @Wire protected Datebox  batchDateToBox;
    @Wire protected Combobox batchStatusCombo;
    @Wire protected Button   btnClearBatchFilters;

    @Wire protected Button btnBatchPagePrev;
    @Wire protected Button btnBatchPageNext;
    @Wire protected Label  batchPageInfoLabel;

    // Screen 2
    @Wire protected Div    chequeListView;
    @Wire protected Label  activeBatchLabel;
    @Wire protected Rows   chequeRows;
    @Wire protected Div    chequeEmptyState;

    @Wire protected Button btnPagePrev;
    @Wire protected Button btnPageNext;
    @Wire protected Label  pageInfoLabel;
    @Wire protected Label  chequeCountLabel;

    // Chip filter labels (clickable) — no All chip, each is independent toggle
    @Wire protected Label btnFilterHv;       // Type toggle — HV
    @Wire protected Label btnFilterRef;      // Type toggle — RF
    @Wire protected Label hvPendingChip;     // Status toggle — Pending
    @Wire protected Label hvPassedChip;      // Status toggle — Passed/Accepted
    @Wire protected Label hvRejectedChip;    // Status toggle — Rejected

    // Cheque list search + date filter controls
    @Wire protected Textbox chequeSearchBox;
    @Wire protected Datebox chequeDateFromBox;
    @Wire protected Datebox chequeDateToBox;
    @Wire protected Button  btnClearChequeFilters;

    // Popup window
    @Wire protected Window chequeDetailPopup;

    // ── Popup children — wired manually via getFellow() ──────────────────────

    protected Label   popupTitle;
    protected Div     popupTypeBadge;

    protected Button  btnFlipImage;           // single flip button (replaces FRONT/BACK tabs)
    private  boolean  showingFront = true;    // tracks which side is currently visible
    protected Div     imgPanelFront;
    protected Div     imgPanelBack;
    protected Image   frontChequeImage;
    protected Image   rearChequeImage;
    protected Div     frontImagePlaceholder;
    protected Div     rearImagePlaceholder;

    // MICR Data
    protected Label   fChequeNo;
    protected Label   fCityCode;
    protected Label   fBankCode;
    protected Label   fBranchCode;
    protected Label   fTxCode;

    // Cheque Data
    protected Label   fPayeeName;
    protected Label   fAmount;
    protected Label   fAccountNo;
    protected Label   fChequeDate;
    protected Label   fAmountWords;

    // CBS Data (stub)
    protected Label   fCbsPayeeName;
    protected Label   fCbsAccStatus;
    protected Label   fCbsPayeeMatch;
    protected Label   fCbsNewAccount;

    // Action bar
    protected Textbox fVerRemarks;
    protected Button  btnAccept;
    protected Button  btnReject;

    // Footer nav
    protected Label   popupCounter;
    protected Button  btnPopupPrev;
    protected Button  btnPopupNext;

    // ── State ─────────────────────────────────────────────────────────────────

    private List<BatchModel>  allHvBatches;
    private List<ChequeModel> currentBatchCheques;

    /**
     * FIX: Snapshot of the filtered cheque list captured when the popup is
     * opened.  Prev / Next navigation and findNextPendingInList() work against
     * this list so they stay within whatever filter was active at open-time.
     * After Accept / Reject the list is refreshed via getFilteredCheques() so
     * status changes are reflected in subsequent navigation.
     */
    private List<ChequeModel> popupFilteredList;

    private int popupIndex = 0;

    private String batchFilter  = "ALL";
    private int    batchPage    = 1;

    // ── CHEQUE MULTI-SELECT TOGGLE FILTER STATE ───────────────────────────────
    //   Type group   : hvActive, rfActive  — OR within group, AND across groups
    //   Status group : pendingActive, passedActive, rejectedActive — OR within group
    //   Nothing active in a group = show all of that dimension
    private boolean hvActive       = false;
    private boolean rfActive       = false;
    private boolean pendingActive  = false;
    private boolean passedActive   = false;
    private boolean rejectedActive = false;

    // Cheque list search + date range
    private String    chequeSearchText = "";
    private LocalDate chequeDateFrom   = null;
    private LocalDate chequeDateTo     = null;

    private int    chequePage   = 1;

    // Batch list search & date range
    private String    batchSearchText = "";
    private LocalDate batchDateFrom   = null;
    private LocalDate batchDateTo     = null;

    private static final DateTimeFormatter BATCH_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    // ── Session keys ──────────────────────────────────────────────────────────
    private static final String SK_DATE_FROM = "v2_batchDateFrom";
    private static final String SK_DATE_TO   = "v2_batchDateTo";
    private static final String SK_STATUS    = "v2_batchFilter";
    private static final String SK_SEARCH    = "v2_batchSearch";
    private static final String SK_VIEW      = "v2_activeView";
    private static final String SK_BATCH_ID  = "v2_activeBatchId";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // ── Wire popup children manually ──────────────────────────────────────
        popupTitle     = (Label) chequeDetailPopup.getFellow("popupTitle");
        popupTypeBadge = (Div)   chequeDetailPopup.getFellow("popupTypeBadge");

        btnFlipImage          = (Button) chequeDetailPopup.getFellow("btnFlipImage");
        imgPanelFront         = (Div)    chequeDetailPopup.getFellow("imgPanelFront");
        imgPanelBack          = (Div)    chequeDetailPopup.getFellow("imgPanelBack");
        frontChequeImage      = (Image)  chequeDetailPopup.getFellow("frontChequeImage");
        rearChequeImage       = (Image)  chequeDetailPopup.getFellow("rearChequeImage");
        frontImagePlaceholder = (Div)    chequeDetailPopup.getFellow("frontImagePlaceholder");
        rearImagePlaceholder  = (Div)    chequeDetailPopup.getFellow("rearImagePlaceholder");

        fChequeNo   = (Label) chequeDetailPopup.getFellow("fChequeNo");
        fCityCode   = (Label) chequeDetailPopup.getFellow("fCityCode");
        fBankCode   = (Label) chequeDetailPopup.getFellow("fBankCode");
        fBranchCode = (Label) chequeDetailPopup.getFellow("fBranchCode");
        fTxCode     = (Label) chequeDetailPopup.getFellow("fTxCode");

        fPayeeName   = (Label) chequeDetailPopup.getFellow("fPayeeName");
        fAmount      = (Label) chequeDetailPopup.getFellow("fAmount");
        fAccountNo   = (Label) chequeDetailPopup.getFellow("fAccountNo");
        fChequeDate  = (Label) chequeDetailPopup.getFellow("fChequeDate");
        fAmountWords = (Label) chequeDetailPopup.getFellow("fAmountWords");

        fCbsPayeeName  = (Label) chequeDetailPopup.getFellow("fCbsPayeeName");
        fCbsAccStatus  = (Label) chequeDetailPopup.getFellow("fCbsAccStatus");
        fCbsPayeeMatch = (Label) chequeDetailPopup.getFellow("fCbsPayeeMatch");
        fCbsNewAccount = (Label) chequeDetailPopup.getFellow("fCbsNewAccount");

        fVerRemarks   = (Textbox) chequeDetailPopup.getFellow("fVerRemarks");
        btnAccept     = (Button)  chequeDetailPopup.getFellow("btnAccept");
        btnReject     = (Button)  chequeDetailPopup.getFellow("btnReject");

        popupCounter = (Label)  chequeDetailPopup.getFellow("popupCounter");
        btnPopupPrev = (Button) chequeDetailPopup.getFellow("btnPopupPrev");
        btnPopupNext = (Button) chequeDetailPopup.getFellow("btnPopupNext");

        // ── Image flip button ─────────────────────────────────────────────────
        btnFlipImage.addEventListener("onClick", e -> flipImage());

        // ── Popup navigation ──────────────────────────────────────────────────
        Button btnClosePopup = (Button) chequeDetailPopup.getFellow("btnClosePopup");
        btnClosePopup.addEventListener("onClick", e -> {
            chequeDetailPopup.doEmbedded();
            chequeDetailPopup.setVisible(false);
        });

        // FIX: navigate within popupFilteredList, not the full currentBatchCheques
        btnPopupPrev.addEventListener("onClick", e -> {
            if (popupIndex > 0) renderPopup(--popupIndex);
        });
        btnPopupNext.addEventListener("onClick", e -> {
            List<ChequeModel> list = activePopupList();
            if (list != null && popupIndex < list.size() - 1)
                renderPopup(++popupIndex);
        });

        btnAccept.addEventListener("onClick", e -> onAcceptClick());
        btnReject.addEventListener("onClick", e -> onRejectClick());

        chequeDetailPopup.setVisible(false);

        // ── CHEQUE CHIP TOGGLE LISTENERS ─────────────────────────────────────
        //   Each chip is an independent on/off toggle.
        //   Second click on an active chip turns it OFF.
        //   Nothing active in a group = show all of that dimension.
        //   Type group chips AND with Status group chips.
        //   Within each group, active chips are OR'd together.

        btnFilterHv.addEventListener("onClick", e -> {
            hvActive = !hvActive;
            chequePage = 1;
            refreshChequeListChips();
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        btnFilterRef.addEventListener("onClick", e -> {
            rfActive = !rfActive;
            chequePage = 1;
            refreshChequeListChips();
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        hvPendingChip.addEventListener("onClick", e -> {
            pendingActive = !pendingActive;
            chequePage = 1;
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        hvPassedChip.addEventListener("onClick", e -> {
            passedActive = !passedActive;
            chequePage = 1;
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        hvRejectedChip.addEventListener("onClick", e -> {
            rejectedActive = !rejectedActive;
            chequePage = 1;
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        // ── CHEQUE SEARCH + DATE LISTENERS ───────────────────────────────────

        chequeSearchBox.addEventListener("onChange", e -> {
            chequeSearchText = chequeSearchBox.getValue();
            chequePage = 1;
            buildFilteredPagedChequeRows();
        });

        chequeDateFromBox.addEventListener("onChange", e -> {
            java.util.Date d = chequeDateFromBox.getValue();
            chequeDateFrom = (d == null) ? null
                    : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

            // If From now lands after the current To, pull To forward to match
            if (chequeDateFrom != null && chequeDateTo != null && chequeDateFrom.isAfter(chequeDateTo)) {
                chequeDateTo = chequeDateFrom;
                chequeDateToBox.setValue(toUtilDate(chequeDateTo));
            }
            updateDateConstraints(chequeDateFromBox, chequeDateToBox, chequeDateFrom, chequeDateTo);

            chequePage = 1;
            refreshChequeListChips();
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        chequeDateToBox.addEventListener("onChange", e -> {
            java.util.Date d = chequeDateToBox.getValue();
            chequeDateTo = (d == null) ? null
                    : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

            // If To now lands before the current From, pull From back to match
            if (chequeDateFrom != null && chequeDateTo != null && chequeDateTo.isBefore(chequeDateFrom)) {
                chequeDateFrom = chequeDateTo;
                chequeDateFromBox.setValue(toUtilDate(chequeDateFrom));
            }
            updateDateConstraints(chequeDateFromBox, chequeDateToBox, chequeDateFrom, chequeDateTo);

            chequePage = 1;
            refreshChequeListChips();
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        btnClearChequeFilters.addEventListener("onClick", e -> {
            // Reset all cheque filters
            hvActive = false; rfActive = false;
            pendingActive = false; passedActive = false; rejectedActive = false;
            chequeSearchText = "";
            chequeDateFrom = null;
            chequeDateTo   = null;
            chequePage = 1;

            // Drop stale constraints before clearing the values
            chequeDateFromBox.setConstraint((String) null);
            chequeDateToBox.setConstraint((String) null);

            chequeSearchBox.setValue("");
            chequeDateFromBox.setValue(null);
            chequeDateToBox.setValue(null);

            refreshChequeListChips();
            updateChequeFilterStyles();
            buildFilteredPagedChequeRows();
        });

        // ── Restore session state ─────────────────────────────────────────────
        org.zkoss.zk.ui.Session s = Sessions.getCurrent();
        String savedView    = (String) s.getAttribute(SK_VIEW);
        String savedBatchId = (String) s.getAttribute(SK_BATCH_ID);

        if ("CHEQUE_LIST".equals(savedView) && savedBatchId != null) {
            loadHighValueBatches();
            BatchModel target = null;
            if (allHvBatches != null) {
                for (BatchModel b : allHvBatches) {
                    if (savedBatchId.equals(b.getBatchId())) { target = b; break; }
                }
            }
            if (target != null) loadChequeList(target);
        } else {
            loadHighValueBatches();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 1 — BATCH LIST
    // ════════════════════════════════════════════════════════════════════

    private void saveFilterState() {
        org.zkoss.zk.ui.Session s = Sessions.getCurrent();
        s.setAttribute(SK_DATE_FROM, batchDateFrom);
        s.setAttribute(SK_DATE_TO,   batchDateTo);
        s.setAttribute(SK_STATUS,    batchFilter);
        s.setAttribute(SK_SEARCH,    batchSearchText);
    }

    private void saveViewState(String batchId) {
        org.zkoss.zk.ui.Session s = Sessions.getCurrent();
        s.setAttribute(SK_VIEW,     "CHEQUE_LIST");
        s.setAttribute(SK_BATCH_ID, batchId);
    }

    private void clearViewState() {
        org.zkoss.zk.ui.Session s = Sessions.getCurrent();
        s.setAttribute(SK_VIEW,     "BATCH_LIST");
        s.setAttribute(SK_BATCH_ID, null);
    }

    private boolean restoreFilterState() {
        org.zkoss.zk.ui.Session s = Sessions.getCurrent();
        if (s.getAttribute(SK_DATE_FROM) == null) return false;

        batchDateFrom   = (LocalDate) s.getAttribute(SK_DATE_FROM);
        batchDateTo     = (LocalDate) s.getAttribute(SK_DATE_TO);
        batchFilter     = (String)    s.getAttribute(SK_STATUS);
        batchSearchText = (String)    s.getAttribute(SK_SEARCH);
        return true;
    }

    private void loadHighValueBatches() {
        allHvBatches = service.getHighValueBatches();

        int totalHv  = 0;
        int totalRef = 0;
        for (BatchModel b : allHvBatches) {
            totalHv  += parseHvCount(b);
            totalRef += parseRefCount(b);
        }

        totalBatchesChip.setValue(allHvBatches.size() + " Batches");
        totalHvChip.setValue(totalHv + " HV Cheques");
        totalRefChip.setValue(totalRef + " Referred");

        batchPage = 1;

        boolean restored = restoreFilterState();
        if (!restored) {
            LocalDate today = LocalDate.now();
            batchDateFrom   = today;
            batchDateTo     = today;
            batchFilter     = "ALL";
            batchSearchText = "";
            saveFilterState();
        }

        batchSearchBox.setValue(batchSearchText);
        batchStatusCombo.setSelectedIndex(statusIndexFor(batchFilter));

        java.util.Date fromDate = (batchDateFrom == null) ? null :
                java.util.Date.from(batchDateFrom.atStartOfDay(ZoneId.systemDefault()).toInstant());
        java.util.Date toDate = (batchDateTo == null) ? null :
                java.util.Date.from(batchDateTo.atStartOfDay(ZoneId.systemDefault()).toInstant());
        batchDateFromBox.setValue(fromDate);
        batchDateToBox.setValue(toDate);
        updateDateConstraints(batchDateFromBox, batchDateToBox, batchDateFrom, batchDateTo);

        buildFilteredPagedBatchRows();
    }

    private int statusIndexFor(String filter) {
        switch (filter == null ? "ALL" : filter) {
            case "PENDING":    return 1;
            case "VERIFIED":   return 2;
            case "INPROGRESS": return 3;
            default:           return 0;
        }
    }

    @Listen("onChange = #batchSearchBox")
    public void onBatchSearchChange() {
        batchSearchText = batchSearchBox.getValue();
        batchPage = 1;
        saveFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onChange = #batchDateFromBox")
    public void onBatchDateFromChange() {
        Date d = batchDateFromBox.getValue();
        batchDateFrom = (d == null)
                ? null
                : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        // If From now lands after the current To, pull To forward to match
        if (batchDateFrom != null && batchDateTo != null && batchDateFrom.isAfter(batchDateTo)) {
            batchDateTo = batchDateFrom;
            batchDateToBox.setValue(toUtilDate(batchDateTo));
        }
        updateDateConstraints(batchDateFromBox, batchDateToBox, batchDateFrom, batchDateTo);

        batchPage = 1;
        saveFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onChange = #batchDateToBox")
    public void onBatchDateToChange() {
        Date d = batchDateToBox.getValue();
        batchDateTo = (d == null)
                ? null
                : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        // If To now lands before the current From, pull From back to match
        if (batchDateFrom != null && batchDateTo != null && batchDateTo.isBefore(batchDateFrom)) {
            batchDateFrom = batchDateTo;
            batchDateFromBox.setValue(toUtilDate(batchDateFrom));
        }
        updateDateConstraints(batchDateFromBox, batchDateToBox, batchDateFrom, batchDateTo);

        batchPage = 1;
        saveFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onSelect = #batchStatusCombo")
    public void onBatchStatusChange() {
        Comboitem sel = batchStatusCombo.getSelectedItem();
        batchFilter = (sel != null) ? sel.getValue() : "ALL";
        batchPage = 1;
        saveFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onClick = #btnClearBatchFilters")
    public void onClearBatchFilters() {
        LocalDate today = LocalDate.now();

        // Drop any constraint left over from the previous range *before*
        // resetting the values — otherwise resetting to "today" can itself
        // get rejected by a stale "before <old To>" / "after <old From>" bound.
        batchDateFromBox.setConstraint((String) null);
        batchDateToBox.setConstraint((String) null);

        batchDateFrom = today;
        batchDateTo   = today;

        java.util.Date todayDate = java.util.Date.from(
                today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());

        batchSearchBox.setValue("");
        batchDateFromBox.setValue(todayDate);
        batchDateToBox.setValue(todayDate);
        batchStatusCombo.setSelectedIndex(0);
        updateDateConstraints(batchDateFromBox, batchDateToBox, batchDateFrom, batchDateTo);

        batchSearchText = "";
        batchFilter     = "ALL";
        batchPage       = 1;

        saveFilterState();
        buildFilteredPagedBatchRows();
    }

    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (batchPage > 1) { batchPage--; buildFilteredPagedBatchRows(); }
    }

    @Listen("onClick = #btnBatchPageNext")
    public void onBatchPageNext() {
        List<BatchModel> filtered = getFilteredBatches();
        int total = getTotalPages(filtered.size(), BATCH_PAGE_SIZE);
        if (batchPage < total) { batchPage++; buildFilteredPagedBatchRows(); }
    }

    private List<BatchModel> getFilteredBatches() {
        List<BatchModel> out = new ArrayList<>();
        for (BatchModel b : allHvBatches) {
            if (!matchesStatusFilter(b)) continue;
            if (!matchesSearchFilter(b)) continue;
            if (!matchesDateFilter(b))   continue;
            out.add(b);
        }
        return out;
    }

    private boolean matchesStatusFilter(BatchModel b) {
        if ("ALL".equals(batchFilter)) return true;
        return batchFilter.equals(getV2Status(b));
    }

    private boolean matchesSearchFilter(BatchModel b) {
        if (batchSearchText == null || batchSearchText.isBlank()) return true;
        String id = b.getBatchId();
        if (id == null) return false;
        return id.toLowerCase().contains(batchSearchText.trim().toLowerCase());
    }

    private boolean matchesDateFilter(BatchModel b) {
        if (batchDateFrom == null && batchDateTo == null) return true;
        if (b.getCreatedAt() == null) return false;
        LocalDate created = b.getCreatedAt().toLocalDate();
        if (batchDateFrom != null && created.isBefore(batchDateFrom)) return false;
        if (batchDateTo   != null && created.isAfter(batchDateTo))   return false;
        return true;
    }

    private void buildFilteredPagedBatchRows() {
        List<BatchModel> filtered = getFilteredBatches();
        int totalPages = getTotalPages(filtered.size(), BATCH_PAGE_SIZE);
        if (batchPage > totalPages) batchPage = totalPages;

        batchPageInfoLabel.setValue("Page " + batchPage + " of " + totalPages);
        btnBatchPagePrev.setDisabled(batchPage <= 1);
        btnBatchPageNext.setDisabled(batchPage >= totalPages);

        int from = (batchPage - 1) * BATCH_PAGE_SIZE;
        int to   = Math.min(from + BATCH_PAGE_SIZE, filtered.size());
        List<BatchModel> page = filtered.subList(from, to);

        batchRows.getChildren().clear();
        batchEmptyState.setVisible(page.isEmpty());

        for (BatchModel b : page) batchRows.appendChild(buildBatchRow(b));

        int totalFiltered = filtered.size();
        if (totalFiltered == 0) {
            batchCountLabel.setValue("Showing 0 batches");
        } else {
            batchCountLabel.setValue(
                "Showing " + (from + 1) + "–" + to
                + " of " + totalFiltered + " batches");
        }
    }

    private Row buildBatchRow(BatchModel b) {
        int hv        = parseHvCount(b);
        int pending   = parsePendingCount(b);
        int processed = parseProcessedCount(b);

        Row row = new Row();
        Label batchIdLbl = new Label(safe(b.getBatchId()));
        batchIdLbl.setSclass("v2-batch-no-cell");
        row.appendChild(cell(batchIdLbl, ""));

        Label dateLbl = new Label(formatBatchDate(b.getCreatedAt()));
        dateLbl.setSclass("v2-date-cell");
        row.appendChild(cell(dateLbl, "v2-center"));

        row.appendChild(cell(new Label(String.valueOf(b.getTotalCheques())), "v2-center"));
        Label hvLbl = new Label(String.valueOf(hv));
        hvLbl.setSclass("v2-hv-count");
        row.appendChild(cell(hvLbl, "v2-center"));
        row.appendChild(cell(new Label(String.valueOf(pending)),   "v2-center"));
        row.appendChild(cell(new Label(String.valueOf(processed)), "v2-center"));

        String v2Status = getV2Status(b);
        String statusText, statusCss;
        switch (v2Status) {
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
        Label statusLbl = new Label(statusText);
        statusLbl.setSclass(statusCss);
        row.appendChild(cell(statusLbl, "v2-center"));

        Button processBtn = new Button("Process");
        processBtn.setSclass("v2-process-btn");
        processBtn.addEventListener("onClick", e -> loadChequeList(b));
        row.appendChild(cell(processBtn, "v2-center"));

        return row;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 2 — CHEQUE LIST
    // ════════════════════════════════════════════════════════════════════

    private void loadChequeList(BatchModel b) {
        currentBatchCheques = service.getHighValueChequesForBatch(b.getBatchId());
        activeBatchLabel.setValue(b.getBatchId());

        // Reset all cheque filter toggles + search/date when entering a batch
        hvActive = false; rfActive = false;
        pendingActive = false; passedActive = false; rejectedActive = false;
        chequeSearchText = "";
        chequeDateFrom   = null;
        chequeDateTo     = null;
        chequePage       = 1;

        // Reset popup filtered list
        popupFilteredList = null;

        chequeSearchBox.setValue("");
        chequeDateFromBox.setValue(null);
        chequeDateToBox.setValue(null);

        refreshChequeListChips();
        updateChequeFilterStyles();
        buildFilteredPagedChequeRows();

        batchListView.setVisible(false);
        chequeListView.setVisible(true);
        saveViewState(b.getBatchId());
    }

    @Listen("onClick = #btnPagePrev")
    public void onPagePrev() {
        if (chequePage > 1) { chequePage--; buildFilteredPagedChequeRows(); }
    }

    @Listen("onClick = #btnPageNext")
    public void onPageNext() {
        List<ChequeModel> filtered = getFilteredCheques();
        int total = getTotalPages(filtered.size(), CHEQUE_PAGE_SIZE);
        if (chequePage < total) { chequePage++; buildFilteredPagedChequeRows(); }
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHEQUE FILTER — getFilteredCheques()
    //
    //  Type group   (HV / RF)              : OR within group, AND with status group
    //  Status group (Pending/Passed/Rejected): OR within group
    //  Search       : cheque no. OR payee name substring match
    //  Date range   : cheque date (dd/MM/yyyy) between chequeDateFrom and chequeDateTo
    //
    //  Nothing active in a group = no filter applied for that dimension
    // ════════════════════════════════════════════════════════════════════

    private List<ChequeModel> getFilteredCheques() {
        if (currentBatchCheques == null) return new ArrayList<>();

        List<ChequeModel> out = new ArrayList<>();

        for (ChequeModel c : currentBatchCheques) {

            // ── Type group filter (HV / RF) ───────────────────────────────────
            // If neither is active → show all types
            // If one or both active → cheque must match at least one active type (OR)
            boolean anyTypeActive = hvActive || rfActive;
            if (anyTypeActive) {
                boolean referred = c.isReferred();
                boolean typeMatch = (hvActive && !referred) || (rfActive && referred);
                if (!typeMatch) continue;
            }

            // ── Status group filter (Pending / Passed / Rejected) ─────────────
            // If none active → show all statuses
            // If one or more active → cheque must match at least one active status (OR)
            boolean anyStatusActive = pendingActive || passedActive || rejectedActive;
            if (anyStatusActive) {
                ChequeStatus cs    = ChequeStatus.fromDb(c.getVerStatus());
                boolean isPassed   = cs == ChequeStatus.VERIFIED;
                boolean isRejected = cs == ChequeStatus.REJECTED;
                boolean isPending  = !isPassed && !isRejected;
                boolean statusMatch = (pendingActive  && isPending)
                                   || (passedActive   && isPassed)
                                   || (rejectedActive && isRejected);
                if (!statusMatch) continue;
            }

            // ── Search filter (cheque no. or payee name) ──────────────────────
            if (chequeSearchText != null && !chequeSearchText.isBlank()) {
                String query     = chequeSearchText.trim().toLowerCase();
                String chequeNo  = (c.getChequeNo()  != null) ? c.getChequeNo().toLowerCase()  : "";
                String payeeName = (c.getPayeeName() != null) ? c.getPayeeName().toLowerCase() : "";
                if (!chequeNo.contains(query) && !payeeName.contains(query)) continue;
            }

            // ── Cheque date range filter ───────────────────────────────────────
            // chequeDate is a String in "dd/MM/yyyy" format
            if (chequeDateFrom != null || chequeDateTo != null) {
                LocalDate chequeDate = parseChequeDate(c.getChequeDate());
                if (chequeDate == null) continue; // skip if date unparseable
                if (chequeDateFrom != null && chequeDate.isBefore(chequeDateFrom)) continue;
                if (chequeDateTo   != null && chequeDate.isAfter(chequeDateTo))    continue;
            }

            out.add(c);
        }

        return out;
    }

    // Parses chequeDate String "dd/MM/yyyy" → LocalDate; returns null if invalid
    private LocalDate parseChequeDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.trim(),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return null;
        }
    }

    private void buildFilteredPagedChequeRows() {
        List<ChequeModel> filtered = getFilteredCheques();
        int totalPages = getTotalPages(filtered.size(), CHEQUE_PAGE_SIZE);
        if (chequePage > totalPages) chequePage = totalPages;

        pageInfoLabel.setValue("Page " + chequePage + " of " + totalPages);
        btnPagePrev.setDisabled(chequePage <= 1);
        btnPageNext.setDisabled(chequePage >= totalPages);

        int from = (chequePage - 1) * CHEQUE_PAGE_SIZE;
        int to   = Math.min(from + CHEQUE_PAGE_SIZE, filtered.size());
        List<ChequeModel> page = filtered.subList(from, to);

        chequeRows.getChildren().clear();
        chequeEmptyState.setVisible(page.isEmpty());

        int sno = from + 1;
        for (ChequeModel c : page) chequeRows.appendChild(buildChequeRow(sno++, c));

        int totalFiltered = filtered.size();
        if (chequeCountLabel != null) {
            if (totalFiltered == 0) {
                chequeCountLabel.setValue("Showing 0 cheques");
            } else {
                chequeCountLabel.setValue(
                    "Showing " + (from + 1) + "–" + to
                    + " of " + totalFiltered + " cheques");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHIP COUNTS — refreshChequeListChips()
    //
    //  HV / RF counts   : always full totals, never filtered
    //  Status counts    : scoped to active type toggles
    //                     (if HV active → pending/passed/rejected count HV only)
    // ════════════════════════════════════════════════════════════════════

    private void refreshChequeListChips() {
        if (currentBatchCheques == null) return;

        int hvCount = 0, rfCount = 0;
        int pending = 0, passed = 0, rejected = 0;

        for (ChequeModel c : currentBatchCheques) {
            boolean referred = c.isReferred();

            // Apply date filter to chip counts
            // (search filter does NOT affect counts — only date does)
            if (chequeDateFrom != null || chequeDateTo != null) {
                LocalDate chequeDate = parseChequeDate(c.getChequeDate());
                if (chequeDate == null) continue;
                if (chequeDateFrom != null && chequeDate.isBefore(chequeDateFrom)) continue;
                if (chequeDateTo   != null && chequeDate.isAfter(chequeDateTo))    continue;
            }

            // HV / RF totals — within date range
            if (referred) rfCount++;
            else          hvCount++;

            // Status counts — scoped to active type toggles + date range
            boolean anyTypeActive = hvActive || rfActive;
            boolean inScope;
            if (!anyTypeActive) {
                inScope = true;
            } else {
                inScope = (hvActive && !referred) || (rfActive && referred);
            }

            if (inScope) {
                ChequeStatus cs = ChequeStatus.fromDb(c.getVerStatus());
                if      (cs == ChequeStatus.VERIFIED) passed++;
                else if (cs == ChequeStatus.REJECTED) rejected++;
                else                                   pending++;
            }
        }

        btnFilterHv.setValue(hvCount + " High Value");
        btnFilterRef.setValue(rfCount + " Referred");
        hvPendingChip.setValue(pending + " Pending");
        hvPassedChip.setValue(passed + " Passed");
        hvRejectedChip.setValue(rejected + " Rejected");
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHIP STYLES — updateChequeFilterStyles()
    //  Adds chip-filter-active when the toggle boolean is true
    // ════════════════════════════════════════════════════════════════════

    private void updateChequeFilterStyles() {
        // Each chip gets chip-filter-active added when its toggle is ON
        btnFilterHv.setSclass("v2-stat-chip chip-filter-hv chip-filter"
                + (hvActive       ? " chip-filter-active" : ""));
        btnFilterRef.setSclass("v2-stat-chip chip-filter-ref chip-filter"
                + (rfActive       ? " chip-filter-active" : ""));
        hvPendingChip.setSclass("v2-stat-chip pending-chip chip-filter"
                + (pendingActive  ? " chip-filter-active" : ""));
        hvPassedChip.setSclass("v2-stat-chip passed-chip chip-filter"
                + (passedActive   ? " chip-filter-active" : ""));
        hvRejectedChip.setSclass("v2-stat-chip rejected-chip chip-filter"
                + (rejectedActive ? " chip-filter-active" : ""));
    }

    private Row buildChequeRow(int sno, ChequeModel c) {
        Row row = new Row();
        row.appendChild(cell(new Label(String.valueOf(sno)), "v2-center"));
        row.appendChild(cell(new Label(safe(c.getChequeNo())), ""));
        row.appendChild(cell(new Label(safe(c.getPayeeName())), ""));
        row.appendChild(cell(new Label(formatAmount(c.getAmount())), "v2-right"));
        row.appendChild(cell(new Label(safe(c.getChequeDate())), "v2-center"));
        row.appendChild(cell(buildFlagLabel(c.isReferred()), "v2-center"));

        String vs = (c.getVerStatus() != null) ? c.getVerStatus() : "PENDING";
        String vsDisplay = ("V1_PENDING".equalsIgnoreCase(vs) || "V2_PENDING".equalsIgnoreCase(vs)
                || "SUBMITTED".equalsIgnoreCase(vs)) ? "PENDING" : vs;
        Label vsLbl = new Label(vsDisplay);
        vsLbl.setSclass("v2-status-badge v2-status-" + safeStatus(vsDisplay));
        row.appendChild(cell(vsLbl, "v2-center"));

        // FIX: pass the cheque object itself — openChequePopup will find its
        // position inside the current filtered list at click-time, so the popup
        // counter and Prev/Next are scoped to the visible filtered set.
        Button openBtn = new Button("Open");
        openBtn.setSclass("v2-open-btn");
        openBtn.addEventListener("onClick", e -> openChequePopup(c));
        row.appendChild(cell(openBtn, "v2-center"));

        return row;
    }

    private Label buildFlagLabel(boolean referred) {
        if (referred) {
            Label lbl = new Label("⇄ REF");
            lbl.setSclass("v2-ref-flag");
            return lbl;
        }
        Label lbl = new Label("⚑ HV");
        lbl.setSclass("v2-hv-flag");
        return lbl;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN 3 — POPUP
    // ════════════════════════════════════════════════════════════════════

    /**
     * FIX: Opens the popup for a specific cheque object.
     * Snapshots the current filtered list into popupFilteredList so that
     * Prev / Next and findNextPendingInList() stay within the filtered set.
     */
    private void openChequePopup(ChequeModel cheque) {
        // Capture the filtered list at open-time
        popupFilteredList = getFilteredCheques();

        // Find the index of the clicked cheque inside the filtered list
        popupIndex = popupFilteredList.indexOf(cheque);
        if (popupIndex < 0) popupIndex = 0; // safety fallback

        resetImageToFront();
        renderPopup(popupIndex);
        chequeDetailPopup.setVisible(true);
        chequeDetailPopup.doModal();
    }

    /**
     * Returns the list the popup should navigate within.
     * Falls back to the full batch list if the snapshot is somehow null.
     */
    private List<ChequeModel> activePopupList() {
        return (popupFilteredList != null && !popupFilteredList.isEmpty())
               ? popupFilteredList
               : currentBatchCheques;
    }

    /**
     * FIX: Renders a cheque at the given index inside activePopupList().
     * Counter shows "x / total" relative to the filtered set, not the full batch.
     */
    private void renderPopup(int index) {
        List<ChequeModel> list = activePopupList();
        if (list == null || list.isEmpty()) return;

        ChequeModel c = list.get(index);

        popupTitle.setValue("Cheque  #" + safe(c.getChequeNo()));

        boolean referred = c.isReferred();
        if (referred) {
            popupTypeBadge.setSclass("v2-badge-ref");
            popupTypeBadge.getChildren().clear();
            popupTypeBadge.appendChild(new Label("REFERRED"));
        } else {
            popupTypeBadge.setSclass("v2-badge-hv");
            popupTypeBadge.getChildren().clear();
            popupTypeBadge.appendChild(new Label("HIGH VALUE"));
        }

        String sortCode = safe(c.getSortCode());
        String[] micr   = splitMicr(sortCode);
        fChequeNo.setValue(safe(c.getChequeNo()));
        fCityCode.setValue(micr[0]);
        fBankCode.setValue(micr[1]);
        fBranchCode.setValue(micr[2]);
        fTxCode.setValue(safe(c.getTransactionCode()));

        fPayeeName.setValue(safe(c.getPayeeName()));
        fAmount.setValue(formatAmount(c.getAmount()));
        fAccountNo.setValue(safe(c.getAccountNo()));
        fChequeDate.setValue(safe(c.getChequeDate()));
        fAmountWords.setValue(safe(c.getAmountInWords()));

        fCbsPayeeName.setValue("—");
        fCbsAccStatus.setValue("—");
        fCbsPayeeMatch.setValue("—");
        fCbsNewAccount.setValue("—");

        ChequeStatus currentVs    = ChequeStatus.fromDb(c.getVerStatus());
        boolean alreadyActioned = currentVs == ChequeStatus.VERIFIED
                               || currentVs == ChequeStatus.REJECTED;
        btnAccept.setDisabled(alreadyActioned);
        btnReject.setDisabled(alreadyActioned);

        fVerRemarks.setValue("");
        fVerRemarks.setSclass("v2-remarks-box");

        byte[] frontBytes = c.getFrontImageBytes();
        if (frontBytes != null && frontBytes.length > 0) {
            frontChequeImage.setSrc("data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(frontBytes));
            frontChequeImage.setVisible(true);
            frontImagePlaceholder.setVisible(false);
        } else {
            frontChequeImage.setVisible(false);
            frontImagePlaceholder.setVisible(true);
        }

        byte[] rearBytes = c.getRearImageBytes();
        if (rearBytes != null && rearBytes.length > 0) {
            rearChequeImage.setSrc("data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(rearBytes));
            rearChequeImage.setVisible(true);
            rearImagePlaceholder.setVisible(false);
        } else {
            rearChequeImage.setVisible(false);
            rearImagePlaceholder.setVisible(true);
        }

        // FIX: counter and nav buttons are relative to the filtered list
        int total = list.size();
        popupCounter.setValue((index + 1) + " / " + total);
        btnPopupPrev.setDisabled(index == 0);
        btnPopupNext.setDisabled(index == total - 1);
    }

    /**
     * Flips the cheque image between FRONT and BACK.
     * Button label toggles:  showing front → label is "Show Back"
     *                        showing back  → label is "Show Front"
     */
    private void flipImage() {
        showingFront = !showingFront;
        imgPanelFront.setVisible(showingFront);
        imgPanelBack.setVisible(!showingFront);
        btnFlipImage.setLabel(showingFront ? "Show Back" : "Show Front");
    }

    /** Reset image to front side whenever a new cheque is opened */
    private void resetImageToFront() {
        showingFront = true;
        imgPanelFront.setVisible(true);
        imgPanelBack.setVisible(false);
        btnFlipImage.setLabel("Show Back");
    }

    // ════════════════════════════════════════════════════════════════════
    //  VERIFICATION ACTIONS
    // ════════════════════════════════════════════════════════════════════

    private void onAcceptClick() {
        String verBy  = getVerifierUsername();
        String remarks = fVerRemarks.getValue();
        if (remarks == null || remarks.isBlank()) {
            remarks = "Accepted by " + verBy;
            fVerRemarks.setValue(remarks);
        }
        performVerification(ChequeStatus.VERIFIED.db(), remarks, verBy);
    }

    private void onRejectClick() {
        String remarks = fVerRemarks.getValue();
        if (remarks == null || remarks.isBlank()) {
            fVerRemarks.setSclass("v2-remarks-box v2-remarks-box-error");
            fVerRemarks.focus();
            return;
        }
        fVerRemarks.setSclass("v2-remarks-box");

        String verBy = getVerifierUsername();
        performVerification(ChequeStatus.REJECTED.db(), remarks, verBy);
    }

    /**
     * FIX: After saving the verification result the popup filtered list is
     * refreshed from getFilteredCheques() so it reflects the new status.
     * findNextPendingInList() then searches within that refreshed filtered list,
     * not the full batch list — keeping navigation scoped to the active filter.
     */
    private void performVerification(String action, String remarks, String verBy) {
        List<ChequeModel> list = activePopupList();
        if (list == null || list.isEmpty()) return;

        ChequeModel c      = list.get(popupIndex);
        long        chequeId = Long.parseLong(c.getId());

        service.verifyHighValueCheque(chequeId, action, verBy, remarks);

        ChequeStatus resolved = ChequeStatus.fromDb(action);
        String newStatus = (resolved == ChequeStatus.VERIFIED)
                ? ChequeStatus.VERIFIED.db()
                : ChequeStatus.REJECTED.db();
        c.setVerStatus(newStatus);

        service.checkAndUpdateBatchStatus(c.getBatchId());

        // Refresh chip counts and cheque list rows
        refreshChequeListChips();
        buildFilteredPagedChequeRows();

        // FIX: refresh the popup filtered list after the status change so the
        // next-pending search operates on the up-to-date filtered set
        popupFilteredList = getFilteredCheques();

        int next = findNextPendingInList(popupIndex, popupFilteredList);
        popupIndex = (next >= 0) ? next : popupIndex;
        renderPopup(popupIndex);
    }

    /**
     * FIX: Searches for the next pending cheque within the given list.
     * Replaces the old findNextPending() which always used currentBatchCheques.
     */
    private int findNextPendingInList(int currentIndex, List<ChequeModel> list) {
        if (list == null) return -1;
        for (int i = currentIndex + 1; i < list.size(); i++) {
            ChequeStatus cs = ChequeStatus.fromDb(list.get(i).getVerStatus());
            if (cs != ChequeStatus.VERIFIED && cs != ChequeStatus.REJECTED) return i;
        }
        for (int i = currentIndex - 1; i >= 0; i--) {
            ChequeStatus cs = ChequeStatus.fromDb(list.get(i).getVerStatus());
            if (cs != ChequeStatus.VERIFIED && cs != ChequeStatus.REJECTED) return i;
        }
        return -1;
    }

    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        chequeListView.setVisible(false);
        batchListView.setVisible(true);
        currentBatchCheques = null;
        popupFilteredList   = null;

        // Reset all cheque filter state on leaving the cheque list
        hvActive = false; rfActive = false;
        pendingActive = false; passedActive = false; rejectedActive = false;
        chequeSearchText = "";
        chequeDateFrom   = null;
        chequeDateTo     = null;
        chequePage       = 1;

        clearViewState();
        loadHighValueBatches();
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String getVerifierUsername() {
        String u = (String) Sessions.getCurrent()
            .getAttribute(com.cts.composer.LoginComposer.SESS_USER_NAME);
        return (u == null || u.isBlank()) ? "Unknown" : u;
    }

    /**
     * Keeps a From/To datebox pair from accepting an invalid range:
     * To can't be set earlier than From, and From can't be set later than To.
     * Uses ZK Datebox's built-in "after yyyyMMdd" / "before yyyyMMdd"
     * constraint syntax (both bounds inclusive of the given date).
     */
    private void updateDateConstraints(Datebox fromBox, Datebox toBox, LocalDate from, LocalDate to) {
        if (toBox != null) {
            toBox.setConstraint(from != null ? "after " + yyyymmdd(from) : (String) null);
        }
        if (fromBox != null) {
            fromBox.setConstraint(to != null ? "before " + yyyymmdd(to) : (String) null);
        }
    }

    private String yyyymmdd(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private Date toUtilDate(LocalDate d) {
        return (d == null) ? null : Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private boolean isReferred(String iqaStatus) {
        if (iqaStatus == null) return false;
        if (!iqaStatus.startsWith("VACTION:")) return false;
        String action = iqaStatus.substring("VACTION:".length());
        return "REFERRED".equalsIgnoreCase(action);
    }

    private String[] splitMicr(String sortCode) {
        if (sortCode == null || sortCode.isBlank() || "——".equals(sortCode) || "0".equals(sortCode)) {
            return new String[]{"—", "—", "—"};
        }
        String s = sortCode.trim();
        if (s.length() >= 9) return new String[]{s.substring(0, 3), s.substring(3, 6), s.substring(6, 9)};
        if (s.length() >= 6) return new String[]{s.substring(0, 3), s.substring(3, 6), "—"};
        return new String[]{s, "—", "—"};
    }

    private int getTotalPages(int listSize, int pageSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / pageSize);
    }

    private int parseHvCount(BatchModel b)        { return parsePart(b.getPresentingBankId(), 0); }
    private int parsePendingCount(BatchModel b)   { return parsePart(b.getPresentingBankId(), 1); }
    private int parseProcessedCount(BatchModel b) { return parsePart(b.getPresentingBankId(), 2); }
    private int parseRefCount(BatchModel b)       { return parsePart(b.getPresentingBankId(), 3); }

    private String getV2Status(BatchModel b) {
        int hv        = parseHvCount(b);
        int processed = parseProcessedCount(b);
        if (processed == 0)   return "PENDING";
        if (processed < hv)   return "INPROGRESS";
        return "VERIFIED";
    }

    private int parsePart(String encoded, int index) {
        if (encoded == null || encoded.isEmpty()) return 0;
        try {
            String[] parts = encoded.split("\\|");
            return parts.length > index ? Integer.parseInt(parts[index]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setFilterActive(Button btn, boolean active) {
        btn.setSclass(active ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
    }

    private Cell cell(Component child, String sclass) {
        Cell c = new Cell();
        if (sclass != null && !sclass.isEmpty()) c.setSclass(sclass);
        c.appendChild(child);
        return c;
    }

    private String safe(String val) {
        return (val == null || val.isBlank()) ? "—" : val;
    }

    private String safeStatus(String status) {
        return (status == null || status.isBlank()) ? "pending" : status.toLowerCase();
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "——";
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "Rs. " + nf.format(amount);
    }

    private String formatBatchDate(java.time.LocalDateTime dt) {
        if (dt == null) return "—";
        return dt.format(BATCH_DATE_FMT);
    }
}