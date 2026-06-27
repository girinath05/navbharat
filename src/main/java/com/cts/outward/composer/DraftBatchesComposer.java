package com.cts.outward.composer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.zkoss.zul.Textbox;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Timer;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;
import com.cts.outward.service.ChequeService;
import com.cts.outward.service.ChequeServiceImpl;
import com.cts.outward.service.ImportResult;
import com.cts.outward.service.ZipImportService;
import com.cts.outward.service.ZipImportServiceImpl;

/**
 * ZK SelectorComposer for {@code draft-batches.zul}.
 *
 * <p>Owns the full Create Batch + ZIP scan workflow (Steps 1 and 2), previously
 * in {@code ChequeScanComposer}. Shows <b>Draft-status batches only</b>.
 *
 * <h3>What changed vs ChequeScanComposer</h3>
 * <ul>
 *   <li>Filter: Draft only — VerificationInProgressAtMaker excluded.</li>
 *   <li>No stat cards (lblStatBatches / lblStatCheques / lblStatPending removed).</li>
 *   <li>No btnViewBatches / btnLogout / header user labels.</li>
 *   <li>Back-nav from batch-detail returns to draft-batches.zul.</li>
 *   <li>updateBatchCountLabel() — no JS bce_updateBatchLabel call.</li>
 * </ul>
 *
 * <h3>Flow</h3>
 * <pre>
 *   draft-batches.zul → DraftBatchesComposer
 *       ├── BatchService / BatchServiceImpl   → cts_batches
 *       ├── ChequeService / ChequeServiceImpl → cts_cheques
 *       └── ZipImportService / ZipImportServiceImpl → ZIP parse → dedup → persist
 * </pre>
 *
 * @author Umesh M.
 */
