package com.cts.outward.composer;

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.service.Verification2Service;
import com.cts.outward.service.Verification2ServiceImpl;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.*;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Verification2Composer
 *
 * Features in this version:
 *   BATCH LIST (Screen 1):
 *     - Filter: All / Verified / In Progress
 *     - Pagination: BATCH_PAGE_SIZE batches per page
 *
 *   CHEQUE LIST (Screen 2):
 *     - Filter: All / Pending / Accepted / Rejected
 *     - Pagination: CHEQUE_PAGE_SIZE cheques per page
 *
 * ZK @Wire / getFellow() note:
 *   Components inside mode="overlapped" Window are moved to page root by ZK,
 *   so @Wire fails for them. They are wired manually via getFellow() in
 *   doAfterCompose(). All popup-internal fields are declared without @Wire.
 */
public class Verification2Composer extends SelectorComposer<Component> {

    private final Verification2Service service = new Verification2ServiceImpl();

    private static final int BATCH_PAGE_SIZE  = 5;  // batches per page
    private static final int CHEQUE_PAGE_SIZE = 10;  // cheques per page

    // ── Wired — page-level components (not inside overlapped window) ──────────

    @Wire protected Label  totalBatchesChip;
    @Wire protected Label  totalHvChip;

    // Screen 1 — batch list
    @Wire protected Div    batchListView;
    @Wire protected Rows   batchRows;
    @Wire protected Div    batchEmptyState;
    @Wire protected Label  batchCountLabel;

    // Batch filter buttons
    @Wire protected Button btnBatchFilterAll;
    @Wire protected Button btnBatchFilterVerified;
    @Wire protected Button btnBatchFilterInProgress;

    // Batch pagination
    @Wire protected Button btnBatchPagePrev;
    @Wire protected Button btnBatchPageNext;
    @Wire protected Label  batchPageInfoLabel;

    // Screen 2 — cheque list
    @Wire protected Div    chequeListView;
    @Wire protected Label  activeBatchLabel;
    @Wire protected Label  hvPendingChip;
    @Wire protected Label  hvPassedChip;
    @Wire protected Label  hvRejectedChip;
    @Wire protected Label  progressLabel;
    @Wire protected Div    progressFill;
    @Wire protected Rows   chequeRows;
    @Wire protected Div    chequeEmptyState;

    // Cheque filter buttons
    @Wire protected Button btnFilterAll;
    @Wire protected Button btnFilterPending;
    @Wire protected Button btnFilterAccepted;
    @Wire protected Button btnFilterRejected;

    // Cheque pagination
    @Wire protected Button btnPagePrev;
    @Wire protected Button btnPageNext;
    @Wire protected Label  pageInfoLabel;

    // ── Popup window ──────────────────────────────────────────────────────────
    @Wire protected Window chequeDetailPopup;

    // Popup children — wired manually via getFellow() (overlapped window trick)
    protected Label   popupTitle;
    protected Label   fChequeNo;
    protected Label   fSortCode;
    protected Label   fTransactionCode;
    protected Label   fAccountNo;
    protected Label   fChequeDate;
    protected Label   fAmount;
    protected Label   fAmountInWords;
    protected Label   fPayeeName;
    protected Label   fDrawerName;
    protected Image   frontChequeImage;
    protected Image   rearChequeImage;
    protected Div     frontImagePlaceholder;
    protected Div     rearImagePlaceholder;
    protected Label   popupCounter;
    protected Button  btnPopupPrev;
    protected Button  btnPopupNext;
    protected Textbox fVerRemarks;
    protected Button  btnAccept;
    protected Button  btnReject;

    // ── State ─────────────────────────────────────────────────────────────────

    private List<BatchModel>  allHvBatches;         // full batch list from DB
    private List<ChequeModel> currentBatchCheques;  // full cheque list for selected batch
    private int               popupIndex = 0;

    // Batch filter + pagination
    private String batchFilter = "ALL";  // ALL / VERIFIED / INPROGRESS
    private int    batchPage   = 1;

