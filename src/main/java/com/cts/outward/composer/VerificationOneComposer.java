/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : VerificationOneComposer.java
 *  Package     : com.cts.outward.composer
 *  Author      : Anusha M.
 *  Created     : June 2026
 *  Description : ZK SelectorComposer for Verification I (Checker).
 * ============================================================
 */

package com.cts.outward.composer;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Image;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.CBSDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;
import com.cts.outward.service.CBSService;
import com.cts.outward.service.CBSServiceImpl;
import com.cts.outward.service.ChequeService;
import com.cts.outward.service.ChequeServiceImpl;

public class VerificationOneComposer extends SelectorComposer<Component> {

    private static final long   serialVersionUID = 1L;
    private static final String IMG_SERVLET  = "/chequeImage";
    private static final String DEFAULT_USER = "SYSTEM";
    private static final Logger LOG = Logger.getLogger(VerificationOneComposer.class.getName());

    private final BatchService  batchService  = new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());
    private final ChequeService chequeService = new ChequeServiceImpl(new ChequeDAOImpl());
    private final CBSService    cbsService    = new CBSServiceImpl(new CBSDAOImpl());

    // Phase 1
    @Wire private Div     pnlBatchList;
    @Wire private Listbox lbBatches;

    // Phase 2
    @Wire private Div     pnlChequeList;
    @Wire private Listbox lbCheques;
    @Wire private Label   spCntPending;
    @Wire private Label   spCntPassed;
    @Wire private Label   spCntRejected;
    @Wire private Label   lblBatchTitle;

    // Popup
    @Wire private Div      dlgChequeVerify;
    @Wire private Div      dlgBackdrop;
    @Wire private Label    dlgBatchPill;
    @Wire private Label    dlgRecordPos;
    @Wire private Div      dlgImageBox;
    @Wire private Image    dlgImage;
    @Wire private Label    dlgImagePh;

    // MICR fields
    @Wire private Label    dlgChequeNo;
    @Wire private Label    dlgCityCode;
    @Wire private Label    dlgBankCode;
    @Wire private Label    dlgBranchCode;

    // Cheque info fields
    @Wire private Label    dlgPayeeName;
    @Wire private Label    dlgCbsAccName;
    @Wire private Label    dlgCbsPayeeMatch;
    @Wire private Label    dlgAccountNo;
    @Wire private Label    dlgChequeDate;
    @Wire private Label    dlgCbsAccStatus;
    @Wire private Label    dlgCbsNewAcc;
    @Wire private Label    dlgAmount;
    @Wire private Label    dlgAmountWords;

    // Footer
    @Wire private Button   btnDlgPrev;
    @Wire private Button   btnDlgNext;
    @Wire private Button   btnDlgAccept;
    @Wire private Combobox cmbRejectReason;
    @Wire private Button   btnDlgReject;
    @Wire private Combobox cmbReferReason;
    @Wire private Button   btnDlgRefer;
    @Wire private Button   btnToggleSide;

    // State
    private String             currentUser;
    private String             openBatchId;
    private List<ChequeEntity> chequeList;
    private int                dlgIndex;
    private boolean            showingRear = false;

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        Session zs = Sessions.getCurrent();
        // FIX: LoginComposer stores key "loggedUser" — was "loggedInUser" (mismatch → SYSTEM)
        currentUser = (String) zs.getAttribute("loggedUser");
        if (currentUser == null) currentUser = DEFAULT_USER;

        openBatchId = null;
        chequeList  = null;
        dlgIndex    = 0;

        dlgChequeVerify.setVisible(false);
        dlgBackdrop.setVisible(false);

        showPhase(1);
        loadBatchList();
        LOG.info("VerificationOneComposer init — user=" + currentUser);
    }

    private void showPhase(int phase) {
        pnlBatchList.setVisible(phase == 1);
        pnlChequeList.setVisible(phase == 2);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 1 — BATCH LIST
    // ══════════════════════════════════════════════════════════════════════

    private void loadBatchList() {
        List<ChequeEntity> v1Pending = chequeService.getChequesByVerLevel("V1", ChequeStatus.V1_PENDING.db());
        Map<String, Long> pendingByBatch = v1Pending.stream()
                .collect(Collectors.groupingBy(ChequeEntity::getBatchId, Collectors.counting()));

        List<BatchEntity> batches = batchService.getAllBatches().stream()
                .filter(b -> pendingByBatch.containsKey(b.getBatchId()))
                .filter(b -> {
                    BatchStatus bs = BatchStatus.fromDb(b.getStatus());
                    return bs == BatchStatus.READY_FOR_VERIFICATION || bs == BatchStatus.VERIFICATION_IN_PROGRESS;
                })
                .collect(Collectors.toList());

        lbBatches.getItems().clear();
        for (BatchEntity b : batches) {
            long pending   = pendingByBatch.getOrDefault(b.getBatchId(), 0L);
            long processed = Math.max(0, b.getTotalCheques() - pending);

            Listitem row = new Listitem();
            row.appendChild(cell(b.getBatchId()));
            row.appendChild(cellCenter(String.valueOf(b.getTotalCheques())));
            row.appendChild(cellCenter(String.valueOf(pending)));
            row.appendChild(cellCenter(String.valueOf(processed)));
            row.appendChild(cell(b.getCreatedAt() != null ? b.getCreatedAt().toString() : "—"));

            BatchStatus bs = BatchStatus.fromDb(b.getStatus());
            Listcell sc = new Listcell();
            Label sl = new Label(bs.getLabel());
            sl.setSclass("batch-pill " + batchPillClass(bs));
            sc.appendChild(sl);
            row.appendChild(sc);

            Listcell ac = new Listcell();
            String lbl = bs == BatchStatus.VERIFICATION_IN_PROGRESS ? "Resume" : "Process";
            Button ab = new Button(lbl);
            ab.setSclass("v1-action-process-btn");
            final String bid = b.getBatchId();
            ab.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK, e -> openBatchChequeList(bid));
            ac.appendChild(ab);
            row.appendChild(ac);

            row.setParent(lbBatches);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — CHEQUE LIST
    // ══════════════════════════════════════════════════════════════════════

    private void openBatchChequeList(String batchId) {
        BatchEntity batch = batchService.getBatchById(batchId);
        if (batch != null && BatchStatus.fromDb(batch.getStatus()) == BatchStatus.READY_FOR_VERIFICATION) {
            batchService.updateBatchStatus(batchId, BatchStatus.VERIFICATION_IN_PROGRESS.db());
        }

        List<ChequeEntity> cheques = chequeService.getChequesForBatch(batchId);
        if (cheques == null || cheques.isEmpty()) {
            Messagebox.show("No cheques found in batch " + batchId + ".", "Empty Batch", Messagebox.OK, Messagebox.INFORMATION);
            return;
        }

        openBatchId = batchId;
        chequeList  = cheques;
        lblBatchTitle.setValue(batchId);
        renderChequeList();
        showPhase(2);
    }

    private void renderChequeList() {
        lbCheques.getItems().clear();
        for (int i = 0; i < chequeList.size(); i++) {
            ChequeEntity c = chequeList.get(i);
            final int idx = i;

            Listitem row = new Listitem();
            row.appendChild(cellCenter(String.valueOf(i + 1)));
            row.appendChild(cellMono(nv(c.getChequeNo())));
            row.appendChild(cell(nv(c.getPayeeName())));
            row.appendChild(cellAmt(fmtAmt(c.getAmount())));
            row.appendChild(cell(nv(c.getChequeDate())));

            ChequeStatus cs = ChequeStatus.fromDb(c.getStatus());
            Listcell vc = new Listcell();
            Label vl = new Label(cs.getLabel());
            vl.setSclass("v1-row-status-badge " + verBadgeClass(cs));
            vc.appendChild(vl);
            row.appendChild(vc);

            Listcell ac = new Listcell();
            Button ob = new Button("Open");
            ob.setSclass("v1-action-open-btn");
            ob.setDisabled(cs != ChequeStatus.V1_PENDING);
            ob.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK, e -> openChequePopup(idx));
            ac.appendChild(ob);
            row.appendChild(ac);

            row.setParent(lbCheques);
        }
        updateCounters();
    }

    private void updateCounters() {
        long pending  = countByStatus(ChequeStatus.V1_PENDING);
        long accepted = countByStatus(ChequeStatus.VERIFIED);
        long rejected = countByStatus(ChequeStatus.REJECTED);
        spCntPending.setValue(pending + " Pending");
        spCntPassed.setValue(accepted + " Accepted");
        spCntRejected.setValue(rejected + " Rejected");
    }

    private long countByStatus(ChequeStatus s) {
        return chequeList.stream().filter(c -> s.db().equals(c.getStatus())).count();
    }

    @Listen("onClick = #btnBackToBatches")
    public void onBackToBatches() {
        closePopup();
        openBatchId = null;
        chequeList  = null;
        showPhase(1);
        loadBatchList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // POPUP
    // ══════════════════════════════════════════════════════════════════════

    private void openChequePopup(int index) {
        if (chequeList == null || index < 0 || index >= chequeList.size()) return;
        dlgIndex    = index;
        showingRear = false;
        renderPopup();
        dlgBackdrop.setVisible(true);
        dlgChequeVerify.setVisible(true);
    }

    private void closePopup() {
        dlgChequeVerify.setVisible(false);
        dlgBackdrop.setVisible(false);
    }

    @Listen("onClick = #btnCloseDlg")
    public void onCloseDlg() { closePopup(); }

    private void renderPopup() {
        ChequeEntity c = chequeList.get(dlgIndex);

        dlgBatchPill.setValue("Batch: " + openBatchId);
        dlgRecordPos.setValue((dlgIndex + 1) + " / " + chequeList.size());

        // MICR
        dlgChequeNo.setValue(nv(c.getChequeNo()));
        String sort = c.getSortCode() != null ? c.getSortCode().replaceAll("[^0-9]", "") : "";
        dlgCityCode.setValue(sortSegment(sort, 0, 3));
        dlgBankCode.setValue(sortSegment(sort, 3, 6));
        dlgBranchCode.setValue(sortSegment(sort, 6, 9));

        // Static fields from DB
        dlgPayeeName.setValue(nv(c.getPayeeName()));
        dlgAccountNo.setValue(nv(c.getPayeeAccountNo()));
        dlgChequeDate.setValue(nv(c.getChequeDate()));
        dlgAmount.setValue(fmtAmt(c.getAmount()));
        dlgAmountWords.setValue(nv(c.getAmountInWords()));

        // CBS live lookup — payee_account_no only
        String accNo = c.getPayeeAccountNo();
        if (accNo != null && !accNo.isBlank()) {
            com.fasterxml.jackson.databind.JsonNode fields = cbsService.lookupAccountFields(accNo);
            if (fields != null && !fields.isMissingNode()) {
                String cbsName = fields.path("accountHolderName").path("stringValue").asText(null);
                boolean active = fields.path("active").path("booleanValue").asBoolean(false);

                dlgCbsAccName.setValue(cbsName != null ? cbsName : "\u2014");
                dlgCbsAccStatus.setValue(active ? "Active" : "Inactive");
                // New Account: Yes if account opened < 90 days ago
                dlgCbsNewAcc.setValue(cbsService.getIsNewAccount(accNo));

                String payee = c.getPayeeName();
                if (cbsName != null && payee != null) {
                    boolean match = cbsName.trim().equalsIgnoreCase(payee.trim());
                    dlgCbsPayeeMatch.setValue(match ? "Match" : "Mismatch");
                    dlgCbsPayeeMatch.setSclass(match ? "cbs-match-ok" : "cbs-match-fail");
                } else {
                    dlgCbsPayeeMatch.setValue("\u2014");
                    dlgCbsPayeeMatch.setSclass("");
                }
            } else {
                dlgCbsAccName.setValue("\u2014");
                dlgCbsAccStatus.setValue("Not found");
                dlgCbsNewAcc.setValue("\u2014");
                dlgCbsPayeeMatch.setValue("\u2014");
                dlgCbsPayeeMatch.setSclass("");
            }
        } else {
            dlgCbsAccName.setValue("\u2014");
            dlgCbsAccStatus.setValue("\u2014");
            dlgCbsNewAcc.setValue("\u2014");
            dlgCbsPayeeMatch.setValue("\u2014");
            dlgCbsPayeeMatch.setSclass("");
        }

        cmbRejectReason.setSelectedItem(null);
        cmbReferReason.setSelectedItem(null);

        showingRear = false;
        loadImage(c, "front");
        btnToggleSide.setLabel("⇄ Show BACK");
    }

    private void loadImage(ChequeEntity c, String side) {
        if (c.getId() == null) {
            dlgImagePh.setValue("No image");
            dlgImagePh.setVisible(true);
            dlgImage.setVisible(false);
            return;
        }
        dlgImage.setSrc(IMG_SERVLET + "?id=" + c.getId() + "&side=" + side + "&t=" + System.currentTimeMillis());
        dlgImage.setVisible(true);
        dlgImagePh.setVisible(false);
    }

    @Listen("onClick = #btnToggleSide")
    public void onToggleSide() {
        ChequeEntity c = currentCheque();
        if (c == null) return;
        showingRear = !showingRear;
        loadImage(c, showingRear ? "rear" : "front");
        btnToggleSide.setLabel(showingRear ? "⇄ Show FRONT" : "⇄ Show BACK");
    }

    // ── Nav ──────────────────────────────────────────────────────────────

    @Listen("onClick = #btnDlgPrev")
    public void onDlgPrev() {
        if (dlgIndex > 0) { dlgIndex--; showingRear = false; renderPopup(); }
    }

    @Listen("onClick = #btnDlgNext")
    public void onDlgNext() {
        if (dlgIndex < chequeList.size() - 1) { dlgIndex++; showingRear = false; renderPopup(); }
    }

    // ── Actions ──────────────────────────────────────────────────────────

    @Listen("onClick = #btnDlgAccept")
    public void onDlgAccept() {
        ChequeEntity c = currentCheque();
        if (c == null) return;

        // FIX: account-only CBS check — no /cheques collection lookup needed for V1.
        // Old code called validateCheque() which hit /cheques/{acct}_{chequeNo}
        // (empty collection) → always returned CHEQUE_NOT_FOUND → blocked every Accept.
        String accNo = c.getPayeeAccountNo();
        if (accNo != null && !accNo.isBlank()) {
            com.fasterxml.jackson.databind.JsonNode fields = cbsService.lookupAccountFields(accNo);
            if (fields == null || fields.isMissingNode()) {
                Messagebox.show("Account not found in CBS. Cannot accept.",
                        "CBS Validation Failed", Messagebox.OK, Messagebox.EXCLAMATION);
                return;
            }
            if (!fields.path("active").path("booleanValue").asBoolean(false)) {
                Messagebox.show("Account is inactive in CBS. Cannot accept.",
                        "CBS Validation Failed", Messagebox.OK, Messagebox.EXCLAMATION);
                return;
            }
        }

        chequeService.applyVerifierAction(c.getId(), ChequeStatus.VERIFIED.db(), "V1", ChequeStatus.VERIFIED.db(), currentUser, "");
        afterAction(ChequeStatus.VERIFIED.db());
    }

    @Listen("onClick = #btnDlgReject")
    public void onDlgReject() {
        ChequeEntity c = currentCheque();
        if (c == null) return;
        String reason = comboValue(cmbRejectReason);
        if (reason == null) {
            Messagebox.show("Select a rejection reason.", "Validation", Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }
        chequeService.applyVerifierAction(c.getId(), ChequeStatus.REJECTED.db(), "V1", ChequeStatus.REJECTED.db(), currentUser, reason);
        afterAction(ChequeStatus.REJECTED.db());
    }

    @Listen("onClick = #btnDlgRefer")
    public void onDlgRefer() {
        ChequeEntity c = currentCheque();
        if (c == null) return;
        String reason = comboValue(cmbReferReason);
        if (reason == null) {
            Messagebox.show("Select a refer reason.", "Validation", Messagebox.OK, Messagebox.EXCLAMATION);
            return;
        }
        chequeService.referToVerificationTwo(c.getId(), currentUser, reason);
        afterAction(ChequeStatus.V2_PENDING.db());
    }

    private void afterAction(String newStatus) {
        ChequeEntity c = currentCheque();
        if (c != null) {
            c.setStatus(newStatus);
            c.setVerStatus(newStatus);
        }
        batchService.checkAndFinalizeBatch(openBatchId);
        renderChequeList();
        closePopup();
        advanceToNextPendingOrClose();
    }

    private void advanceToNextPendingOrClose() {
        for (int i = 0; i < chequeList.size(); i++) {
            if (ChequeStatus.V1_PENDING.db().equals(chequeList.get(i).getStatus())) {
                openChequePopup(i);
                return;
            }
        }
        Messagebox.show("All cheques in batch " + openBatchId + " have been actioned.",
                "Batch Complete", Messagebox.OK, Messagebox.INFORMATION);
    }

    private ChequeEntity currentCheque() {
        if (chequeList == null || dlgIndex < 0 || dlgIndex >= chequeList.size()) return null;
        return chequeList.get(dlgIndex);
    }

    private String comboValue(Combobox cmb) {
        return cmb.getSelectedItem() != null ? (String) cmb.getSelectedItem().getValue() : null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private String batchPillClass(BatchStatus bs) {
        switch (bs) {
            case VERIFICATION_IN_PROGRESS: return "batch-pill-blue";
            case READY_FOR_VERIFICATION:   return "batch-pill-amber";
            case VERIFIED:                 return "batch-pill-green";
            default:                       return "batch-pill-gray";
        }
    }

    private String verBadgeClass(ChequeStatus cs) {
        switch (cs) {
            case VERIFIED:   return "badge-green";
            case REJECTED:   return "badge-red";
            case PENDING:    return "badge-gray";
            default:         return "badge-amber";
        }
    }

    private String sortSegment(String s, int from, int to) {
        if (s == null || s.isEmpty() || s.length() <= from) return "—";
        return s.substring(from, Math.min(to, s.length()));
    }

    private Listcell cell(String t)       { return new Listcell(t == null ? "—" : t); }
    private Listcell cellCenter(String t) { Listcell lc = cell(t); lc.setStyle("text-align:center"); return lc; }
    private Listcell cellMono(String t)   { Listcell lc = cell(t); lc.setSclass("mono"); return lc; }
    private Listcell cellAmt(String t)    { Listcell lc = cell(t); lc.setSclass("amt");  return lc; }
    private String   fmtAmt(BigDecimal v) { return v == null ? "Rs. 0.00" : "Rs. " + NumberFormat.getNumberInstance(new Locale("en", "IN")).format(v); }
    private String   nv(String s)         { return (s == null || s.isBlank()) ? "—" : s; }

    private String cbsValidationMessage(String code) {
        switch (code) {
            case "ACCOUNT_NOT_FOUND":      return "Account not found in CBS. Cannot accept.";
            case "ACCOUNT_INACTIVE":       return "Account is inactive in CBS. Cannot accept.";
            default:                       return "CBS validation failed (" + code + "). Cannot accept.";
        }
    }
}