public class DraftBatchesComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final int BATCH_PAGE_SIZE = 5;
    private static final Logger LOG = Logger.getLogger(DraftBatchesComposer.class.getName());

    private static final String SESS_USER_NAME   = "userName";
    private static final String SESS_USER_BRANCH = "userBranch";

    // ── Services ──────────────────────────────────────────────────────
    private final BatchService     batchService     = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
    private final ChequeService    chequeService    = new ChequeServiceImpl(new ChequeDAOImpl());
    private final ZipImportService zipImportService = new ZipImportServiceImpl();

    // ── In-memory state ───────────────────────────────────────────────
    /** Draft batches loaded from DB. Filtered in-memory by search/date. */
    private final List<BatchEntity> batches = new ArrayList<>();
    private int batchPage = 1;
    /** Bridge from Step 1 (create) to Step 2 (scan). Cleared on success/discard. */
    private String pendingBatchId = null;
    /** Stashed mismatch result pending Maker Accept/Discard decision. */
    private ImportResult pendingMismatchResult = null;

    // ── Wired: batch table + toolbar ──────────────────────────────────
    @Wire private Button  btnOpenBatchModal;
    @Wire private Button  btnCloseBatchModal;
    @Wire private Button  btnCancelBatchModal;
    @Wire private Div     batchModal;
    @Wire private Button  btnCloseScanModal;
    @Wire private Button  btnScanCancelDiscard;

    @Wire private Div     scanModal;
    @Wire private Label   scanBatchIdLabel;
    @Wire private Div     scanProgress;
    @Wire private Div     scanProgressFill;
    @Wire private Label   scanProgressText;

    @Wire private Div     mismatchDialog;
    @Wire private Label   lblMdExpectedCount;
    @Wire private Label   lblMdControlAmt;
    @Wire private Label   lblMdParsedCount;
    @Wire private Label   lblMdParsedAmt;
    @Wire private Div     mdDupRow;
    @Wire private Label   lblMdSkippedCount;
    @Wire private Label   lblMdSavedCount;
    @Wire private Label   lblMdFooterAmt;
    @Wire private Button  btnMismatchAccept;
    @Wire private Button  btnMismatchDiscard;

    @Wire private Div     duplicateDialog;
    @Wire private Label   lblDupCount;
    @Wire private Label   lblDupAmt;
    @Wire private Label   lblDupFlag;
    @Wire private Button  btnDuplicateOk;

    @Wire private Div     batchSuccessToast;
    @Wire private Label   lblToastBatchId;
    @Wire private Label   lblToastChequeCount;
    @Wire private Label   lblToastAmount;
    @Wire private Button  btnToastDismiss;
    @Wire private Button  btnToastClose;
    @Wire private Timer   toastTimer;

    @Wire private Label   batchCountLabel;
    @Wire private Listbox lbBatches;
    @Wire private Textbox txtBatchSearch;
    @Wire private Datebox dtBatchFrom;
    @Wire private Datebox dtBatchTo;
    @Wire private Button  btnBatchClearDate;
    @Wire private Button  btnBatchPgFirst;
    @Wire private Button  btnBatchPgPrev;
    @Wire private Label   lblBatchPgInfo;
    @Wire private Button  btnBatchPgNext;
    @Wire private Button  btnBatchPgLast;

    @Wire private Button  btnCreateBatch;   // Submit Step 1 modal
    @Wire private Intbox  txtChequeCount;
    @Wire private Decimalbox txtExpectedAmount;
    @Wire private Label   errChequeCount;
    @Wire private Label   errExpectedAmount;

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        if (!com.cts.util.SecurityUtil.isLoggedIn()) {
            Executions.sendRedirect("/zul/login.zul");
            return;
        }
        loadBatchesFromService();
        renderBatches();
        updateBatchCountLabel();

        // Auto-open modal if navigated here via "Create Batch" from another page
        Object flag = Sessions.getCurrent().getAttribute("autoOpenBatchModal");
        if (Boolean.TRUE.equals(flag)) {
            Sessions.getCurrent().removeAttribute("autoOpenBatchModal");
            openBatchModal();
        }
    }

    // ── Data load ─────────────────────────────────────────────────────

    private void loadBatchesFromService() {
        batches.clear();
        try {
            batches.addAll(batchService.getAllBatches());
        } catch (Exception ex) {
            Clients.showNotification("⚠ Could not load batches: " + ex.getMessage(),
                    "warning", null, "middle_center", 4000);
        }
    }

    // ── Create Batch modal listeners ──────────────────────────────────

    @Listen("onClick = #btnOpenBatchModal")
    public void onOpenBatchModal() { openBatchModal(); }

    @Listen("onClick = #btnCloseBatchModal")
    public void onCloseBatchModal() { closeBatchModal(); }

    @Listen("onClick = #btnCancelBatchModal")
    public void onCancelBatchModal() { closeBatchModal(); }

    @Listen("onChange = #txtChequeCount")
    public void onChequeCountChange() {
        if (errChequeCount  != null) errChequeCount.setValue("");
        if (txtChequeCount  != null) txtChequeCount.setSclass("mf-input");
    }

    @Listen("onChange = #txtExpectedAmount")
    public void onExpectedAmountChange() {
        if (errExpectedAmount != null) errExpectedAmount.setValue("");
        if (txtExpectedAmount != null) txtExpectedAmount.setSclass("mf-input");
    }

    /**
     * Step 1: validate inputs → create Draft batch → open Scan Modal (Step 2).
     */
    @Listen("onClick = #btnCreateBatch")
    public void onCreateBatch() {
        if (btnCreateBatch != null) btnCreateBatch.setDisabled(true);

        if (errChequeCount   != null) errChequeCount.setValue("");
        if (errExpectedAmount!= null) errExpectedAmount.setValue("");
        if (txtChequeCount   != null) txtChequeCount.setSclass("mf-input");
        if (txtExpectedAmount!= null) txtExpectedAmount.setSclass("mf-input");

        Integer rawCount  = (txtChequeCount   != null) ? txtChequeCount.getValue()   : null;
        BigDecimal rawAmt = (txtExpectedAmount != null) ? txtExpectedAmount.getValue(): null;
        int chequeCount   = (rawCount  != null) ? rawCount  : 0;
        BigDecimal ctrlAmt = (rawAmt != null) ? rawAmt : BigDecimal.ZERO;

        boolean err = false;
        if (rawCount == null || chequeCount < 1) {
            err = true;
            if (errChequeCount  != null) errChequeCount.setValue(rawCount == null ? "Cheque count is required." : "Must be at least 1.");
            if (txtChequeCount  != null) txtChequeCount.setSclass("mf-input mf-input-error");
        }
        if (rawAmt == null || ctrlAmt.compareTo(BigDecimal.ZERO) <= 0) {
            err = true;
            if (errExpectedAmount != null) errExpectedAmount.setValue(rawAmt == null ? "Control amount is required." : "Must be greater than 0.");
            if (txtExpectedAmount != null) txtExpectedAmount.setSclass("mf-input mf-input-error");
        }
        if (err) {
            if (btnCreateBatch != null) btnCreateBatch.setDisabled(false);
            return;
        }

        closeBatchModal();
        try {
            String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
            String createdBy  = sessionStr(SESS_USER_NAME,   "SYSTEM");
            BatchEntity newBatch = batchService.createBatch(branchCode, chequeCount, ctrlAmt, createdBy);
            pendingBatchId = newBatch.getBatchId();
            batches.add(0, newBatch);
            renderBatches();
            updateBatchCountLabel();
            if (btnCreateBatch != null) btnCreateBatch.setDisabled(false);
            openScanModal(newBatch.getBatchId());
        } catch (Exception ex) {
            if (btnCreateBatch != null) btnCreateBatch.setDisabled(false);
            Clients.showNotification("❌ Could not create batch: " + ex.getMessage(),
                    "error", null, "middle_center", 5000);
        }
    }

    // ── Scan modal listeners ──────────────────────────────────────────

    @Listen("onClick = #btnCloseScanModal")
    public void onCloseScanModal() {
        closeScanModal();
        discardPendingBatch();
    }

    @Listen("onClick = #btnScanCancelDiscard")
    public void onScanCancelDiscard() { discardPendingBatch(); }

    /**
     * Step 2: handle ZIP upload → 3 outcomes: all-dups / mismatch / clean import.
     */
    @Listen("onUpload = #btnScanUploadZip")
    public void onScanZipUpload(UploadEvent event) {
        Media m = event.getMedia();
        if (m == null) { Clients.showNotification("No file received.", "error", null, "middle_center", 3000); return; }
        if (!m.getName().toLowerCase().endsWith(".zip")) { Clients.showNotification("Please upload a .zip file.", "warning", null, "middle_center", 3000); return; }

        showScanProgress("Processing ZIP — please wait…");
        Clients.showBusy("Processing ZIP…");
        try {
            byte[] zipBytes   = readAllBytes(m.getStreamData());
            String branchCode = sessionStr(SESS_USER_BRANCH, "MUM01");
            String createdBy  = sessionStr(SESS_USER_NAME,   "SYSTEM");

            ImportResult result = zipImportService.importZip(zipBytes, m.getName(), branchCode, createdBy, pendingBatchId);
            Clients.clearBusy();

            // Case 1: all duplicates
            if (result.isAllDuplicates()) {
                hideScanProgress(); closeScanModal(); discardPendingBatch();
                openDuplicateDialog(result.getParsedTotal(), result.getParsedTotalAmount());
                return;
            }

            // Case 2: count mismatch
            BatchEntity pendingEntity = batches.stream()
                    .filter(b -> b.getBatchId().equals(pendingBatchId)).findFirst().orElse(null);
            int expected    = (pendingEntity != null) ? pendingEntity.getExpectedCheques() : 0;
            BigDecimal ctrl = (pendingEntity != null && pendingEntity.getExpectedAmount() != null)
                    ? pendingEntity.getExpectedAmount() : BigDecimal.ZERO;
            int saved  = result.getCheques().size();
            int parsed = result.getParsedTotal();

            if (expected > 0 && saved != expected) {
                pendingMismatchResult = result;
                hideScanProgress(); closeScanModal();
                openMismatchDialog(expected, ctrl, parsed, result.getParsedTotalAmount(), saved, result.getSkippedDuplicates());
                return;
            }

            // Case 3: clean import
            hideScanProgress(); closeScanModal();
            finishSuccessfulImport(result);

        } catch (Exception ex) {
            Clients.clearBusy(); hideScanProgress(); closeScanModal();
            LOG.severe("onScanZipUpload error: " + ex.getMessage());
            Clients.showNotification("❌ Upload failed: " + ex.getMessage(), "error", null, "middle_center", 6000);
            pendingBatchId = null;
        }
    }

    // ── Mismatch dialog listeners ─────────────────────────────────────

    @Listen("onClick = #btnMismatchAccept")
    public void onMismatchAccept() {
        closeMismatchDialog();
        if (pendingMismatchResult != null) finishSuccessfulImport(pendingMismatchResult);
        pendingMismatchResult = null;
    }

    @Listen("onClick = #btnMismatchDiscard")
    public void onMismatchDiscard() {
        closeMismatchDialog();
        String bid = pendingBatchId;
        pendingMismatchResult = null;
        pendingBatchId = null;
        if (bid != null) {
            try {
                batchService.discardBatch(bid);
                batches.removeIf(b -> b.getBatchId().equals(bid));
                renderBatches(); updateBatchCountLabel();
                Clients.showNotification("Batch " + bid + " discarded.", "warning", null, "middle_center", 3000);
            } catch (Exception ex) {
                Clients.showNotification("❌ Discard failed: " + ex.getMessage(), "error", null, "middle_center", 5000);
            }
        }
    }

    // ── Duplicate dialog ──────────────────────────────────────────────

    @Listen("onClick = #btnDuplicateOk")
    public void onDuplicateOk() { closeDuplicateDialog(); }

    // ── Toast listeners ───────────────────────────────────────────────

    @Listen("onClick = #btnToastDismiss")
    public void onToastDismiss() { closeSuccessToast(); }

    @Listen("onClick = #btnToastClose")
    public void onToastClose() { closeSuccessToast(); }

    @Listen("onTimer = #toastTimer")
    public void onToastTimer() { closeSuccessToast(); }

    // ── Filter / pagination listeners ─────────────────────────────────

    @Listen("onChange = #txtBatchSearch; onChanging = #txtBatchSearch")
    public void onBatchSearch() { batchPage = 1; renderBatches(); }

    @Listen("onChange = #dtBatchFrom; onChange = #dtBatchTo")
    public void onBatchDateFilter() { batchPage = 1; renderBatches(); }

    @Listen("onClick = #btnBatchClearDate")
    public void onBatchClearDate() {
        if (dtBatchFrom != null) dtBatchFrom.setValue(null);
        if (dtBatchTo   != null) dtBatchTo.setValue(null);
        batchPage = 1; renderBatches();
    }

    @Listen("onClick = #btnBatchPgFirst")
    public void onBatchPgFirst() { batchPage = 1; renderBatches(); }

    @Listen("onClick = #btnBatchPgPrev")
    public void onBatchPgPrev() { if (batchPage > 1) { batchPage--; renderBatches(); } }

    @Listen("onClick = #btnBatchPgNext")
    public void onBatchPgNext() { batchPage++; renderBatches(); }

    @Listen("onClick = #btnBatchPgLast")
    public void onBatchPgLast() {
        int total = Math.max(1, (int) Math.ceil((double) getFilteredBatches().size() / BATCH_PAGE_SIZE));
        batchPage = total; renderBatches();
    }

    // ── Modal open/close helpers ──────────────────────────────────────

    private void openBatchModal() {
        if (txtChequeCount    != null) { txtChequeCount.setValue((Integer) null);   txtChequeCount.setSclass("mf-input"); }
        if (txtExpectedAmount != null) { txtExpectedAmount.setValue((BigDecimal) null); txtExpectedAmount.setSclass("mf-input"); }
        if (errChequeCount    != null) errChequeCount.setValue("");
        if (errExpectedAmount != null) errExpectedAmount.setValue("");
        if (batchModal        != null) batchModal.setVisible(true);
    }
    private void closeBatchModal()    { if (batchModal      != null) batchModal.setVisible(false); }

    private void openScanModal(String batchId) {
        if (scanBatchIdLabel != null) scanBatchIdLabel.setValue(batchId != null ? batchId : "—");
        hideScanProgress();
        if (scanModal != null) scanModal.setVisible(true);
    }
    private void closeScanModal() {
        if (scanModal != null) scanModal.setVisible(false);
        hideScanProgress();
    }

    private void showScanProgress(String msg) {
        if (scanProgress     != null) scanProgress.setVisible(true);
        if (scanProgressText != null) scanProgressText.setValue(msg != null ? msg : "Scanning…");
        if (scanProgressFill != null) scanProgressFill.setStyle("width:90%;");
    }
    private void hideScanProgress() {
        if (scanProgress     != null) scanProgress.setVisible(false);
        if (scanProgressFill != null) scanProgressFill.setStyle("width:0%;");
    }

    private void openMismatchDialog(int exp, BigDecimal ctrl, int parsed,
            BigDecimal parsedAmt, int saved, int skipped) {
        if (lblMdExpectedCount != null) lblMdExpectedCount.setValue(exp + " cheque" + (exp != 1 ? "s" : ""));
        if (lblMdControlAmt    != null) lblMdControlAmt.setValue("₹" + formatAmtRaw(ctrl));
        if (lblMdParsedCount   != null) lblMdParsedCount.setValue(parsed + " cheque" + (parsed != 1 ? "s" : ""));
        if (lblMdParsedAmt     != null) lblMdParsedAmt.setValue("₹" + formatAmtRaw(parsedAmt));
        if (lblMdSavedCount    != null) lblMdSavedCount.setValue(saved + " cheque" + (saved != 1 ? "s" : ""));
        if (lblMdFooterAmt     != null) lblMdFooterAmt.setValue("₹" + formatAmtRaw(parsedAmt));
        if (skipped > 0) {
            if (lblMdSkippedCount != null) lblMdSkippedCount.setValue(skipped + " cheque" + (skipped != 1 ? "s" : ""));
            if (mdDupRow != null) mdDupRow.setVisible(true);
        } else if (mdDupRow != null) mdDupRow.setVisible(false);
        if (mismatchDialog != null) mismatchDialog.setVisible(true);
    }
    private void closeMismatchDialog() { if (mismatchDialog != null) mismatchDialog.setVisible(false); }

    private void openDuplicateDialog(int count, BigDecimal amt) {
        if (lblDupCount != null) lblDupCount.setValue(count + " cheque" + (count != 1 ? "s" : ""));
        if (lblDupAmt   != null) lblDupAmt.setValue("₹" + formatAmtRaw(amt));
        if (lblDupFlag  != null) lblDupFlag.setValue("All " + count + " cheque" + (count != 1 ? "s are" : " is") + " already registered in the system");
        if (duplicateDialog != null) duplicateDialog.setVisible(true);
    }
    private void closeDuplicateDialog() { if (duplicateDialog != null) duplicateDialog.setVisible(false); }

    private void showSuccessToast(String batchId, int chequeCount, String amountStr) {
        if (scanModal       != null) scanModal.setVisible(false);
        if (batchModal      != null) batchModal.setVisible(false);
        if (mismatchDialog  != null) mismatchDialog.setVisible(false);
        if (duplicateDialog != null) duplicateDialog.setVisible(false);
        if (lblToastBatchId    != null) lblToastBatchId.setValue(nullSafe(batchId, "—"));
        if (lblToastChequeCount!= null) lblToastChequeCount.setValue(chequeCount + " cheque" + (chequeCount != 1 ? "s" : ""));
        if (lblToastAmount     != null) lblToastAmount.setValue("₹" + amountStr);
        Clients.showNotification("✅ Batch " + nullSafe(batchId, "—") + " created — " + chequeCount
                + " cheque" + (chequeCount != 1 ? "s" : "") + " · ₹" + amountStr, "info", null, "top_center", 4000);
        if (batchSuccessToast != null) batchSuccessToast.setVisible(true);
        if (toastTimer != null) { toastTimer.setRunning(false); toastTimer.setRunning(true); }
    }
    private void closeSuccessToast() {
        if (toastTimer        != null) toastTimer.setRunning(false);
        if (batchSuccessToast != null) batchSuccessToast.setVisible(false);
    }

    // ── Finish import ─────────────────────────────────────────────────

    private void finishSuccessfulImport(ImportResult result) {
        loadBatchesFromService();
        renderBatches();
        updateBatchCountLabel();
        pendingBatchId = null;
        String amt = result.getBatch().getTotalAmount() != null
                ? String.format("%,.2f", result.getBatch().getTotalAmount()) : "0.00";
        showSuccessToast(result.getBatch().getBatchId(), result.getCheques().size(), amt);
    }

    // ── Discard pending batch ─────────────────────────────────────────

    private void discardPendingBatch() {
        if (pendingBatchId == null) return;
        String bid = pendingBatchId;
        pendingBatchId = null;
        try {
            batchService.discardBatch(bid);
            batches.removeIf(b -> b.getBatchId().equals(bid));
            renderBatches(); updateBatchCountLabel();
            LOG.info("Discarded empty batch: " + bid);
        } catch (Exception ex) {
            LOG.warning("discardPendingBatch: " + ex.getMessage());
        }
    }

    // ── Filter (Draft only) ───────────────────────────────────────────

    private List<BatchEntity> getFilteredBatches() {
        // Gate: Draft status only
        List<BatchEntity> draftBatches = batches.stream()
                .filter(b -> b.getStatus() == null || "Draft".equalsIgnoreCase(b.getStatus()))
                .collect(java.util.stream.Collectors.toList());

        // Text search on batchId / branchCode
        String q = (txtBatchSearch != null && txtBatchSearch.getValue() != null)
                ? txtBatchSearch.getValue().trim().toLowerCase() : "";
        if (!q.isEmpty()) {
            draftBatches = draftBatches.stream()
                    .filter(b -> (b.getBatchId() != null && b.getBatchId().toLowerCase().contains(q))
                            || (b.getBranchCode() != null && b.getBranchCode().toLowerCase().contains(q)))
                    .collect(java.util.stream.Collectors.toList());
        }

        // Date range filter
        java.util.Date from = (dtBatchFrom != null) ? dtBatchFrom.getValue() : null;
        java.util.Date to   = (dtBatchTo   != null) ? dtBatchTo.getValue()   : null;
        if (from != null || to != null) {
            draftBatches = draftBatches.stream().filter(b -> {
                java.util.Date d = parseBatchDate(b.getCreatedAt());
                if (d == null) return true;
                if (from != null && d.before(from)) return false;
                if (to   != null && d.after(to))    return false;
                return true;
            }).collect(java.util.stream.Collectors.toList());
        }
        return draftBatches;
    }

    private java.util.Date parseBatchDate(java.time.LocalDateTime ldt) {
        if (ldt == null) return null;
        return java.util.Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    // ── Render ────────────────────────────────────────────────────────

    private void renderBatches() {
        if (lbBatches == null) return;
        lbBatches.getItems().clear();

        List<BatchEntity> filtered = getFilteredBatches();
        int total      = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / BATCH_PAGE_SIZE));
        batchPage      = Math.max(1, Math.min(batchPage, totalPages));

        int start = (batchPage - 1) * BATCH_PAGE_SIZE;
        int end   = Math.min(start + BATCH_PAGE_SIZE, total);
        List<BatchEntity> page = (start < total) ? filtered.subList(start, end) : new ArrayList<>();

        updateBatchCountLabel(total);
        updateBatchPagination(totalPages);

        if (page.isEmpty()) {
            Listitem row = new Listitem();
            Listcell cell = new Listcell();
            cell.setClientAttribute("colspan", "6");
            Label lbl = new Label(total == 0 ? "No draft batches found." : "No batches match the current filter.");
            lbl.setStyle("display:block;text-align:center;color:#94a3b8;padding:32px;font-size:13px;");
            cell.appendChild(lbl); row.appendChild(cell); lbBatches.appendChild(row);
            return;
        }
        for (BatchEntity b : page) appendBatchRow(b);
    }

    private void updateBatchPagination(int totalPages) {
        if (lblBatchPgInfo  != null) lblBatchPgInfo.setValue("Page " + batchPage + " of " + totalPages);
        if (btnBatchPgFirst != null) btnBatchPgFirst.setDisabled(batchPage <= 1);
        if (btnBatchPgPrev  != null) btnBatchPgPrev.setDisabled(batchPage <= 1);
        if (btnBatchPgNext  != null) btnBatchPgNext.setDisabled(batchPage >= totalPages);
        if (btnBatchPgLast  != null) btnBatchPgLast.setDisabled(batchPage >= totalPages);
    }

    private void appendBatchRow(BatchEntity batch) {
        Listitem row = new Listitem();
        row.setValue(batch); row.setSclass("mb-row");

        Listcell idCell = new Listcell();
        Label idLbl = new Label(nullSafe(batch.getBatchId(), "—"));
        idLbl.setSclass("mb-link"); idCell.appendChild(idLbl); row.appendChild(idCell);

        row.appendChild(new Listcell(String.valueOf(batch.getTotalCheques())));

        Listcell amtCell = new Listcell();
        amtCell.setSclass("amt-cell"); amtCell.appendChild(new Label(formatAmt(batch.getTotalAmount())));
        row.appendChild(amtCell);

        row.appendChild(new Listcell(formatBatchDate(batch.getCreatedAt())));

        Listcell stCell = new Listcell();
        Label stLbl = new Label("Draft"); stLbl.setSclass("chip ch-amber");
        stCell.appendChild(stLbl); row.appendChild(stCell);

        Listcell actCell = new Listcell();
        Label actLbl = new Label("View & Edit"); actLbl.setSclass("mb-action-link");
        actCell.appendChild(actLbl); row.appendChild(actCell);

        row.addEventListener("onClick", e -> navigateToBatchDetail(batch));
        lbBatches.appendChild(row);
    }

    private void navigateToBatchDetail(BatchEntity batch) {
        Sessions.getCurrent().setAttribute("selectedBatchId", batch.getBatchId());
        Sessions.getCurrent().setAttribute("batchDetailBackPage", "/zul/outward/draft-batches.zul");
        com.cts.composer.DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
    }

    private void updateBatchCountLabel(int count) {
        if (batchCountLabel != null) batchCountLabel.setValue(count + " batch" + (count == 1 ? "" : "es"));
    }
    private void updateBatchCountLabel() {
        long count = batches.stream()
                .filter(b -> b.getStatus() == null || "Draft".equalsIgnoreCase(b.getStatus()))
                .count();
        updateBatchCountLabel((int) count);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private List<ChequeEntity> loadChequesForBatch(String batchId) {
        try { return chequeService.getChequesForBatch(batchId); }
        catch (Exception ex) { return new ArrayList<>(); }
    }

    private String sessionStr(String key, String def) {
        Object v = Sessions.getCurrent().getAttribute(key);
        return v != null ? v.toString() : def;
    }
    private String nullSafe(String v, String fb) { return (v != null && !v.isBlank()) ? v : fb; }

    private String formatAmt(BigDecimal a) {
        if (a == null || a.compareTo(BigDecimal.ZERO) == 0) return "₹0.00";
        double v = a.doubleValue();
        if (v >= 1_00_00_000) return String.format("₹%.2f Cr", v / 1_00_00_000.0);
        if (v >= 1_00_000)    return String.format("₹%.2f L",  v / 1_00_000.0);
        return String.format("₹%,.2f", v);
    }
    private String formatAmtRaw(BigDecimal a) {
        if (a == null || a.compareTo(BigDecimal.ZERO) == 0) return "0.00";
        return String.format("%,.2f", a);
    }
    private String formatBatchDate(java.time.LocalDateTime ldt) {
        if (ldt == null) return "—";
        return String.format("%02d/%02d/%04d", ldt.getDayOfMonth(), ldt.getMonthValue(), ldt.getYear());
    }
    private byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[8192]; int n;
        while ((n = in.read(block)) != -1) buf.write(block, 0, n);
        return buf.toByteArray();
    }
}