    // Cheque filter + pagination
    private String chequeFilter = "ALL";  // ALL / PENDING / ACCEPTED / REJECTED
    private int    chequePage   = 1;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // Wire popup children manually
        popupTitle            = (Label)   chequeDetailPopup.getFellow("popupTitle");
        fChequeNo             = (Label)   chequeDetailPopup.getFellow("fChequeNo");
        fSortCode             = (Label)   chequeDetailPopup.getFellow("fSortCode");
        fTransactionCode      = (Label)   chequeDetailPopup.getFellow("fTransactionCode");
        fAccountNo            = (Label)   chequeDetailPopup.getFellow("fAccountNo");
        fChequeDate           = (Label)   chequeDetailPopup.getFellow("fChequeDate");
        fAmount               = (Label)   chequeDetailPopup.getFellow("fAmount");
        fAmountInWords        = (Label)   chequeDetailPopup.getFellow("fAmountInWords");
        fPayeeName            = (Label)   chequeDetailPopup.getFellow("fPayeeName");
        fDrawerName           = (Label)   chequeDetailPopup.getFellow("fDrawerName");
        frontChequeImage      = (Image)   chequeDetailPopup.getFellow("frontChequeImage");
        rearChequeImage       = (Image)   chequeDetailPopup.getFellow("rearChequeImage");
        frontImagePlaceholder = (Div)     chequeDetailPopup.getFellow("frontImagePlaceholder");
        rearImagePlaceholder  = (Div)     chequeDetailPopup.getFellow("rearImagePlaceholder");
        popupCounter          = (Label)   chequeDetailPopup.getFellow("popupCounter");
        btnPopupPrev          = (Button)  chequeDetailPopup.getFellow("btnPopupPrev");
        btnPopupNext          = (Button)  chequeDetailPopup.getFellow("btnPopupNext");
        fVerRemarks           = (Textbox) chequeDetailPopup.getFellow("fVerRemarks");
        btnAccept             = (Button)  chequeDetailPopup.getFellow("btnAccept");
        btnReject             = (Button)  chequeDetailPopup.getFellow("btnReject");

        Button btnClosePopup = (Button) chequeDetailPopup.getFellow("btnClosePopup");
        btnClosePopup.addEventListener("onClick", e -> chequeDetailPopup.setVisible(false));
        btnPopupPrev.addEventListener("onClick",  e -> { if (popupIndex > 0) renderPopup(--popupIndex); });
        btnPopupNext.addEventListener("onClick",  e -> {
            if (currentBatchCheques != null && popupIndex < currentBatchCheques.size() - 1)
                renderPopup(++popupIndex);
        });
        btnAccept.addEventListener("onClick", e -> performVerification("ACCEPTED"));
        btnReject.addEventListener("onClick", e -> performVerification("REJECTED"));

        chequeDetailPopup.setVisible(false);

