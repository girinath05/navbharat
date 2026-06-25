package com.cts.outward.composer;

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
import org.zkoss.zk.ui.event.InputEvent;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final NumberFormat RUPEE_FORMATTER = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));

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

    private String currentStatusFilter = STATUS_PENDING;
    private String searchBatchId = "";
    private java.util.Date filterFromDate = null;
    private java.util.Date filterToDate = null;
    private Checkbox chkSelectAllHeader;

    /**
     * Initializes components and loads initial data after ZK composition.
     *
     * @param component the component that was composed
     * @throws Exception if post-composition setup fails
     */
    @Override
    public void doAfterCompose(Component component) throws Exception {
        super.doAfterCompose(component);
        service = new CxfCibfServiceImpl();

        // Set default selection
        cmbStatus.setSelectedIndex(2);
        currentStatusFilter = STATUS_ALL;

        updateTableLabels();
        rebuildTableColumns(currentStatusFilter);
        refreshPageData();
    }

    /**
     * Fetches fresh listbox records and recalculates statistics based on filters.
     */
    private void refreshPageData() {
        try {
            // 1. Fetch raw data via service layer (Composer must never call DAO directly)
            List<CxfBatchDTO> rawPending = service.findPendingBatches();
            List<CxfBatchDTO> rawCompleted = service.findCompletedBatches();

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
            List<CxfBatchDTO> filtered = new ArrayList<>();
            for (CxfBatchDTO batch : activeRawList) {
                if (matchesFilters(batch)) {
                    filtered.add(batch);
                }
            }

            // 3. Visibility
            updateActionButtonsVisibility();

            // 4. Render
            populateDynamicTable(filtered);

            // 5. Pagination
            refreshPagingWidgets();

        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "refreshPageData failed", exception);
            displayErrorMessageBox("Failed to load data: " + exception.getMessage());
        }
    }

    /**
     * Evaluates a batch record against active query filters.
     *
     * @param batch the batch record to evaluate
     * @return true if batch matches search criteria; false otherwise
     */
    private boolean matchesFilters(CxfBatchDTO batch) {
        if (!searchBatchId.isEmpty() && !batch.getBatchId().toLowerCase().contains(searchBatchId.toLowerCase())) {
            return false;
        }
        LocalDateTime checkDateTime = getDynamicDate(batch);
        if (checkDateTime != null) {
            java.time.LocalDate checkLocalDate = checkDateTime.toLocalDate();
            if (filterFromDate != null) {
                java.time.LocalDate fromLocalDate = filterFromDate.toInstant()
                                              .atZone(java.time.ZoneId.systemDefault())
                                              .toLocalDate();
                if (checkLocalDate.isBefore(fromLocalDate)) {
                    return false;
                }
            }
            if (filterToDate != null) {
                java.time.LocalDate toLocalDate = filterToDate.toInstant()
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDate();
                if (checkLocalDate.isAfter(toLocalDate)) {
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

    /**
     * Updates visibility configurations of generation action buttons.
     */
    private void updateActionButtonsVisibility() {
        btnGenerateCxf.setVisible(true);
    }

    /**
     * Toggles generation button state according to dynamic grid selection count.
     */
    private void updateActionButtonsEnablementState() {
        boolean hasSelection = !lbDynamic.getSelectedItems().isEmpty();
        boolean isPendingView = STATUS_PENDING.equals(currentStatusFilter);
        btnGenerateCxf.setDisabled(!(isPendingView && hasSelection));
    }

    /**
     * Updates content header labels matching selected view filter statuses.
     */
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

    /**
     * Rebuilds list header headers according to status filters.
     *
     * @param statusFilter active status filter string
     */
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

    /**
     * Appends a new header column onto the dynamic header structure.
     *
     * @param label the text header column title label
     */
    private void addHeader(String label) {
        Listheader listHeader = new Listheader(label);
        listHeader.setSclass("v1-lh");
        listHeader.setAlign("center");
        listHeader.setHflex("1");
        lhDynamic.appendChild(listHeader);
    }

    /**
     * Generates a multi-select master header checkbox for checker actions.
     */
    private void addBatchIdCheckboxHeader() {
        Listheader lhBatchId = new Listheader();
        lhBatchId.setSclass("v1-lh");
        lhBatchId.setAlign("center");
        lhBatchId.setHflex("1");

        Div containerDiv = new Div();
        containerDiv.setSclass("cxf-header-checkbox-container");

        Checkbox selectAll = new Checkbox();
        selectAll.setTooltiptext("Select / Deselect All");
        selectAll.setSclass("cxf-header-checkbox");
        selectAll.addEventListener("onCheck", event -> {
            selectAllRows(selectAll.isChecked());
        });
        chkSelectAllHeader = selectAll;

        Label batchIdLabel = new Label("BATCH ID");
        batchIdLabel.setStyle("font-weight:700;color:#CBD5E1;letter-spacing:0.06em;");

        containerDiv.appendChild(selectAll);
        containerDiv.appendChild(batchIdLabel);
        lhBatchId.appendChild(containerDiv);
        lhDynamic.appendChild(lhBatchId);
    }

    /**
     * Selects or deselects all batch records inside the dynamic table view.
     *
     * @param checked true to select all items; false to deselect
     */
    private void selectAllRows(boolean checked) {
        for (Listitem item : lbDynamic.getItems()) {
            if (isCheckable(item)) {
                item.setSelected(checked);
                if (!item.getChildren().isEmpty()) {
                    Component cell = item.getChildren().get(0);
                    if (!cell.getChildren().isEmpty()) {
                        Component childDiv = cell.getChildren().get(0);
                        if (childDiv instanceof Div && !childDiv.getChildren().isEmpty()) {
                            Component checkBoxComponent = childDiv.getChildren().get(0);
                            if (checkBoxComponent instanceof Checkbox) {
                                ((Checkbox) checkBoxComponent).setChecked(checked);
                            }
                        }
                    }
                }
            } else {
                item.setSelected(false);
                if (!item.getChildren().isEmpty()) {
                    Component cell = item.getChildren().get(0);
                    if (!cell.getChildren().isEmpty()) {
                        Component childDiv = cell.getChildren().get(0);
                        if (childDiv instanceof Div && !childDiv.getChildren().isEmpty()) {
                            Component checkBoxComponent = childDiv.getChildren().get(0);
                            if (checkBoxComponent instanceof Checkbox) {
                                ((Checkbox) checkBoxComponent).setChecked(false);
                            }
                        }
                    }
                }
            }
        }
        updateActionButtonsEnablementState();
    }

    /**
     * Binds query results lists dynamically onto grid row components.
     *
     * @param list data list of CxfBatchDTO items to display
     */
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
                    row.appendChild(createListcellWithStatusPill("Ready for generation.", "batch-pill batch-pill-amber"));
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

    /**
     * Builds list cell layout containing checkbox and ID labels.
     *
     * @param batchId   unique ID reference of the batch
     * @param row       container Listitem row target
     * @param checkable true if interactive selection is allowed; false otherwise
     * @return populated Listcell component containing interactive checkbox wrapper
     */
    private Listcell createCheckboxAndIdCell(String batchId, Listitem row, boolean checkable) {
        Listcell cell = new Listcell();
        Div cellDiv = new Div();
        cellDiv.setSclass("cxf-cell-checkbox-container");

        Checkbox rowCheckbox = new Checkbox();
        rowCheckbox.setSclass("cxf-cell-checkbox");
        rowCheckbox.setDisabled(!checkable);
        if (checkable) {
            rowCheckbox.addEventListener("onCheck", event -> {
                row.setSelected(rowCheckbox.isChecked());
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

        Label label = new Label(batchId);
        label.setStyle("font-weight:700;color:#1B2E4B;white-space:nowrap;");

        cellDiv.appendChild(rowCheckbox);
        cellDiv.appendChild(label);
        cell.appendChild(cellDiv);
        return cell;
    }

    /**
     * Event listener managing selections manually inside the grid rows.
     */
    @Listen("onSelect = #lbDynamic")
    public void onDynamicTableSelectionChanged() {
        for (Listitem item : lbDynamic.getItems()) {
            if (!item.getChildren().isEmpty()) {
                Component cell = item.getChildren().get(0);
                if (!cell.getChildren().isEmpty()) {
                    Component childDiv = cell.getChildren().get(0);
                    if (childDiv instanceof Div && !childDiv.getChildren().isEmpty()) {
                        Component checkBoxComponent = childDiv.getChildren().get(0);
                        if (checkBoxComponent instanceof Checkbox) {
                            Checkbox rowCheckbox = (Checkbox) checkBoxComponent;
                            if (rowCheckbox.isDisabled()) {
                                item.setSelected(false);
                                rowCheckbox.setChecked(false);
                            } else {
                                rowCheckbox.setChecked(item.isSelected());
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

    /**
     * Determines whether the listitem is open for Checker verification inputs.
     *
     * @param item grid row listitem element
     * @return true if row is open for checkbox select actions; false otherwise
     */
    private boolean isCheckable(Listitem item) {
        if (item.getChildren().isEmpty()) return false;
        Component cell = item.getChildren().get(0);
        if (cell.getChildren().isEmpty()) return false;
        Component childDiv = cell.getChildren().get(0);
        if (childDiv instanceof Div && !childDiv.getChildren().isEmpty()) {
            Component checkBoxComponent = childDiv.getChildren().get(0);
            if (checkBoxComponent instanceof Checkbox) {
                return !((Checkbox) checkBoxComponent).isDisabled();
            }
        }
        return false;
    }

    /**
     * Listens to status combobox changes to rebuild dynamic grid layouts.
     */
    @Listen("onSelect = #cmbStatus")
    public void onStatusChanged() {
        Comboitem selectedItem = cmbStatus.getSelectedItem();
        currentStatusFilter = selectedItem != null ? (String) selectedItem.getValue() : STATUS_PENDING;

        updateTableLabels();
        rebuildTableColumns(currentStatusFilter);
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    /**
     * Refreshes grids dynamically upon key entry filters or date resets.
     */
    @Listen("onChange = #txtSearchBatchId; onChange = #dbFromDate; onChange = #dbToDate")
    public void onFilterChanged() {
        searchBatchId = txtSearchBatchId.getValue().trim();
        filterFromDate = dbFromDate.getValue();
        filterToDate = dbToDate.getValue();
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    /**
     * Performs instant lookup filtering upon keypress input events.
     *
     * @param event the keyboard input event
     */
    @Listen("onChanging = #txtSearchBatchId")
    public void onSearchChanging(InputEvent event) {
        searchBatchId = event.getValue() != null ? event.getValue().trim() : "";
        pgDynamic.setActivePage(0);
        refreshPageData();
    }

    /**
     * Resets active search filter parameters back to original states.
     */
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

    /**
     * Re-renders pagination offsets dynamically on ZK paging events.
     */
    @Listen("onPaging = #pgDynamic")
    public void onDynamicPaging() {
        refreshPagingWidgets();
    }

    /**
     * Directs active page view offset backward by one layout interval.
     */
    @Listen("onClick = #btnPrev")
    public void onPrevClicked() {
        int page = pgDynamic.getActivePage();
        if (page > 0) {
            pgDynamic.setActivePage(page - 1);
            refreshPagingWidgets();
        }
    }

    /**
     * Directs active page view offset forward by one layout interval.
     */
    @Listen("onClick = #btnNext")
    public void onNextClicked() {
        int page = pgDynamic.getActivePage();
        if (page < pgDynamic.getPageCount() - 1) {
            pgDynamic.setActivePage(page + 1);
            refreshPagingWidgets();
        }
    }

    /**
     * Synchronizes paging labels and button disablement states.
     */
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

    /**
     * Generates ONE combined CXF and CIBF output for ALL selected batches.
     * All cheques from every selected batch are pooled into a single generation
     * run, producing one ZIP archive (or multiple ZIPs only if the combined
     * cheque count exceeds MAX_CHEQUES_PER_FILE). All selected batch DB records
     * are marked CXF_GENERATED upon success.
     */
    @Listen("onClick = #btnGenerateCxf")
    public void onClickGenerateCxfAndCibfButton() {
        List<String> selectedIds = new ArrayList<>();
        for (Listitem item : lbDynamic.getSelectedItems()) {
            selectedIds.add((String) item.getValue());
        }
        if (selectedIds.isEmpty()) return;

        // Single combined call — all selected batches → one CXF/CIBF generation
        CxfFileResult result;
        try {
            result = service.generateForBatches(selectedIds);
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "generateForBatches error", exception);
            displayErrorMessageBox("Generation failed: " + exception.getMessage());
            return;
        }

        refreshPageData();

        if (result.isSuccess()) {
            List<CxfFileResult> results = new ArrayList<>();
            results.add(result);
            displaySuccessMessageBox(selectedIds.size(), results);
        } else {
            displayErrorMessageBox("Generation failed:\n" + result.getErrorMessage());
        }
    }

    /**
     * Formats the display date adding context suffix labels.
     *
     * @param batchDTO target metadata DTO
     * @return formatted date string value with description tag
     */
    private String formatLocalDateTimeWithSuffix(CxfBatchDTO batchDTO) {
        LocalDateTime dateTime = getDynamicDate(batchDTO);
        if (dateTime == null) return "-";
        
        String status = batchDTO.getStatus();
        return dateTime.format(DATE_TIME_FORMATTER) + getDynamicDateSuffix(status);
    }

    /**
     * Gets a suffix labeling tag according to status.
     *
     * @param status batch database status
     * @return descriptor suffix tag
     */
    private String getDynamicDateSuffix(String status) {
        if (status == null) return " (Created)";
        BatchStatus batchStatus = BatchStatus.fromDb(status);
        if (batchStatus == BatchStatus.CXF_CIBF_GENERATED) {
            return " (Gen)";
        }
        return " (Created)";
    }

    /**
     * Appends a normal text list cell onto the listitem.
     *
     * @param row   the listitem row container
     * @param value the string cell value to assign
     * @param style optional inline styles
     */
    private void appendListcellWithTextAndStyle(Listitem row, String value, String style) {
        Listcell cell = new Listcell(value != null ? value : "");
        if (style != null) cell.setStyle(style);
        row.appendChild(cell);
    }

    /**
     * Appends a truncated file name cell to the listitem row.
     * Full value is preserved as a tooltip for hover visibility.
     *
     * @param row   the listitem row container
     * @param value the full file name string to display
     */
    private void appendLongValueCell(Listitem row, String value) {
        Listcell cell = new Listcell(truncateFileName(value));
        if (value != null && !value.isEmpty()) {
            cell.setTooltiptext(value);
        }
        row.appendChild(cell);
    }

    /**
     * Truncates a file name for compact display inside a listcell.
     * <p>
     * CXF/CIBF file names are cut after the 2nd underscore followed by "....":
     *   e.g. "CXF_560765000_25062026_113ABC" → "CXF_560765000_...."
     *   e.g. "CIBF_560765000_25062026_111XY" → "CIBF_560765000_...."
     * All other strings longer than 14 characters are truncated with "....".
     * Null or empty values return an em-dash placeholder.
     *
     * @param value the raw file name string
     * @return truncated display string
     */
    private String truncateFileName(String value) {
        if (value == null || value.isEmpty()) return "—";
        if (value.startsWith("CXF_") || value.startsWith("CIBF_")) {
            int secondUnderscore = value.indexOf('_', value.indexOf('_') + 1);
            return secondUnderscore != -1 ? value.substring(0, secondUnderscore) + "...." : value;
        }
        return value.length() > 10 ? value.substring(0, 10) + "...." : value;
    }
    /**
     * Generates a status pill wrapper component.
     *
     * @param label  status message text
     * @param sclass style class name
     * @return Listcell containing styled label chip
     */
    private Listcell createListcellWithStatusPill(String label, String styleClass) {
        Listcell cell = new Listcell();
        Label statusLabel = new Label(label != null ? label : "");
        statusLabel.setSclass(styleClass);
        cell.appendChild(statusLabel);
        return cell;
    }

    /**
     * Formats decimal currency values into short Indian Rupees format.
     *
     * @param amount the target currency amount
     * @return formatted currency display string
     */
    private String formatCurrencyToIndianRupees(BigDecimal amount) {
        if (amount == null) return "₹ 0";
        try {
            return RUPEE_FORMATTER.format(amount);
        } catch (Exception exception) {
            return "₹ " + amount.toPlainString();
        }
    }

    /**
     * Safely formats LocalDateTime into target patterns.
     *
     * @param dateTime local timestamp
     * @return formatted display date
     */
    private String formatLocalDateTimeToString(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATE_TIME_FORMATTER) : "-";
    }

    /**
     * Limits string lengths with ellipsis overflow.
     *
     * @param text      target string reference
     * @param maxLength maximum allowed characters
     * @return truncated result ending in ellipsis if length exceeded
     */
    private String truncateStringWithEllipsis(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text;
    }

    /**
     * Retrieves CSS style class selectors according to batch status.
     *
     * @param status the batch status value
     * @return css class name
     */
    private String retrieveCssClassForStatusPill(String status) {
        if (status == null) return "batch-pill batch-pill-gray";
        BatchStatus batchStatus = BatchStatus.fromDb(status);
        if (batchStatus == BatchStatus.CXF_CIBF_GENERATED) {
            return "batch-pill batch-pill-green";
        }
        if (batchStatus == BatchStatus.VERIFIED || "FILE_GENERATION_PENDING".equals(status) || "VER2_DONE".equals(status)) {
            return "batch-pill batch-pill-amber";
        }
        return "batch-pill batch-pill-gray";
    }

    /**
     * Parses raw status constants to user-friendly titles.
     *
     * @param status batch status constant
     * @return friendly text status
     */
    private String formatStatusStringToFriendlyLabel(String status) {
        if (status == null) return "";
        BatchStatus batchStatus = BatchStatus.fromDb(status);
        if (batchStatus == BatchStatus.CXF_CIBF_GENERATED) {
            return "CXF-CIBF generated";
        }
        if (batchStatus == BatchStatus.VERIFIED || "FILE_GENERATION_PENDING".equals(status) || "VER2_DONE".equals(status)) {
            return "Ready for generation.";
        }
        return status;
    }

    /**
     * Renders standard error modal dialog boxes.
     *
     * @param message target message label text
     */
    private void displayErrorMessageBox(String message) {
        try {
            Messagebox.show(message, "Generation Error", Messagebox.OK, Messagebox.ERROR);
        } catch (Exception ignore) {
        }
    }

    /**
     * Triggers generation completion confirmation popup.
     *
     * @param count   count of processed batches
     * @param results execution outcomes
     */
    private void displaySuccessMessageBox(long count, List<CxfFileResult> results) {
        this.lastResults = results;
        copyGeneratedZipFilesToProjectDirectory(results);

        if (winSuccessDialog != null) {
            if (lblBatchCount != null) {
                lblBatchCount.setValue(count + (count == 1 ? " batch" : " batches") + " processed successfully");
            }

            try {
                Button viewFileButton = (Button) winSuccessDialog.getFellow("btnViewFile");
                viewFileButton.removeEventListener("onClick", viewFileListener);
                viewFileButton.addEventListener("onClick", viewFileListener);
            } catch (Exception exception) {
                LOG.log(Level.WARNING, "Failed to bind btnViewFile listener", exception);
            }

            try {
                Button doneButton = (Button) winSuccessDialog.getFellow("btnDone");
                doneButton.removeEventListener("onClick", doneListener);
                doneButton.addEventListener("onClick", doneListener);
            } catch (Exception exception) {
                LOG.log(Level.WARNING, "Failed to bind btnDone listener", exception);
            }

            winSuccessDialog.setVisible(true);
            winSuccessDialog.doModal();
        } else {
            // Fallback to standard messagebox if winSuccessDialog is not wired/available
            String message = count + " batch" + (count == 1 ? "" : "es") +
                    " processed successfully.\n\nCXF & CIBF file sent to NPCI successfully.";
            try {
                Messagebox.show(message, "Generation Successful", Messagebox.OK, Messagebox.INFORMATION);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Resolves the outward-generated-files path using NIO.
     */
    private Path getOutwardGeneratedFilesPath() {
        try {
            String webAppPath = Executions.getCurrent().getDesktop().getWebApp().getRealPath("/");
            Path projectRoot = null;
            if (webAppPath != null) {
                Path directory = Paths.get(webAppPath);
                while (directory != null) {
                    if (Files.exists(directory.resolve("pom.xml"))) {
                        projectRoot = directory;
                        break;
                    }
                    directory = directory.getParent();
                }
            }
            if (projectRoot == null) {
                Path userDir = Paths.get(System.getProperty("user.dir"));
                while (userDir != null) {
                    if (Files.exists(userDir.resolve("pom.xml"))) {
                        projectRoot = userDir;
                        break;
                    }
                    userDir = userDir.getParent();
                }
            }
            if (projectRoot == null) {
                projectRoot = Paths.get(System.getProperty("user.home"), "navbharat");
            }
            return projectRoot.resolve("outward-generated-files");
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "Failed to resolve outward-generated-files path using NIO", exception);
            return Paths.get(System.getProperty("user.home"), "navbharat", "outward-generated-files");
        }
    }

    /**
     * Navigates to/opens the folder in the operating system's native file explorer.
     */
    private void openFolderInSystemExplorer(Path path) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(path.toFile());
                LOG.info("Opened directory using java.awt.Desktop: " + path.toAbsolutePath());
                return;
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "java.awt.Desktop.open failed, trying process builder fallback", exception);
        }

        try {
            String os = System.getProperty("os.name").toLowerCase();
            String pathStr = path.toAbsolutePath().toString();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", pathStr).start();
                LOG.info("Opened directory using explorer.exe: " + pathStr);
            } else if (os.contains("nix") || os.contains("nux")) {
                new ProcessBuilder("xdg-open", pathStr).start();
                LOG.info("Opened directory using xdg-open: " + pathStr);
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", pathStr).start();
                LOG.info("Opened directory using open command: " + pathStr);
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "Failed to navigate to folder in explorer", exception);
        }
    }

    /**
     * Points out the generated zip folder location and opens it in the system file explorer.
     */
    public void onClickViewFile() {
        try {
            Path outwardDir = getOutwardGeneratedFilesPath();
            
            // Open the directory in the OS file explorer
            openFolderInSystemExplorer(outwardDir);

            String folderPath = outwardDir.toAbsolutePath().toString();
            StringBuilder sb = new StringBuilder();
            sb.append("The generated ZIP file(s) have been saved to the following location:\n\n");
            sb.append(folderPath);
            sb.append("\n\n");

            boolean foundFiles = false;
            if (lastResults != null) {
                for (CxfFileResult fileResult : lastResults) {
                    if (!fileResult.isSuccess() || fileResult.getZipFilePaths() == null) {
                        continue;
                    }
                    for (String path : fileResult.getZipFilePaths()) {
                        if (path == null) continue;
                        Path zipPath = Paths.get(path);
                        sb.append("• ").append(zipPath.getFileName().toString()).append("\n");
                        foundFiles = true;
                    }
                }
            }

            if (!foundFiles) {
                sb.append("(No active generated ZIP files found)");
            }

            Messagebox.show(sb.toString(), "ZIP File Location", Messagebox.OK, Messagebox.INFORMATION);
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "Failed to display ZIP folder location", exception);
        }
    }

    /**
     * Hides success visual dialog overlays.
     */
    public void onClickDone() {
        if (winSuccessDialog != null) {
            winSuccessDialog.setVisible(false);
        }
    }

    /**
     * Copies outputs locally into developer target folders.
     *
     * @param results outcomes list
     */
    private void copyGeneratedZipFilesToProjectDirectory(List<CxfFileResult> results) {
        try {
            Path outwardDir = getOutwardGeneratedFilesPath();
            if (!Files.exists(outwardDir)) {
                Files.createDirectories(outwardDir);
            }

            for (CxfFileResult fileResult : results) {
                if (!fileResult.isSuccess() || fileResult.getZipFilePaths() == null || fileResult.getZipFilePaths().isEmpty()) {
                    continue;
                }

                for (String zipPathStr : fileResult.getZipFilePaths()) {
                    if (zipPathStr == null) continue;
                    Path zipFile = Paths.get(zipPathStr);
                    if (!Files.exists(zipFile)) {
                        LOG.warning("ZIP file not found, skipping: " + zipFile.toAbsolutePath());
                        continue;
                    }

                    Path destFile = outwardDir.resolve(zipFile.getFileName());
                    Files.copy(zipFile, destFile, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Copied ZIP to project reference folder using NIO: " + destFile.toAbsolutePath());
                }
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "Failed to copy generated files to project reference folder using NIO", exception);
        }
    }

    /**
     * Resolves matching timestamp according to active batch status.
     *
     * @param batchDTO the batch data transfer item
     * @return dynamic local date reference
     */
    private LocalDateTime getDynamicDate(CxfBatchDTO batchDTO) {
        String status = batchDTO.getStatus();
        if (status == null) return batchDTO.getCreatedAt();
        BatchStatus batchStatus = BatchStatus.fromDb(status);
        if (batchStatus == BatchStatus.CXF_CIBF_GENERATED) {
            return batchDTO.getGeneratedAt() != null ? batchDTO.getGeneratedAt() : batchDTO.getCreatedAt();
        }
        return batchDTO.getCreatedAt();
    }

    /**
     * Compares date boundaries.
     *
     * @param localDateTime local date time reference
     * @param date          legacy date bounds
     * @return true if dates fall on the same day; false otherwise
     */
    private boolean isSameDate(LocalDateTime localDateTime, java.util.Date date) {
        if (localDateTime == null || date == null) return false;
        java.time.LocalDate localDate1 = localDateTime.toLocalDate();
        java.time.LocalDate localDate2 = date.toInstant()
                                      .atZone(java.time.ZoneId.systemDefault())
                                      .toLocalDate();
        return localDate1.equals(localDate2);
    }
}