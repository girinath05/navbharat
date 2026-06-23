package com.cts.outward.composer;

import com.cts.outward.dao.CxfCibfDAO;
import com.cts.outward.dao.CxfCibfDAOImpl;
import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.service.CxfCibfService;
import com.cts.outward.service.CxfCibfServiceImpl;
import com.cts.outward.service.CxfFileResult;
import com.cts.outward.enums.BatchStatus;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.*;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.InputEvent;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.zkoss.zk.ui.Executions;

/**
 * ZK Composer for webapp/zul/outward/cxf-cxbf.zul
 * Manages the unified search, date-filter, status category dropdown,
 * dynamic columns listbox, and manual status transitions.
 */
public class CxfCibfComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(CxfCibfComposer.class.getName());

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final NumberFormat INR = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ALL = "ALL";

    @Wire
    Textbox txtSearchBatchId;
    @Wire
    Combobox cmbStatus;
    @Wire
    Datebox dbFromDate;
    @Wire
    Datebox dbToDate;
    @Wire
    Button btnSearch;
    @Wire
    Button btnReset;

    @Wire
    Label lblPending;
    @Wire
    Label lblCompleted;

    @Wire
    Label lblTableTitle;
    @Wire
    Label lblTableSubtitle;

    @Wire
    Listbox lbDynamic;
    @Wire
    Listhead lhDynamic;
    @Wire
    Paging pgDynamic;

    @Wire
    Button btnPrev;
    @Wire
    Button btnNext;
    @Wire
    Label lblPage;
    @Wire
    Label lblRecordCount;

    @Wire("#btnGenerateCxf")
    Button btnGenerateCxf;

    @Wire
    Window winSuccessDialog;
    @Wire
    Label lblBatchCount;

    private List<CxfFileResult> lastResults;

    private final org.zkoss.zk.ui.event.EventListener<org.zkoss.zk.ui.event.Event> viewFileListener = event -> onClickViewFile();
    private final org.zkoss.zk.ui.event.EventListener<org.zkoss.zk.ui.event.Event> doneListener = event -> onClickDone();

    private CxfCibfService service;
    private CxfCibfDAO dao;

    private String currentStatusFilter = STATUS_PENDING;
    private String searchBatchId = "";
    private java.util.Date filterFromDate = null;
    private java.util.Date filterToDate = null;
    private Checkbox chkSelectAllHeader;

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        dao = new CxfCibfDAOImpl();
        service = new CxfCibfServiceImpl();

        // Set default selection
        cmbStatus.setSelectedIndex(2);
        currentStatusFilter = STATUS_ALL;

        updateTableLabels();
        rebuildTableColumns(currentStatusFilter);
        refreshPageData();
    }

    private void refreshPageData() {
        try {
            // 1. Fetch raw data for each category to calculate stats under the active filters
            List<CxfBatchDTO> rawPending = dao.findPendingBatches();
            List<CxfBatchDTO> rawCompleted = dao.findCompletedBatches();

            // Apply active filters to count stats dynamically
            long countPending = rawPending.stream().filter(this::matchesFilters).count();
            long countCompleted = rawCompleted.stream().filter(this::matchesFilters).count();

            lblPending.setValue(String.valueOf(countPending));
            lblCompleted.setValue(String.valueOf(countCompleted));

            // Get the list of batches for the currently selected status filter category
            List<CxfBatchDTO> activeRawList;
            switch (currentStatusFilter) {
                case STATUS_PENDING:
                    activeRawList = rawPending;
                    break;
                case STATUS_COMPLETED:
                    activeRawList = rawCompleted;
                    break;
                case STATUS_ALL:
                default:
                    List<CxfBatchDTO> all = new ArrayList<>();
                    all.addAll(rawPending);
                    all.addAll(rawCompleted);
                    activeRawList = all;
                    break;
            }

            // Apply active filters to populate the dynamic table
            List<CxfBatchDTO> filtered = activeRawList.stream()
                    .filter(this::matchesFilters)
                    .collect(Collectors.toList());

            // 3. Visibility
            updateActionButtonsVisibility();

            // 4. Render
            populateDynamicTable(filtered);

            // 5. Pagination
            refreshPagingWidgets();

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "refreshPageData failed", ex);
            displayErrorMessageBox("Failed to load data: " + ex.getMessage());
        }
    }

    private boolean matchesFilters(CxfBatchDTO batch) {
        if (!searchBatchId.isEmpty() && !batch.getBatchId().toLowerCase().contains(searchBatchId.toLowerCase())) {
            return false;
        }
        LocalDateTime checkDateTime = getDynamicDate(batch);
        if (checkDateTime != null) {
            java.time.LocalDate checkLD = checkDateTime.toLocalDate();
            if (filterFromDate != null) {
                java.time.LocalDate fromLD = filterFromDate.toInstant()
                                              .atZone(java.time.ZoneId.systemDefault())
                                              .toLocalDate();
                if (checkLD.isBefore(fromLD)) {
                    return false;
                }
            }
            if (filterToDate != null) {
                java.time.LocalDate toLD = filterToDate.toInstant()
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDate();
                if (checkLD.isAfter(toLD)) {
                    return false;
                }
            }
        } else {
            if (filterFromDate != null || filterToDate != null) {
                return false;
            }
        }
        return true;
    }


    private void updateActionButtonsVisibility() {
        btnGenerateCxf.setVisible(true);
    }

    private void updateActionButtonsEnablementState() {
        boolean hasSelection = !lbDynamic.getSelectedItems().isEmpty();
        boolean isPendingView = STATUS_PENDING.equals(currentStatusFilter);
        btnGenerateCxf.setDisabled(!(isPendingView && hasSelection));
    }

    private void updateTableLabels() {
        switch (currentStatusFilter) {
            case STATUS_PENDING:
                lblTableTitle.setValue("PENDING BATCHES FOR CXF-CIBF GENERATION");
                lblTableSubtitle.setValue("Verified batches awaiting CXF & CIBF file generation");
                break;
            case STATUS_COMPLETED:
                lblTableTitle.setValue("COMPLETED BATCHES");
                lblTableSubtitle.setValue("CXF + CIBF files generated and ready");
                break;
            case STATUS_ALL:
                lblTableTitle.setValue("ALL BATCHES");
                lblTableSubtitle.setValue("Combined view of all batches across all statuses");
                break;
        }
    }

    private void rebuildTableColumns(String statusFilter) {
        lhDynamic.getChildren().clear();
        chkSelectAllHeader = null;

        switch (statusFilter) {
            case STATUS_PENDING:
                lbDynamic.setSclass("v1-listbox v1-batch-lb cxf-pending-table");
                addBatchIdCheckboxHeader();
                addHeader("TOTAL CHEQUES");
                addHeader("TOTAL AMOUNT");
                addHeader("STATUS");
                break;
            case STATUS_COMPLETED:
                lbDynamic.setSclass("v1-listbox v1-batch-lb cxf-completed-table");
                addHeader("BATCH ID");
                addHeader("TOTAL CHEQUES");
                addHeader("TOTAL AMOUNT");
                addHeader("CXF FILE");
                addHeader("CIBF FILE");
                addHeader("GENERATED AT");
                addHeader("STATUS");
                break;
            case STATUS_ALL:
                lbDynamic.setSclass("v1-listbox v1-batch-lb cxf-completed-table");
                addHeader("BATCH ID");
                addHeader("TOTAL CHEQUES");
                addHeader("TOTAL AMOUNT");
                addHeader("CXF FILE");
                addHeader("CIBF FILE");
                addHeader("GENERATED AT");
                addHeader("STATUS");
                break;
        }
    }

    private void addHeader(String label) {
        Listheader lh = new Listheader(label);
        lh.setSclass("v1-lh");
        lh.setAlign("center");
        lh.setHflex("1");
        lhDynamic.appendChild(lh);
    }

    private void addBatchIdCheckboxHeader() {
        Listheader lhBatchId = new Listheader();
        lhBatchId.setSclass("v1-lh");
        lhBatchId.setAlign("center");
        lhBatchId.setHflex("1");

        Div div = new Div();
        div.setSclass("cxf-header-checkbox-container");

        Checkbox selectAll = new Checkbox();
        selectAll.setTooltiptext("Select / Deselect All");
        selectAll.setSclass("cxf-header-checkbox");
        selectAll.addEventListener(Events.ON_CHECK, event -> {
            selectAllRows(selectAll.isChecked());
        });
        chkSelectAllHeader = selectAll;

        Label span = new Label("BATCH ID");
        span.setStyle("font-weight:700;color:#CBD5E1;letter-spacing:0.06em;");

        div.appendChild(selectAll);
        div.appendChild(span);
        lhBatchId.appendChild(div);
        lhDynamic.appendChild(lhBatchId);
    }

    private void selectAllRows(boolean checked) {
        for (Listitem item : lbDynamic.getItems()) {
            if (isCheckable(item)) {
                item.setSelected(checked);
                if (!item.getChildren().isEmpty()) {
                    Component cell = item.getChildren().get(0);
                    if (!cell.getChildren().isEmpty()) {
                        Component div = cell.getChildren().get(0);
                        if (div instanceof Div && !div.getChildren().isEmpty()) {
                            Component cb = div.getChildren().get(0);
                            if (cb instanceof Checkbox) {
                                ((Checkbox) cb).setChecked(checked);
                            }
                        }
                    }
                }
            } else {
                item.setSelected(false);
                if (!item.getChildren().isEmpty()) {
                    Component cell = item.getChildren().get(0);
                    if (!cell.getChildren().isEmpty()) {
                        Component div = cell.getChildren().get(0);
                        if (div instanceof Div && !div.getChildren().isEmpty()) {
                            Component cb = div.getChildren().get(0);
                            if (cb instanceof Checkbox) {
                                ((Checkbox) cb).setChecked(false);
                            }
                        }
                    }
                }
            }
        }
        updateActionButtonsEnablementState();
    }

    private void populateDynamicTable(List<CxfBatchDTO> list) {
        lbDynamic.getItems().clear();

        for (CxfBatchDTO batchDTO : list) {
            Listitem row = new Listitem();
            row.setValue(batchDTO.getBatchId());

            switch (currentStatusFilter) {
                case STATUS_PENDING:
                    row.appendChild(createCheckboxAndIdCell(batchDTO.getBatchId(), row, true));
                    appendListcellWithTextAndStyle(row, String.valueOf(batchDTO.getTotalCheques()), null);
                    appendListcellWithTextAndStyle(row, formatCurrencyToIndianRupees(batchDTO.getTotalAmount()), "color:#166534;font-weight:700;");
                    row.appendChild(createListcellWithStatusPill("Ready for file generation.", "batch-pill batch-pill-amber"));
                    break;
                case STATUS_COMPLETED:
                    appendListcellWithTextAndStyle(row, batchDTO.getBatchId(), "font-weight:700;color:#1B2E4B;");
                    appendListcellWithTextAndStyle(row, String.valueOf(batchDTO.getTotalCheques()), null);
                    appendListcellWithTextAndStyle(row, formatCurrencyToIndianRupees(batchDTO.getTotalAmount()), "color:#166534;font-weight:700;");
                    appendLongValueCell(row, batchDTO.getCxfFileName());
                    appendLongValueCell(row, batchDTO.getCibfFileName());
                    appendListcellWithTextAndStyle(row, formatLocalDateTimeToString(batchDTO.getGeneratedAt()), "font-size:11px;");
                    row.appendChild(createListcellWithStatusPill("CXF-CIBF generated", "batch-pill batch-pill-green"));
                    break;
                case STATUS_ALL:
                    appendListcellWithTextAndStyle(row, batchDTO.getBatchId(), "font-weight:700;color:#1B2E4B;");
                    appendListcellWithTextAndStyle(row, String.valueOf(batchDTO.getTotalCheques()), null);
                    appendListcellWithTextAndStyle(row, formatCurrencyToIndianRupees(batchDTO.getTotalAmount()), "color:#166534;font-weight:700;");
                    appendLongValueCell(row, batchDTO.getCxfFileName());
                    appendLongValueCell(row, batchDTO.getCibfFileName());
                    appendListcellWithTextAndStyle(row, formatLocalDateTimeToString(batchDTO.getGeneratedAt()), "font-size:11px;");
                    row.appendChild(createListcellWithStatusPill(formatStatusStringToFriendlyLabel(batchDTO.getStatus()), retrieveCssClassForStatusPill(batchDTO.getStatus())));
                    break;
            }
            lbDynamic.appendChild(row);
        }
        updateActionButtonsEnablementState();
    }

    private Listcell createCheckboxAndIdCell(String batchId, Listitem row, boolean checkable) {
        Listcell cell = new Listcell();
        Div cellDiv = new Div();
        cellDiv.setSclass("cxf-cell-checkbox-container");

        Checkbox rowCb = new Checkbox();
        rowCb.setSclass("cxf-cell-checkbox");
        rowCb.setDisabled(!checkable);
        if (checkable) {
            rowCb.addEventListener(Events.ON_CHECK, event -> {
                row.setSelected(rowCb.isChecked());
                updateActionButtonsEnablementState();
                
                int checkableCount = 0;
                int selectedCheckableCount = 0;
                for (Listitem item : lbDynamic.getItems()) {
                    if (isCheckable(item)) {
                        checkableCount++;
                        if (item.isSelected()) {
                            selectedCheckableCount++;
                        }
                    }
                }
                if (chkSelectAllHeader != null) {
                    chkSelectAllHeader.setChecked(checkableCount > 0 && selectedCheckableCount == checkableCount);
                }
            });
        }

        Label lbl = new Label(batchId);
        lbl.setStyle("font-weight:700;color:#1B2E4B;white-space:nowrap;");

        cellDiv.appendChild(rowCb);
        cellDiv.appendChild(lbl);
        cell.appendChild(cellDiv);
        return cell;
    }

    @Listen("onSelect = #lbDynamic")
    public void onDynamicTableSelectionChanged() {
        for (Listitem item : lbDynamic.getItems()) {
            if (!item.getChildren().isEmpty()) {
                Component cell = item.getChildren().get(0);
                if (!cell.getChildren().isEmpty()) {
                    Component div = cell.getChildren().get(0);
                    if (div instanceof Div && !div.getChildren().isEmpty()) {
                        Component cb = div.getChildren().get(0);
                        if (cb instanceof Checkbox) {
                            Checkbox rowCb = (Checkbox) cb;
                            if (rowCb.isDisabled()) {
                                item.setSelected(false);
                                rowCb.setChecked(false);
                            } else {
                                rowCb.setChecked(item.isSelected());
                            }
                        }
                    }
                }
            }
        }
        
        int checkableCount = 0;
        int selectedCheckableCount = 0;
        for (Listitem item : lbDynamic.getItems()) {
            if (isCheckable(item)) {
                checkableCount++;
                if (item.isSelected()) {
                    selectedCheckableCount++;
                }
            }
        }
        if (chkSelectAllHeader != null) {
            chkSelectAllHeader.setChecked(checkableCount > 0 && selectedCheckableCount == checkableCount);
        }
        updateActionButtonsEnablementState();
    }

    private boolean isCheckable(Listitem item) {
        if (item.getChildren().isEmpty()) return false;
        Component cell = item.getChildren().get(0);
        if (cell.getChildren().isEmpty()) return false;
        Component div = cell.getChildren().get(0);
        if (div instanceof Div && !div.getChildren().isEmpty()) {
            Component cb = div.getChildren().get(0);
            if (cb instanceof Checkbox) {
                return !((Checkbox) cb).isDisabled();
            }
        }
        return false;
    }

    @Listen("onSelect = #cmbStatus")
    public void onStatusChanged() {
        Comboitem selectedItem = cmbStatus.getSelectedItem();
        currentStatusFilter = selectedItem != null ? (String) selectedItem.getValue() : STATUS_PENDING;

        updateTableLabels();
        rebuildTableColumns(currentStatusFilter);
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    @Listen("onChange = #txtSearchBatchId; onChange = #dbFromDate; onChange = #dbToDate")
    public void onFilterChanged() {
        searchBatchId = txtSearchBatchId.getValue().trim();
        filterFromDate = dbFromDate.getValue();
        filterToDate = dbToDate.getValue();
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    @Listen("onChanging = #txtSearchBatchId")
    public void onSearchChanging(InputEvent event) {
        searchBatchId = event.getValue() != null ? event.getValue().trim() : "";
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    @Listen("onClick = #btnReset")
    public void onResetClicked() {
        txtSearchBatchId.setValue("");
        dbFromDate.setValue(null);
        dbToDate.setValue(null);
        searchBatchId = "";
        filterFromDate = null;
        filterToDate = null;
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    @Listen("onPaging = #pgDynamic")
    public void onDynamicPaging() {
        refreshPagingWidgets();
    }

    @Listen("onClick = #btnPrev")
    public void onPrevClicked() {
        int page = pgDynamic.getActivePage();
        if (page > 0) {
            pgDynamic.setActivePage(page - 1);
            refreshPagingWidgets();
        }
    }

    @Listen("onClick = #btnNext")
    public void onNextClicked() {
        int page = pgDynamic.getActivePage();
        if (page < pgDynamic.getPageCount() - 1) {
            pgDynamic.setActivePage(page + 1);
            refreshPagingWidgets();
        }
    }

    private void refreshPagingWidgets() {
        if (pgDynamic == null) return;
        int activePage = pgDynamic.getActivePage();
        int totalPages = pgDynamic.getPageCount();
        int totalSize = pgDynamic.getTotalSize();
        int pageSize = pgDynamic.getPageSize();

        if (totalPages <= 1) {
            btnPrev.setDisabled(true);
            btnNext.setDisabled(true);
        } else {
            btnPrev.setDisabled(activePage == 0);
            btnNext.setDisabled(activePage == totalPages - 1);
        }

        int start = totalSize == 0 ? 0 : (activePage * pageSize + 1);
        int end = Math.min(totalSize, (activePage + 1) * pageSize);
        if (lblRecordCount != null) {
            lblRecordCount.setValue("Showing " + start + "-" + end + " of " + totalSize + " records");
        }

        lblPage.setValue("Page " + (activePage + 1) + " of " + Math.max(1, totalPages));
    }

    @Listen("onClick = #btnGenerateCxf")
    public void onClickGenerateCxfAndCibfButton() {
        Set<String> selectedIds = lbDynamic.getSelectedItems().stream()
                .map(item -> (String) item.getValue())
                .collect(Collectors.toSet());
        if (selectedIds.isEmpty()) return;

        List<CxfFileResult> results = new ArrayList<>();
        for (String batchId : selectedIds) {
            try {
                CxfFileResult fileResult = service.generateForBatch(batchId);
                results.add(fileResult);
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "generateForBatch error: " + batchId, ex);
                results.add(CxfFileResult.fail(batchId, ex.getMessage()));
            }
        }

        long successCount = results.stream().filter(CxfFileResult::isSuccess).count();
        long failCount = results.stream().filter(result -> !result.isSuccess()).count();

        refreshPageData();

        if (failCount == 0) {
            displaySuccessMessageBox(successCount, results);
        } else {
            StringBuilder sb = new StringBuilder();
            if (successCount > 0) {
                sb.append(successCount).append(" batch(es) generated successfully.\n\n");
            }
            sb.append("Failed batch(es):\n");
            results.stream().filter(result -> !result.isSuccess())
                    .forEach(result -> sb.append("  ").append(result.getBatchId())
                            .append(": ").append(result.getErrorMessage()).append("\n"));
            displayErrorMessageBox(sb.toString());
        }
    }

    private String formatLocalDateTimeWithSuffix(CxfBatchDTO batchDTO) {
        LocalDateTime dt = getDynamicDate(batchDTO);
        if (dt == null) return "-";
        
        String status = batchDTO.getStatus();
        return dt.format(DT_FMT) + getDynamicDateSuffix(status);
    }

    private String getDynamicDateSuffix(String status) {
        if (status == null) return " (Created)";
        BatchStatus bs = BatchStatus.fromDb(status);
        if (bs == BatchStatus.CXF_CIBF_GENERATED) {
            return " (Gen)";
        }
        return " (Created)";
    }

    private void appendListcellWithTextAndStyle(Listitem row, String value, String style) {
        Listcell cell = new Listcell(value != null ? value : "");
        if (style != null) cell.setStyle(style);
        row.appendChild(cell);
    }

    private void appendLongValueCell(Listitem row, String value) {
        String displayVal = (value != null) ? value : "";
        Listcell cell = new Listcell(displayVal);
        if (value != null && !value.isEmpty()) {
            cell.setTooltiptext(value);
        }
        row.appendChild(cell);
    }

    private Listcell createListcellWithStatusPill(String label, String sclass) {
        Listcell cell = new Listcell();
        Label lbl = new Label(label != null ? label : "");
        lbl.setSclass(sclass);
        cell.appendChild(lbl);
        return cell;
    }

    private String formatCurrencyToIndianRupees(BigDecimal amt) {
        if (amt == null) return "₹ 0";
        try {
            return INR.format(amt);
        } catch (Exception ex) {
            return "₹ " + amt.toPlainString();
        }
    }

    private String formatLocalDateTimeToString(LocalDateTime dt) {
        return dt != null ? dt.format(DT_FMT) : "-";
    }

    private String truncateStringWithEllipsis(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private String retrieveCssClassForStatusPill(String status) {
        if (status == null) return "batch-pill batch-pill-gray";
        BatchStatus bs = BatchStatus.fromDb(status);
        if (bs == BatchStatus.CXF_CIBF_GENERATED) {
            return "batch-pill batch-pill-green";
        }
        if (bs == BatchStatus.VERIFIED || "FILE_GENERATION_PENDING".equals(status) || "VER2_DONE".equals(status)) {
            return "batch-pill batch-pill-amber";
        }
        return "batch-pill batch-pill-gray";
    }

    private String formatStatusStringToFriendlyLabel(String status) {
        if (status == null) return "";
        BatchStatus bs = BatchStatus.fromDb(status);
        if (bs == BatchStatus.CXF_CIBF_GENERATED) {
            return "CXF-CIBF generated";
        }
        if (bs == BatchStatus.VERIFIED || "FILE_GENERATION_PENDING".equals(status) || "VER2_DONE".equals(status)) {
            return "Ready for file generation.";
        }
        return status;
    }

    private void displayErrorMessageBox(String msg) {
        try {
            Messagebox.show(msg, "Generation Error", Messagebox.OK, Messagebox.ERROR);
        } catch (Exception ignore) {
        }
    }

    private void displaySuccessMessageBox(long count, List<CxfFileResult> results) {
        this.lastResults = results;
        copyGeneratedZipFilesToProjectDirectory(results);

        if (winSuccessDialog != null) {
            if (lblBatchCount != null) {
                lblBatchCount.setValue(count + (count == 1 ? " batch" : " batches") + " processed successfully");
            }

            try {
                Button btnView = (Button) winSuccessDialog.getFellow("btnViewFile");
                btnView.removeEventListener("onClick", viewFileListener);
                btnView.addEventListener("onClick", viewFileListener);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Failed to bind btnViewFile listener", ex);
            }

            try {
                Button btnDone = (Button) winSuccessDialog.getFellow("btnDone");
                btnDone.removeEventListener("onClick", doneListener);
                btnDone.addEventListener("onClick", doneListener);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Failed to bind btnDone listener", ex);
            }

            winSuccessDialog.setVisible(true);
            winSuccessDialog.doModal();
        } else {
            // Fallback to standard messagebox if winSuccessDialog is not wired/available
            String msg = count + " batch" + (count == 1 ? "" : "es") +
                    " processed successfully.\n\nCXF & CIBF file sent to NPCI successfully.";
            try {
                Messagebox.show(msg, "Generation Successful", Messagebox.OK, Messagebox.INFORMATION);
            } catch (Exception ignored) {}
        }
    }

    public void onClickViewFile() {
        if (lastResults == null || lastResults.isEmpty()) {
            return;
        }

        boolean opened = false;
        for (CxfFileResult fileResult : lastResults) {
            if (!fileResult.isSuccess() || fileResult.getZipFilePaths() == null) {
                continue;
            }

            for (String path : fileResult.getZipFilePaths()) {
                if (path == null) continue;
                File zipFile = new File(path);
                if (!zipFile.exists()) {
                    continue;
                }

                // Point out to zip folder location locally if running on Windows
                if (!opened) {
                    try {
                        String os = System.getProperty("os.name").toLowerCase();
                        if (os.contains("win")) {
                            String cmd = "explorer.exe /select,\"" + zipFile.getAbsolutePath().replace("/", "\\") + "\"";
                            Runtime.getRuntime().exec(cmd);
                            opened = true;
                        }
                    } catch (Exception ex) {
                        LOG.log(Level.WARNING, "Failed to open explorer for " + zipFile.getAbsolutePath(), ex);
                    }
                }
            }
        }
    }

    public void onClickDone() {
        if (winSuccessDialog != null) {
            winSuccessDialog.setVisible(false);
        }
    }

    private void copyGeneratedZipFilesToProjectDirectory(List<CxfFileResult> results) {
        try {
            String webAppPath = Executions.getCurrent().getDesktop().getWebApp().getRealPath("/");
            File projectRoot = null;
            if (webAppPath != null) {
                File dir = new File(webAppPath);
                while (dir != null) {
                    if (new File(dir, "pom.xml").exists()) {
                        projectRoot = dir;
                        break;
                    }
                    dir = dir.getParentFile();
                }
            }
            if (projectRoot == null) {
                File userDir = new File(System.getProperty("user.dir"));
                while (userDir != null) {
                    if (new File(userDir, "pom.xml").exists()) {
                        projectRoot = userDir;
                        break;
                    }
                    userDir = userDir.getParentFile();
                }
            }
            if (projectRoot == null) {
                projectRoot = new File("c:/Users/C PAVAN KUMAR/Music/navbharat");
            }

            File outwardDir = new File(projectRoot, "outward-generated-files");
            if (!outwardDir.exists()) {
                outwardDir.mkdirs();
            }

            for (CxfFileResult fileResult : results) {
                if (!fileResult.isSuccess() || fileResult.getZipFilePaths() == null || fileResult.getZipFilePaths().isEmpty()) {
                    continue;
                }

                for (String zipPathStr : fileResult.getZipFilePaths()) {
                    if (zipPathStr == null) continue;
                    File zipFile = new File(zipPathStr);
                    if (!zipFile.exists()) {
                        LOG.warning("ZIP file not found, skipping: " + zipFile.getAbsolutePath());
                        continue;
                    }

                    File destFile = new File(outwardDir, zipFile.getName());
                    Files.copy(zipFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Copied ZIP to project reference folder: " + destFile.getAbsolutePath());
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Failed to copy generated files to project reference folder", ex);
        }
    }

    private LocalDateTime getDynamicDate(CxfBatchDTO batchDTO) {
        String status = batchDTO.getStatus();
        if (status == null) return batchDTO.getCreatedAt();
        BatchStatus bs = BatchStatus.fromDb(status);
        if (bs == BatchStatus.CXF_CIBF_GENERATED) {
            return batchDTO.getGeneratedAt() != null ? batchDTO.getGeneratedAt() : batchDTO.getCreatedAt();
        }
        return batchDTO.getCreatedAt();
    }

    private boolean isSameDate(LocalDateTime ldt, java.util.Date date) {
        if (ldt == null || date == null) return false;
        java.time.LocalDate ld1 = ldt.toLocalDate();
        java.time.LocalDate ld2 = date.toInstant()
                                      .atZone(java.time.ZoneId.systemDefault())
                                      .toLocalDate();
        return ld1.equals(ld2);
    }
}

// created by Pavan Kumar C on 17-06-2024