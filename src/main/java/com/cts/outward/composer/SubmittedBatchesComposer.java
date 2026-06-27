package com.cts.outward.composer;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Textbox;

import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.service.BatchService;
import com.cts.outward.service.BatchServiceImpl;

/**
 * ZK SelectorComposer for {@code submitted-batches.zul}.
 *
 * <p>Displays only {@code ReadyForVerification} batches — batches the Maker has
 * fully completed (all cheques saved) and submitted to the Verifier queue.
 * Read-only view: no Create Batch, no Save Batch, no edit actions.
 *
 * <h3>Status scope</h3>
 * <ul>
 *   <li>Shows: {@link BatchStatus#READY_FOR_VERIFICATION} ({@code "ReadyForVerification"}) only.</li>
 *   <li>Does NOT show Draft, Pending, or any downstream verifier/CXF states.</li>
 *   <li>No "Create Batch" or "Save Batch" buttons — Maker's work is done.</li>
 * </ul>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   submitted-batches.zul
 *       └── SubmittedBatchesComposer
 *               └── BatchService → BatchServiceImpl → BatchDAOImpl → cts_batches
 * </pre>
 *
 * @author Umesh M.
 * @see BatchService
 */
public class SubmittedBatchesComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(SubmittedBatchesComposer.class.getName());

    private static final int PAGE_SIZE = 5;

    /** Hardcoded status — this page shows ReadyForVerification only. */
    private static final String STATUS_FILTER = BatchStatus.READY_FOR_VERIFICATION.db(); // "ReadyForVerification"

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Pagination state ──────────────────────────────────────────────
    private int  currentPage = 1;
    private int  totalPages  = 1;
    private long totalRows   = 0;

    // ── Filter state ──────────────────────────────────────────────────
    private String filterSearch   = "";
    private String filterFromDate = null;
    private String filterToDate   = null;

    // ── Service ───────────────────────────────────────────────────────
    private final BatchService batchService =
            new BatchServiceImpl(new BatchDAOImpl(), new ChequeDAOImpl());

    // ── Wired components ──────────────────────────────────────────────

    @Wire("#lblBatchCount")
    private Label lblBatchCount;

    @Wire("#txtSearch")
    private Textbox txtSearch;

    @Wire("#dtFrom")
    private Datebox dtFrom;

    @Wire("#dtTo")
    private Datebox dtTo;

    @Wire("#lbBatches")
    private Listbox lbBatches;

    @Wire("#btnPgFirst")
    private Button btnPgFirst;
    @Wire("#btnPgPrev")
    private Button btnPgPrev;
    @Wire("#btnPgNext")
    private Button btnPgNext;
    @Wire("#btnPgLast")
    private Button btnPgLast;
    @Wire("#lblPgInfo")
    private Label lblPgInfo;

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        guardSession();
        loadPage();
    }

    // ── Filter listeners ──────────────────────────────────────────────

    @Listen("onChanging = #txtSearch")
    public void onSearch(org.zkoss.zk.ui.event.InputEvent e) {
        filterSearch = e.getValue() != null ? e.getValue().trim() : "";
        currentPage = 1;
        loadPage();
    }

    @Listen("onChange = #dtFrom")
    public void onFromDate() {
        filterFromDate = toIsoDate(dtFrom != null ? dtFrom.getValue() : null);
        currentPage = 1;
        loadPage();
    }

    @Listen("onChange = #dtTo")
    public void onToDate() {
        filterToDate = toIsoDate(dtTo != null ? dtTo.getValue() : null);
        currentPage = 1;
        loadPage();
    }

    @Listen("onClick = #btnClearDate")
    public void onClearDate() {
        if (dtFrom != null) dtFrom.setValue((Date) null);
        if (dtTo   != null) dtTo.setValue((Date) null);
        filterFromDate = null;
        filterToDate   = null;
        currentPage = 1;
        loadPage();
    }

    // ── Pagination listeners ──────────────────────────────────────────

    @Listen("onClick = #btnPgFirst")
    public void onPgFirst() { currentPage = 1; loadPage(); }

    @Listen("onClick = #btnPgPrev")
    public void onPgPrev() { if (currentPage > 1) { currentPage--; loadPage(); } }

    @Listen("onClick = #btnPgNext")
    public void onPgNext() { if (currentPage < totalPages) { currentPage++; loadPage(); } }

    @Listen("onClick = #btnPgLast")
    public void onPgLast() { currentPage = totalPages; loadPage(); }

    // ── Core load ─────────────────────────────────────────────────────

    private void loadPage() {
        try {
            totalRows = batchService.countBatches(filterSearch, STATUS_FILTER, filterFromDate, filterToDate);
        } catch (Exception ex) {
            LOG.severe("countBatches error: " + ex.getMessage());
            totalRows = 0;
        }

        totalPages = (totalRows == 0) ? 1 : (int) Math.ceil((double) totalRows / PAGE_SIZE);
        if (currentPage > totalPages) currentPage = totalPages;
        if (currentPage < 1)          currentPage = 1;

        List<BatchEntity> page;
        try {
            page = batchService.getBatchesPage(
                    filterSearch, STATUS_FILTER, filterFromDate, filterToDate,
                    PAGE_SIZE, currentPage);
        } catch (Exception ex) {
            LOG.severe("getBatchesPage error: " + ex.getMessage());
            page = Collections.emptyList();
        }

        renderPage(page);
    }

    // ── Render ────────────────────────────────────────────────────────

    private void renderPage(List<BatchEntity> batches) {
        if (lbBatches == null) return;
        lbBatches.getItems().clear();

        if (lblBatchCount != null)
            lblBatchCount.setValue(totalRows + " batch" + (totalRows != 1 ? "es" : ""));

        if (lblPgInfo  != null) lblPgInfo.setValue("Page " + currentPage + " of " + totalPages);
        if (btnPgFirst != null) btnPgFirst.setDisabled(currentPage <= 1);
        if (btnPgPrev  != null) btnPgPrev.setDisabled(currentPage <= 1);
        if (btnPgNext  != null) btnPgNext.setDisabled(currentPage >= totalPages);
        if (btnPgLast  != null) btnPgLast.setDisabled(currentPage >= totalPages);

        if (batches == null || batches.isEmpty()) return;

        for (BatchEntity b : batches) appendRow(b);
    }

    private void appendRow(BatchEntity b) {
        Listitem row = new Listitem();
        row.setSclass("mb-row");
        final String bid = b.getBatchId() != null ? b.getBatchId() : "";
        if (!bid.isEmpty()) row.addEventListener("onClick", e -> openBatch(bid));

        // Col 1: Batch ID
        Listcell idCell = new Listcell();
        Label idLbl = new Label(!bid.isEmpty() ? bid : "—");
        idLbl.setSclass("mb-link");
        idCell.appendChild(idLbl);
        row.appendChild(idCell);

        // Col 2: Cheque count
        row.appendChild(new Listcell(String.valueOf(b.getTotalCheques())));

        // Col 3: Amount
        Listcell amtCell = new Listcell(fmtAmt(b.getTotalAmount()));
        amtCell.setSclass("amt-cell");
        row.appendChild(amtCell);

        // Col 4: Date
        row.appendChild(new Listcell(
                b.getCreatedAt() != null ? b.getCreatedAt().format(DISPLAY_DATE) : "—"));

        // Col 5: Status chip — always "Submitted" on this page
        Listcell stCell = new Listcell();
        Label stLbl = new Label("Submitted");
        stLbl.setSclass("chip ch-submitted"); // blue/green chip for Submitted
        stCell.appendChild(stLbl);
        row.appendChild(stCell);

        // Col 6: Action — View only (batch is locked read-only for Maker)
        Listcell actCell = new Listcell();
        Label viewLbl = new Label("View");
        viewLbl.setSclass("mb-action-link");
        actCell.appendChild(viewLbl);
        row.appendChild(actCell);

        lbBatches.appendChild(row);
    }

    // ── Row actions ───────────────────────────────────────────────────

    private void openBatch(String batchId) {
        Sessions.getCurrent().setAttribute("selectedBatchId", batchId);
        Sessions.getCurrent().setAttribute("batchDetailBackPage", "/zul/outward/submitted-batches.zul");
        com.cts.composer.DashboardComposer.navigateTo("/zul/outward/batch-detail.zul");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void guardSession() {
        if (!com.cts.util.SecurityUtil.isLoggedIn())
            Executions.sendRedirect("/zul/login.zul");
    }

    private String toIsoDate(Date d) {
        if (d == null) return null;
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private String fmtAmt(BigDecimal a) {
        return a != null ? "₹" + String.format("%,.2f", a) : "₹0.00";
    }
}