        loadHighValueBatches();
    }

    // ── SCREEN 1 — Batch list ─────────────────────────────────────────────────

    /**
     * Loads all HV batches from DB into allHvBatches,
     * resets filter + page to default, then rebuilds the batch grid.
     */
    private void loadHighValueBatches() {
        allHvBatches = service.getHighValueBatches();

        int totalHv = 0;
        for (BatchModel b : allHvBatches) totalHv += parseHvCount(b);

        totalBatchesChip.setValue(allHvBatches.size() + " Batches");
        totalHvChip.setValue(totalHv + " HV Cheques");

        // Reset batch filter + page whenever list is freshly loaded
        batchFilter = "ALL";
        batchPage   = 1;
        updateBatchFilterStyles();
        buildFilteredPagedBatchRows();
    }

    // ── Batch filter listeners ────────────────────────────────────────────────

    @Listen("onClick = #btnBatchFilterAll")
    public void onBatchFilterAll() {
        batchFilter = "ALL";
        batchPage   = 1;
        updateBatchFilterStyles();
        buildFilteredPagedBatchRows();
    }

    @Listen("onClick = #btnBatchFilterVerified")
    public void onBatchFilterVerified() {
        batchFilter = "VERIFIED";
        batchPage   = 1;
        updateBatchFilterStyles();
        buildFilteredPagedBatchRows();
    }

    @Listen("onClick = #btnBatchFilterInProgress")
    public void onBatchFilterInProgress() {
        batchFilter = "INPROGRESS";
        batchPage   = 1;
        updateBatchFilterStyles();
        buildFilteredPagedBatchRows();
    }

    // ── Batch pagination listeners ────────────────────────────────────────────

    @Listen("onClick = #btnBatchPagePrev")
    public void onBatchPagePrev() {
        if (batchPage > 1) {
            batchPage--;
            buildFilteredPagedBatchRows();
        }
    }

    @Listen("onClick = #btnBatchPageNext")
    public void onBatchPageNext() {
        int totalPages = getTotalPages(getFilteredBatchList().size(), BATCH_PAGE_SIZE);
        if (batchPage < totalPages) {
            batchPage++;
            buildFilteredPagedBatchRows();
        }
    }

    // ── Core: filter + page + rebuild batch rows ──────────────────────────────

    private void buildFilteredPagedBatchRows() {
        List<BatchModel> filtered = getFilteredBatchList();

        batchRows.getChildren().clear();

        if (filtered.isEmpty()) {
            batchEmptyState.setVisible(true);
            updateBatchPaginationBar(0);
            batchCountLabel.setValue("No batches match the selected filter");
            return;
        }
        batchEmptyState.setVisible(false);
        batchCountLabel.setValue("Showing " + filtered.size() + " batch(es)");

        int totalPages = getTotalPages(filtered.size(), BATCH_PAGE_SIZE);
        if (batchPage > totalPages) batchPage = totalPages;

        int start = (batchPage - 1) * BATCH_PAGE_SIZE;
        int end   = Math.min(start + BATCH_PAGE_SIZE, filtered.size());

        for (int i = start; i < end; i++) {
            batchRows.appendChild(buildBatchRow(filtered.get(i)));
        }

        updateBatchPaginationBar(filtered.size());
    }

    /**
     * Returns a filtered copy of allHvBatches based on batchFilter.
     * VERIFIED    → pending count == 0
     * INPROGRESS  → pending count  > 0
     * ALL         → everything
     */
    private List<BatchModel> getFilteredBatchList() {
        List<BatchModel> result = new ArrayList<>();
        for (BatchModel b : allHvBatches) {
            int pending = parsePendingCount(b);
            if ("VERIFIED".equals(batchFilter)) {
                if (pending == 0) result.add(b);
            } else if ("INPROGRESS".equals(batchFilter)) {
                if (pending > 0) result.add(b);
            } else {
                result.add(b);  // ALL
            }
        }
        return result;
    }

    private void updateBatchPaginationBar(int filteredSize) {
        int totalPages = getTotalPages(filteredSize, BATCH_PAGE_SIZE);
        batchPageInfoLabel.setValue("Page " + batchPage + " of " + totalPages);
        btnBatchPagePrev.setDisabled(batchPage <= 1);
        btnBatchPageNext.setDisabled(batchPage >= totalPages);
    }

    private void updateBatchFilterStyles() {
        btnBatchFilterAll.setSclass(        "ALL".equals(batchFilter)        ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
        btnBatchFilterVerified.setSclass(   "VERIFIED".equals(batchFilter)   ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
        btnBatchFilterInProgress.setSclass( "INPROGRESS".equals(batchFilter) ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
    }

    private Row buildBatchRow(BatchModel b) {
        Row row = new Row();

        Label batchIdLbl = new Label(safe(b.getBatchId()));
        batchIdLbl.setSclass("v2-batch-no-cell");
        row.appendChild(cell(batchIdLbl, ""));

        row.appendChild(cell(new Label(String.valueOf(b.getTotalCheques())), "v2-center"));

        Label hvLbl = new Label(String.valueOf(parseHvCount(b)));
        hvLbl.setSclass("v2-hv-count");
        row.appendChild(cell(hvLbl, "v2-center"));

        row.appendChild(cell(new Label(String.valueOf(parsePendingCount(b))),   "v2-center"));
        row.appendChild(cell(new Label(String.valueOf(parseProcessedCount(b))), "v2-center"));
        row.appendChild(cell(new Label(safe(b.getBranchCode())), "v2-center"));

        int pendingCount = parsePendingCount(b);
        String derivedStatus = (pendingCount == 0) ? "VERIFIED" : "VERIFICATION IN PROGRESS";
        Label statusLbl = new Label(derivedStatus);
        statusLbl.setSclass((pendingCount == 0)
                ? "v2-status-badge v2-status-verified"
                : "v2-status-badge v2-status-inprogress");
        row.appendChild(cell(statusLbl, "v2-center"));

        row.appendChild(cell(new Label("——"), "v2-center")); // createdBy

        Button btn = new Button("Process");
        btn.setSclass("v2-process-btn");
        btn.addEventListener("onClick", e -> onBatchProcess(b));
        row.appendChild(cell(btn, "v2-center"));

        return row;
    }

    // ── SCREEN 2 — HV Cheque list ─────────────────────────────────────────────

    private void onBatchProcess(BatchModel batch) {
        currentBatchCheques = service.getHighValueChequesForBatch(batch.getBatchId());

        batchListView.setVisible(false);
        chequeListView.setVisible(true);
        activeBatchLabel.setValue(batch.getBatchId());

        refreshChequeListChips();

        // Reset cheque filter + page when opening a new batch
        chequeFilter = "ALL";
        chequePage   = 1;
        updateChequeFilterStyles();
        buildFilteredPagedChequeRows();
    }

    // ── Cheque filter listeners ───────────────────────────────────────────────

    @Listen("onClick = #btnFilterAll")
    public void onFilterAll() {
        chequeFilter = "ALL";
        chequePage   = 1;
        updateChequeFilterStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #btnFilterPending")
    public void onFilterPending() {
        chequeFilter = "PENDING";
        chequePage   = 1;
        updateChequeFilterStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #btnFilterAccepted")
    public void onFilterAccepted() {
        chequeFilter = "ACCEPTED";
        chequePage   = 1;
        updateChequeFilterStyles();
        buildFilteredPagedChequeRows();
    }

    @Listen("onClick = #btnFilterRejected")
    public void onFilterRejected() {
        chequeFilter = "REJECTED";
        chequePage   = 1;
        updateChequeFilterStyles();
        buildFilteredPagedChequeRows();
    }

    // ── Cheque pagination listeners ───────────────────────────────────────────

    @Listen("onClick = #btnPagePrev")
    public void onPagePrev() {
        if (chequePage > 1) {
            chequePage--;
            buildFilteredPagedChequeRows();
        }
    }

    @Listen("onClick = #btnPageNext")
    public void onPageNext() {
        int totalPages = getTotalPages(getFilteredChequeList().size(), CHEQUE_PAGE_SIZE);
        if (chequePage < totalPages) {
            chequePage++;
            buildFilteredPagedChequeRows();
        }
    }

    // ── Core: filter + page + rebuild cheque rows ─────────────────────────────

    private void buildFilteredPagedChequeRows() {
        if (currentBatchCheques == null) return;

        List<ChequeModel> filtered = getFilteredChequeList();

        chequeRows.getChildren().clear();

        if (filtered.isEmpty()) {
            chequeEmptyState.setVisible(true);
            updateChequePaginationBar(0);
            return;
        }
        chequeEmptyState.setVisible(false);

        int totalPages = getTotalPages(filtered.size(), CHEQUE_PAGE_SIZE);
        if (chequePage > totalPages) chequePage = totalPages;

        int start = (chequePage - 1) * CHEQUE_PAGE_SIZE;
        int end   = Math.min(start + CHEQUE_PAGE_SIZE, filtered.size());

        for (int i = start; i < end; i++) {
            chequeRows.appendChild(buildChequeRow(i + 1, filtered.get(i)));
        }

        updateChequePaginationBar(filtered.size());
    }

    /**
     * Returns a filtered copy of currentBatchCheques based on chequeFilter.
     * PENDING  → anything that is NOT Accepted or Rejected
     * ACCEPTED → ver_status equalsIgnoreCase "Accepted"
     * REJECTED → ver_status equalsIgnoreCase "Rejected"
     * ALL      → everything
     */
    private List<ChequeModel> getFilteredChequeList() {
        List<ChequeModel> result = new ArrayList<>();
        for (ChequeModel c : currentBatchCheques) {
            String vs = c.getVerStatus();
            if ("ACCEPTED".equals(chequeFilter)) {
                if ("Accepted".equalsIgnoreCase(vs)) result.add(c);
            } else if ("REJECTED".equals(chequeFilter)) {
                if ("Rejected".equalsIgnoreCase(vs)) result.add(c);
            } else if ("PENDING".equals(chequeFilter)) {
                if (!"Accepted".equalsIgnoreCase(vs) && !"Rejected".equalsIgnoreCase(vs)) result.add(c);
            } else {
                result.add(c);  // ALL
            }
        }
        return result;
    }

    private void updateChequePaginationBar(int filteredSize) {
        int totalPages = getTotalPages(filteredSize, CHEQUE_PAGE_SIZE);
        pageInfoLabel.setValue("Page " + chequePage + " of " + totalPages);
        btnPagePrev.setDisabled(chequePage <= 1);
        btnPageNext.setDisabled(chequePage >= totalPages);
    }

    private void updateChequeFilterStyles() {
        btnFilterAll.setSclass(     "ALL".equals(chequeFilter)      ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
        btnFilterPending.setSclass( "PENDING".equals(chequeFilter)  ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
        btnFilterAccepted.setSclass("ACCEPTED".equals(chequeFilter) ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
        btnFilterRejected.setSclass("REJECTED".equals(chequeFilter) ? "v2-filter-btn v2-filter-active" : "v2-filter-btn");
    }

    private void refreshChequeListChips() {
        long pending = 0, passed = 0, rejected = 0;
        for (ChequeModel c : currentBatchCheques) {
            String vs = c.getVerStatus();
            if ("Accepted".equalsIgnoreCase(vs))       passed++;
            else if ("Rejected".equalsIgnoreCase(vs))  rejected++;
            else                                       pending++;
        }
        hvPendingChip.setValue(pending  + " Pending");
        hvPassedChip.setValue(passed    + " Passed");
        hvRejectedChip.setValue(rejected + " Rejected");

        int  total    = currentBatchCheques.size();
        long verified = passed + rejected;
        progressLabel.setValue("Batch Progress  " + verified + " / " + total + " verified");
        int pct = total == 0 ? 0 : (int) (verified * 100.0 / total);
        progressFill.setStyle("width:" + pct + "%");
    }

    private Row buildChequeRow(int sno, ChequeModel c) {
        Row row = new Row();

        row.appendChild(cell(new Label(String.valueOf(sno)), "v2-center"));
        row.appendChild(cell(new Label(safe(c.getChequeNo())), ""));
        row.appendChild(cell(new Label(safe(c.getAccountNo())), ""));
        row.appendChild(cell(new Label(formatAmount(c.getAmount())), "v2-right"));
        row.appendChild(cell(new Label(safe(c.getChequeDate())), "v2-center"));

        row.appendChild(cell(buildFlagLabel(c.getIqaStatus()), "v2-center"));

        String vs = (c.getVerStatus() != null) ? c.getVerStatus() : "PENDING";
        Label vsLbl = new Label(vs);
        vsLbl.setSclass("v2-status-badge v2-status-" + safeStatus(vs));
        row.appendChild(cell(vsLbl, "v2-center"));

        // Open button uses index in the FULL list so popup nav works correctly
        final int fullIdx = currentBatchCheques.indexOf(c);
        Button openBtn = new Button("Open");
        openBtn.setSclass("v2-open-btn");
        openBtn.addEventListener("onClick", e -> openChequePopup(fullIdx));
        row.appendChild(cell(openBtn, "v2-center"));

        return row;
    }

    private Label buildFlagLabel(String iqaStatus) {
        boolean isReferred = iqaStatus != null
                && iqaStatus.startsWith("VACTION:")
                && "REFERRED".equalsIgnoreCase(iqaStatus.substring("VACTION:".length()));
        if (isReferred) {
            Label lbl = new Label("⇄ REF");
            lbl.setSclass("v2-ref-flag");
            return lbl;
        }
        Label lbl = new Label("⚑ HV");
        lbl.setSclass("v2-hv-flag");
        return lbl;
    }

    // ── SCREEN 3 — Popup ──────────────────────────────────────────────────────

    private void openChequePopup(int index) {
        popupIndex = index;
        renderPopup(popupIndex);
        chequeDetailPopup.setVisible(true);
        chequeDetailPopup.doOverlapped();
        chequeDetailPopup.setLeft("calc(50vw - 460px)");
        chequeDetailPopup.setTop("5vh");
    }

    private void renderPopup(int index) {
        if (currentBatchCheques == null || currentBatchCheques.isEmpty()) return;

        ChequeModel c = currentBatchCheques.get(index);

        popupTitle.setValue("Cheque  #" + safe(c.getChequeNo()));
        fChequeNo.setValue(safe(c.getChequeNo()));
        fSortCode.setValue(safe(c.getSortCode()));
        fTransactionCode.setValue(safe(c.getTransactionCode()));
        fAccountNo.setValue(safe(c.getAccountNo()));
        fChequeDate.setValue(safe(c.getChequeDate()));
        fAmount.setValue(formatAmount(c.getAmount()));
        fAmountInWords.setValue("——");
        fPayeeName.setValue(safe(c.getAccountNo()));
        fDrawerName.setValue("——");
        fVerRemarks.setValue("");

        String currentVs = c.getVerStatus();
        boolean alreadyVerified = "Accepted".equalsIgnoreCase(currentVs)
                               || "Rejected".equalsIgnoreCase(currentVs);
        btnAccept.setDisabled(alreadyVerified);
        btnReject.setDisabled(alreadyVerified);

        byte[] frontBytes = c.getFrontImageBytes();
        if (frontBytes != null && frontBytes.length > 0) {
            frontChequeImage.setSrc("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(frontBytes));
            frontChequeImage.setVisible(true);
            frontImagePlaceholder.setVisible(false);
        } else {
            frontChequeImage.setVisible(false);
            frontImagePlaceholder.setVisible(true);
        }

        byte[] rearBytes = c.getRearImageBytes();
        if (rearBytes != null && rearBytes.length > 0) {
            rearChequeImage.setSrc("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(rearBytes));
            rearChequeImage.setVisible(true);
            rearImagePlaceholder.setVisible(false);
        } else {
            rearChequeImage.setVisible(false);
            rearImagePlaceholder.setVisible(true);
        }

        int total = currentBatchCheques.size();
        popupCounter.setValue((index + 1) + " / " + total);
        btnPopupPrev.setDisabled(index == 0);
        btnPopupNext.setDisabled(index == total - 1);
    }

    // ── Verification action ───────────────────────────────────────────────────

    private void performVerification(String action) {
        if (currentBatchCheques == null || currentBatchCheques.isEmpty()) return;

        ChequeModel c = currentBatchCheques.get(popupIndex);
        long chequeId = Long.parseLong(c.getId());

        String verBy = (String) Sessions.getCurrent()
                .getAttribute(com.cts.composer.LoginComposer.SESS_USER_NAME);
        if (verBy == null || verBy.isBlank()) verBy = "Unknown";

        service.verifyHighValueCheque(chequeId, action, verBy, fVerRemarks.getValue());

        String newStatus = "ACCEPTED".equals(action) ? "Accepted" : "Rejected";
        c.setVerStatus(newStatus);
        c.setStatus(newStatus);

        refreshChequeListChips();
        buildFilteredPagedChequeRows();
        renderPopup(popupIndex);
    }

    // ── Event listeners ───────────────────────────────────────────────────────

    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        chequeListView.setVisible(false);
        batchListView.setVisible(true);
        currentBatchCheques = null;
        chequeFilter = "ALL";
        chequePage   = 1;
        // Reload from DB so batch STATUS reflects latest state
        loadHighValueBatches();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** Generic total-pages calculation used by both batch and cheque paginators. */
    private int getTotalPages(int listSize, int pageSize) {
        if (listSize == 0) return 1;
        return (int) Math.ceil((double) listSize / pageSize);
    }

    private int parseHvCount(BatchModel b)        { return parsePart(b.getPresentingBankId(), 0); }
    private int parsePendingCount(BatchModel b)   { return parsePart(b.getPresentingBankId(), 1); }
    private int parseProcessedCount(BatchModel b) { return parsePart(b.getPresentingBankId(), 2); }

    private int parsePart(String encoded, int index) {
        if (encoded == null || encoded.isEmpty()) return 0;
        try {
            String[] parts = encoded.split("\\|");
            return parts.length > index ? Integer.parseInt(parts[index]) : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    private Cell cell(Component child, String sclass) {
        Cell c = new Cell();
        if (sclass != null && !sclass.isEmpty()) c.setSclass(sclass);
        c.appendChild(child);
        return c;
    }

    private String safe(String val) {
        return (val == null || val.isBlank()) ? "——" : val;
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
}