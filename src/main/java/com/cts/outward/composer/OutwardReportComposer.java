package com.cts.outward.composer;

import com.cts.composer.SidebarComposer;
import com.cts.outward.dto.ReportBatchDTO;
import com.cts.outward.dto.ReportChequeDTO;
import com.cts.outward.service.OutwardReportService;
import com.cts.outward.service.OutwardReportServiceImpl;
import com.cts.util.SecurityUtil;
import com.cts.outward.enums.BatchStatus;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.event.InputEvent;
import org.zkoss.zul.*;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for the Outward Reports view.
 */
public class OutwardReportComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(OutwardReportComposer.class.getName());

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final NumberFormat RUPEE_FORMATTER = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    @Wire("#lblPageSubtitle")
    private Label lblPageSubtitle;
    @Wire("#lblListCardTitle")
    private Label lblListCardTitle;
    @Wire("#btnExport")
    private Button btnExport;
    @Wire("#chkSelectAllCxf")
    private Checkbox chkSelectAllCxf;
    @Wire("#chkSelectAllBatch")
    private Checkbox chkSelectAllBatch;
    @Wire("#chkSelectAllCheque")
    private Checkbox chkSelectAllCheque;

    @Wire("#gridCxf")
    private Listbox lbCxf;
    @Wire("#gridBatch")
    private Listbox lbBatch;
    @Wire("#gridCheque")
    private Listbox lbCheque;

    @Wire("#fromDate")
    private Datebox fromDate;
    @Wire("#toDate")
    private Datebox toDate;
    @Wire("#batchIdFilter")
    private Textbox batchIdFilter;

    @Wire("#lblTotalBatches")
    private Label lblTotalBatches;
    @Wire("#lblTotalCheques")
    private Label lblTotalCheques;
    @Wire("#lblTotalAmount")
    private Label lblTotalAmount;

    private final OutwardReportService service = new OutwardReportServiceImpl();

    @Wire("#outwardReportsTimer")
    private Timer outwardReportsTimer;

    private String lastDbChecksum = "";
    private String activeReportTab = "CXF_REPORT";
    private Component rootComponent;
    private String currentBatchIdFilter = "";

    @Wire("#customPagingBar")
    private Div customPagingBar;
    @Wire("#lblPagingInfo")
    private Label lblPagingInfo;
    @Wire("#btnPagingPrev")
    private Button btnPagingPrev;
    @Wire("#lblPagingCurrentPage")
    private Label lblPagingCurrentPage;
    @Wire("#lblPagingText")
    private Label lblPagingText;
    @Wire("#btnPagingNext")
    private Button btnPagingNext;

    private List<?> currentFilteredList = new java.util.ArrayList<>();
    private int currentPage = 0;
    private static final int PAGE_SIZE = 5;

    /**
     * Initializes components and loads data after ZK composition.
     */
    @Override
    public void doAfterCompose(Component component) throws Exception {
        this.rootComponent = component;
        super.doAfterCompose(component);

        initDateFilters();
        service.migrateBatchStatuses();
        determineActiveReportTab();
        configureReportView();
        performSearch();
    }

    /**
     * Resets fromDate and toDate filter values to null.
     */
    private void initDateFilters() {
        if (fromDate != null) {
            fromDate.setValue(null);
        }
        if (toDate != null) {
            toDate.setValue(null);
        }
    }

    /**
     * Reads selection state from the user session.
     */
    private void determineActiveReportTab() {
        String sessionTabId = (String) Sessions.getCurrent().getAttribute("sidebar_report_tab");
        if (sessionTabId != null && !sessionTabId.trim().isEmpty()) {
            activeReportTab = sessionTabId;
        } else {
            activeReportTab = "CXF_REPORT";
        }
    }

    /**
     * Configures component visibility and titles based on the active report tab.
     */
    private void configureReportView() {
        if (lbCxf != null) {
            lbCxf.setVisible(false);
        }
        if (lbBatch != null) {
            lbBatch.setVisible(false);
        }
        if (lbCheque != null) {
            lbCheque.setVisible(false);
        }

        String cardTitle = "CXF-CIBF Reports";
        String infoSubtitle = "Outward Clearing — CXF & CIBF file generation logs";

        switch (activeReportTab) {
            case "BATCH_SUMMARY":
                if (lbBatch != null) {
                    lbBatch.setVisible(true);
                }
                cardTitle = "Batch Summary";
                infoSubtitle = "All outward clearing batches with lifecycle status";
                break;
            case "CHEQUE_REPORT":
                if (lbCheque != null) {
                    lbCheque.setVisible(true);
                }
                cardTitle = "Cheque-level Report";
                infoSubtitle = "Individual cheque details across all batches";
                break;
            case "CXF_REPORT":
            default:
                if (lbCxf != null) {
                    lbCxf.setVisible(true);
                }
                cardTitle = "CXF-CIBF Reports";
                infoSubtitle = "Outward Clearing — CXF & CIBF file generation logs";
                break;
        }

        lblListCardTitle.setValue(cardTitle);
        lblPageSubtitle.setValue(infoSubtitle);
    }

    /**
     * Periodically verifies checksum updates on timer refresh ticks.
     */
    @Listen("onTimer = #outwardReportsTimer")
    public void onTimerRefresh() {
        String currentChecksum = service.getDatabaseSyncChecksum();
        if (!currentChecksum.equals(lastDbChecksum)) {
            lastDbChecksum = currentChecksum;
            performSearch();
        }
    }

    /**
     * Triggers data reload on filter modifications.
     */
    @Listen("onChange = #fromDate; onChange = #toDate")
    public void onFilterChanged() {
        resetActivePages();
        performSearch();
    }

    /**
     * Updates batch filter string on inputs.
     */
    @Listen("onChange = #batchIdFilter")
    public void onBatchIdChange() {
        if (batchIdFilter != null && batchIdFilter.getValue() != null) {
            currentBatchIdFilter = batchIdFilter.getValue().trim();
        } else {
            currentBatchIdFilter = "";
        }
        resetActivePages();
        performSearch();
    }

    /**
     * Triggers real-time keystroke filtering updates.
     */
    @Listen("onChanging = #batchIdFilter")
    public void onBatchIdChanging(InputEvent inputEvent) {
        currentBatchIdFilter = inputEvent.getValue() != null ? inputEvent.getValue().trim() : "";
        resetActivePages();
        performSearch();
    }

    /**
     * Resets filter parameters to default empty values.
     */
    @Listen("onClick = #btnReset")
    public void onReset() {
        initDateFilters();
        currentBatchIdFilter = "";
        if (batchIdFilter != null) {
            batchIdFilter.setValue("");
        }
        resetActivePages();
        performSearch();
    }

    /**
     * Resets page navigation indexes to first page.
     */
    private void resetActivePages() {
        this.currentPage = 0;
    }

    /**
     * Reloads filtered lists and aggregates statistics.
     */
    private void performSearch() {
        lastDbChecksum = service.getDatabaseSyncChecksum();

        Date fromDateVal = fromDate.getValue();
        Date toDateVal = toDate.getValue();
        String filterBatchId = currentBatchIdFilter;

        LocalDate searchFromDate = fromDateVal != null ? new java.sql.Date(fromDateVal.getTime()).toLocalDate() : null;
        LocalDate searchToDate = toDateVal != null ? new java.sql.Date(toDateVal.getTime()).toLocalDate() : null;

        int totalBatchesCount = 0;
        int totalChequesCount = 0;
        BigDecimal grandTotalAmount = BigDecimal.ZERO;
        String amountRange = "ALL";

        try {
            List<ReportBatchDTO> batchList = service.getAllBatches();
            for (ReportBatchDTO batch : batchList) {
                if (matchesFilter(batch, searchFromDate, searchToDate, filterBatchId, false)) {
                    totalBatchesCount++;
                }
            }

            List<ReportChequeDTO> chequeList = service.getAllCheques();
            for (ReportChequeDTO cheque : chequeList) {
                if (matchesChequeFilter(cheque, searchFromDate, searchToDate, filterBatchId)
                        && matchesAmountFilter(cheque.getAmount(), amountRange)) {
                    totalChequesCount++;
                    if (cheque.getAmount() != null) {
                        grandTotalAmount = grandTotalAmount.add(cheque.getAmount());
                    }
                }
            }
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "Global stats calculation failed", exception);
        }

        List<Object> tempFilteredList = new java.util.ArrayList<>();

        switch (activeReportTab) {
            case "CXF_REPORT":
                try {
                    List<ReportBatchDTO> list = service.getGeneratedBatches();
                    for (ReportBatchDTO batch : list) {
                        if (matchesFilter(batch, searchFromDate, searchToDate, filterBatchId, true)) {
                            tempFilteredList.add(batch);
                        }
                    }
                } catch (Exception exception) {
                    LOG.log(Level.SEVERE, "performSearch CXF failed", exception);
                }
                break;

            case "BATCH_SUMMARY":
                try {
                    List<ReportBatchDTO> list = service.getAllBatches();
                    for (ReportBatchDTO batch : list) {
                        if (matchesFilter(batch, searchFromDate, searchToDate, filterBatchId, false)) {
                            tempFilteredList.add(batch);
                        }
                    }
                } catch (Exception exception) {
                    LOG.log(Level.SEVERE, "performSearch Batch Summary failed", exception);
                }
                break;

            case "CHEQUE_REPORT":
                try {
                    List<ReportChequeDTO> list = service.getAllCheques();
                    for (ReportChequeDTO cheque : list) {
                        if (matchesChequeFilter(cheque, searchFromDate, searchToDate, filterBatchId)
                                && matchesAmountFilter(cheque.getAmount(), amountRange)) {
                            tempFilteredList.add(cheque);
                        }
                    }
                } catch (Exception exception) {
                    LOG.log(Level.SEVERE, "performSearch Cheque failed", exception);
                }
                break;
        }

        this.currentFilteredList = tempFilteredList;
        int totalItems = currentFilteredList.size();
        int totalPages = (int) Math.ceil((double) totalItems / PAGE_SIZE);
        if (currentPage >= totalPages) {
            currentPage = 0;
        }
        renderCurrentPage();

        if (lblTotalBatches != null) {
            lblTotalBatches.setValue(String.valueOf(totalBatchesCount));
        }
        if (lblTotalCheques != null) {
            lblTotalCheques.setValue(String.valueOf(totalChequesCount));
        }
        if (lblTotalAmount != null) {
            lblTotalAmount.setValue(formatCurrencyToShortIndianRupees(grandTotalAmount));
        }
    }

    /**
     * Renders visual rows for the active page layout.
     */
    private void renderCurrentPage() {
        if (lbCxf != null) {
            lbCxf.getItems().clear();
        }
        if (lbBatch != null) {
            lbBatch.getItems().clear();
        }
        if (lbCheque != null) {
            lbCheque.getItems().clear();
        }

        if (chkSelectAllCxf != null) {
            chkSelectAllCxf.setChecked(false);
        }
        if (chkSelectAllBatch != null) {
            chkSelectAllBatch.setChecked(false);
        }
        if (chkSelectAllCheque != null) {
            chkSelectAllCheque.setChecked(false);
        }

        int totalItems = currentFilteredList.size();
        int totalPages = (int) Math.ceil((double) totalItems / PAGE_SIZE);
        if (totalPages == 0) {
            totalPages = 1;
        }

        if (currentPage < 0) {
            currentPage = 0;
        }
        if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }

        int startIndex = currentPage * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, totalItems);

        if (totalItems > 0) {
            for (int itemIndex = startIndex; itemIndex < endIndex; itemIndex++) {
                Object listItem = currentFilteredList.get(itemIndex);
                if ("CXF_REPORT".equals(activeReportTab)) {
                    ReportBatchDTO batch = (ReportBatchDTO) listItem;
                    Listitem row = new Listitem();
                    row.setValue(batch);
                    appendCheckboxOnlyCellToListItem(row, chkSelectAllCxf, lbCxf);
                    appendListcellWithTextAndStyle(row, batch.getBatchId(), "font-weight:700;color:#1B2E4B;");
                    appendListcellWithTextAndStyle(row, String.valueOf(batch.getTotalCheques()), null);
                    appendListcellWithTextAndStyle(row, formatCurrencyToIndianRupees(batch.getTotalAmount()),
                            "color:#166534;font-weight:600;");
                    appendLongValueCell(row, batch.getCxfFileName());
                    appendLongValueCell(row, batch.getCibfFileName());
                    appendListcellWithTextAndStyle(row, formatLocalDateTimeToString(batch.getGeneratedAt()), null);
                    row.appendChild(createListcellWithStatusPill(formatStatusStringToFriendlyLabel(batch.getStatus()),
                            retrieveCssClassForBatchStatusPill(batch.getStatus())));
                    lbCxf.appendChild(row);
                } else if ("BATCH_SUMMARY".equals(activeReportTab)) {
                    ReportBatchDTO batch = (ReportBatchDTO) listItem;
                    Listitem row = new Listitem();
                    row.setValue(batch);
                    appendCheckboxOnlyCellToListItem(row, chkSelectAllBatch, lbBatch);
                    appendListcellWithTextAndStyle(row, batch.getBatchId(), "font-weight:700;color:#1B2E4B;");
                    appendListcellWithTextAndStyle(row, String.valueOf(batch.getTotalCheques()), null);
                    appendListcellWithTextAndStyle(row, formatCurrencyToIndianRupees(batch.getTotalAmount()),
                            "color:#166534;font-weight:600;");
                    row.appendChild(createListcellWithStatusPill(formatStatusStringToFriendlyLabel(batch.getStatus()),
                            retrieveCssClassForBatchStatusPill(batch.getStatus())));
                    appendListcellWithTextAndStyle(row, formatLocalDateTimeToString(batch.getCreatedAt()), null);
                    lbBatch.appendChild(row);
                } else if ("CHEQUE_REPORT".equals(activeReportTab)) {
                    ReportChequeDTO cheque = (ReportChequeDTO) listItem;
                    Listitem row = new Listitem();
                    row.setValue(cheque);
                    appendCheckboxOnlyCellToListItem(row, chkSelectAllCheque, lbCheque);
                    appendListcellWithTextAndStyle(row, substituteNullWithPlaceholder(cheque.getChequeNo()),
                            "font-weight:600;");
                    appendListcellWithTextAndStyle(row, substituteNullWithPlaceholder(cheque.getBatchId()),
                            "font-weight:700;color:#1B2E4B;");
                    appendListcellWithTextAndStyle(row, substituteNullWithPlaceholder(cheque.getChequeDate()), null);
                    appendListcellWithTextAndStyle(row, formatCurrencyToIndianRupees(cheque.getAmount()),
                            "color:#166534;font-weight:600;");
                    row.appendChild(
                            createListcellWithStatusPill(formatChequeStatusStringToFriendlyLabel(cheque.getVerStatus()),
                                    retrieveCssClassForChequeStatusPill(cheque.getVerStatus())));
                    lbCheque.appendChild(row);
                }
            }
        }

        if (customPagingBar != null) {
            customPagingBar.setVisible(totalItems > 0);
        }

        String labelType = "records";
        if ("CHEQUE_REPORT".equals(activeReportTab)) {
            labelType = "cheques";
        } else if ("BATCH_SUMMARY".equals(activeReportTab)) {
            labelType = "batches";
        }

        int displayStart = totalItems == 0 ? 0 : (startIndex + 1);
        if (lblPagingInfo != null) {
            lblPagingInfo.setValue("Showing " + displayStart + "-" + endIndex + " of " + totalItems + " " + labelType);
        }

        if (lblPagingCurrentPage != null) {
            lblPagingCurrentPage.setValue(String.valueOf(currentPage + 1));
        }
        if (lblPagingText != null) {
            lblPagingText.setValue("of " + totalPages);
        }

        if (btnPagingPrev != null) {
            btnPagingPrev.setDisabled(currentPage == 0);
        }
        if (btnPagingNext != null) {
            btnPagingNext.setDisabled(currentPage >= totalPages - 1);
        }

        if (btnExport != null) {
            btnExport.setDisabled(true);
        }
    }

    /**
     * Steps active view offset backwards by one page.
     */
    @Listen("onClick = #btnPagingPrev")
    public void onPagingPrev() {
        if (currentPage > 0) {
            currentPage--;
            renderCurrentPage();
        }
    }

    /**
     * Steps active view offset forwards by one page.
     */
    @Listen("onClick = #btnPagingNext")
    public void onPagingNext() {
        int totalItems = currentFilteredList.size();
        int totalPages = (int) Math.ceil((double) totalItems / PAGE_SIZE);
        if (currentPage < totalPages - 1) {
            currentPage++;
            renderCurrentPage();
        }
    }

    /**
     * Matches batch properties against date and ID filter criteria.
     */
    private boolean matchesFilter(ReportBatchDTO batch, LocalDate fromLocalDate, LocalDate toLocalDate,
            String filterBatchId, boolean isGeneratedTab) {
        LocalDateTime timestamp = isGeneratedTab ? batch.getGeneratedAt() : batch.getCreatedAt();
        if (timestamp == null) {
            if (fromLocalDate != null || toLocalDate != null) {
                return false;
            }
        } else {
            LocalDate localDate = timestamp.toLocalDate();
            if (fromLocalDate != null && localDate.isBefore(fromLocalDate)) {
                return false;
            }
            if (toLocalDate != null && localDate.isAfter(toLocalDate)) {
                return false;
            }
        }

        if (filterBatchId != null && !filterBatchId.isEmpty()) {
            if (batch.getBatchId() == null || !batch.getBatchId().toLowerCase().contains(filterBatchId.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Matches cheque properties against date and ID filter criteria.
     */
    private boolean matchesChequeFilter(ReportChequeDTO cheque, LocalDate fromLocalDate, LocalDate toLocalDate,
            String filterBatchId) {
        LocalDate chequeDate = parseChequeDateStringToLocalDate(cheque.getChequeDate());
        if (chequeDate == null) {
            if (fromLocalDate != null || toLocalDate != null) {
                return false;
            }
        } else {
            if (fromLocalDate != null && chequeDate.isBefore(fromLocalDate)) {
                return false;
            }
            if (toLocalDate != null && chequeDate.isAfter(toLocalDate)) {
                return false;
            }
        }

        if (filterBatchId != null && !filterBatchId.isEmpty()) {
            if (cheque.getBatchId() == null
                    || !cheque.getBatchId().toLowerCase().contains(filterBatchId.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates financial limits against pre-selected target ranges.
     */
    private boolean matchesAmountFilter(BigDecimal amount, String range) {
        if (range == null || "ALL".equals(range)) {
            return true;
        }
        if (amount == null) {
            return false;
        }
        double amountDouble = amount.doubleValue();
        switch (range) {
            case "BELOW_50K":
                return amountDouble < 50000.0;
            case "50K_200K":
                return amountDouble >= 50000.0 && amountDouble < 200000.0;
            case "200K_10M":
                return amountDouble >= 200000.0 && amountDouble < 1000000.0;
            case "ABOVE_10M":
                return amountDouble >= 1000000.0;
            default:
                return true;
        }
    }

    /**
     * Parses raw cheque date strings safely into LocalDate objects.
     */
    private LocalDate parseChequeDateStringToLocalDate(String dateString) {
        if (dateString == null || dateString.isEmpty() || dateString.equals("—")) {
            return null;
        }
        try {
            return LocalDate.parse(dateString, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception exception) {
            try {
                return LocalDate.parse(dateString, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception nestedException) {
                try {
                    return LocalDate.parse(dateString, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } catch (Exception fallbackException) {
                    return null;
                }
            }
        }
    }

    /**
     * Synchronizes row checkboxes and trigger button visibility on select actions.
     */
    @Listen("onSelect = #gridCxf; onSelect = #gridBatch; onSelect = #gridCheque")
    public void onListboxSelect() {
        synchronizeRowCheckboxesWithSelectionState(retrieveActiveReportListbox(), retrieveActiveHeaderCheckbox());
        refreshExportButtonEnablementState();
    }

    /**
     * Handles CXF select all master checkbox events.
     */
    @Listen("onCheck = #chkSelectAllCxf")
    public void onCheckSelectAllCxf() {
        synchronizeHeaderSelectAllState(chkSelectAllCxf, lbCxf);
    }

    /**
     * Handles Batch select all master checkbox events.
     */
    @Listen("onCheck = #chkSelectAllBatch")
    public void onCheckSelectAllBatch() {
        synchronizeHeaderSelectAllState(chkSelectAllBatch, lbBatch);
    }

    /**
     * Handles Cheque select all master checkbox events.
     */
    @Listen("onCheck = #chkSelectAllCheque")
    public void onCheckSelectAllCheque() {
        synchronizeHeaderSelectAllState(chkSelectAllCheque, lbCheque);
    }

    /**
     * Sets selection state on sub-rows matching the master checkbox status.
     */
    private void synchronizeHeaderSelectAllState(Checkbox selectAll, Listbox listbox) {
        if (selectAll == null) {
            return;
        }
        boolean isChecked = selectAll.isChecked();
        for (Listitem item : listbox.getItems()) {
            item.setSelected(isChecked);
            Checkbox checkbox = extractCheckboxFromListItem(item);
            if (checkbox != null) {
                checkbox.setChecked(isChecked);
            }
        }
        refreshExportButtonEnablementState();
    }

    /**
     * Synchronizes master check status aligning with sub-row check counts.
     */
    private void synchronizeRowCheckboxesWithSelectionState(Listbox listbox, Checkbox selectAll) {
        if (listbox == null) {
            return;
        }
        for (Listitem item : listbox.getItems()) {
            Checkbox checkbox = extractCheckboxFromListItem(item);
            if (checkbox != null) {
                checkbox.setChecked(item.isSelected());
            }
        }
        int total = listbox.getItems().size();
        int selected = listbox.getSelectedItems().size();
        if (selectAll != null) {
            selectAll.setChecked(total > 0 && selected == total);
        }
    }

    /**
     * Returns header select checkbox matching active report tab context.
     */
    private Checkbox retrieveActiveHeaderCheckbox() {
        switch (activeReportTab) {
            case "BATCH_SUMMARY":
                return chkSelectAllBatch;
            case "CHEQUE_REPORT":
                return chkSelectAllCheque;
            case "CXF_REPORT":
            default:
                return chkSelectAllCxf;
        }
    }

    /**
     * Inspects listitem row children to locate check inputs.
     */
    private Checkbox extractCheckboxFromListItem(Listitem listitemComponent) {
        if (!listitemComponent.getChildren().isEmpty()) {
            Component firstCell = listitemComponent.getChildren().get(0);
            if (!firstCell.getChildren().isEmpty()) {
                Component cellFirstChild = firstCell.getChildren().get(0);
                if (cellFirstChild instanceof Checkbox) {
                    return (Checkbox) cellFirstChild;
                }
                if (cellFirstChild instanceof Div && !cellFirstChild.getChildren().isEmpty()) {
                    Component checkboxComponent = cellFirstChild.getChildren().get(0);
                    if (checkboxComponent instanceof Checkbox) {
                        return (Checkbox) checkboxComponent;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Appends list selection checkboxes to visual row formats.
     */
    private void appendCheckboxOnlyCellToListItem(Listitem row, Checkbox chkSelectAll, Listbox listbox) {
        Listcell listcell = new Listcell();
        Checkbox checkbox = new Checkbox();
        checkbox.addEventListener("onCheck", event -> {
            row.setSelected(checkbox.isChecked());
            refreshExportButtonEnablementState();

            int total = listbox.getItems().size();
            int selected = listbox.getSelectedItems().size();
            if (chkSelectAll != null) {
                chkSelectAll.setChecked(total > 0 && selected == total);
            }
        });
        listcell.appendChild(checkbox);
        row.appendChild(listcell);
    }

    /**
     * Toggles export download triggers active matching selection states.
     */
    private void refreshExportButtonEnablementState() {
        Listbox activeListbox = retrieveActiveReportListbox();
        boolean hasSelection = activeListbox != null && activeListbox.getSelectedCount() > 0;
        if (btnExport != null) {
            btnExport.setDisabled(!hasSelection);
        }
    }

    /**
     * Returns active view Listbox component.
     */
    private Listbox retrieveActiveReportListbox() {
        switch (activeReportTab) {
            case "BATCH_SUMMARY":
                return lbBatch;
            case "CHEQUE_REPORT":
                return lbCheque;
            case "CXF_REPORT":
            default:
                return lbCxf;
        }
    }

    /**
     * Triggers export matching grid parameters.
     */
    @Listen("onClick = #btnExport")
    public void onExport() {
        Listbox activeListbox = retrieveActiveReportListbox();
        if (activeListbox == null || activeListbox.getSelectedCount() == 0) {
            return;
        }
        exportToPdf(activeListbox);
    }

    /**
     * Orchestrates rendering matching export structures.
     */
    private void exportToPdf(Listbox listbox) {
        try {
            List<Object> itemsToExport = new java.util.ArrayList<>();
            Checkbox headerCheckbox = retrieveActiveHeaderCheckbox();
            if (headerCheckbox != null && headerCheckbox.isChecked()) {
                itemsToExport.addAll(currentFilteredList);
            } else {
                for (Listitem item : listbox.getSelectedItems()) {
                    itemsToExport.add(item.getValue());
                }
            }

            if (itemsToExport.isEmpty()) {
                return;
            }

            // Determine date range
            String dateRangeStr = determinePdfDateRange(itemsToExport);

            // Compute totals
            int totalBatches = 0;
            int totalCheques = 0;
            BigDecimal totalAmountVal = BigDecimal.ZERO;
            java.util.Set<String> uniqueBatches = new java.util.HashSet<>();

            for (Object val : itemsToExport) {
                if (val instanceof ReportBatchDTO) {
                    ReportBatchDTO batch = (ReportBatchDTO) val;
                    totalBatches++;
                    totalCheques += batch.getTotalCheques();
                    if (batch.getTotalAmount() != null) {
                        totalAmountVal = totalAmountVal.add(batch.getTotalAmount());
                    }
                    if (batch.getBatchId() != null && !batch.getBatchId().trim().isEmpty()) {
                        uniqueBatches.add(batch.getBatchId());
                    }
                } else if (val instanceof ReportChequeDTO) {
                    ReportChequeDTO cheque = (ReportChequeDTO) val;
                    totalCheques++;
                    if (cheque.getAmount() != null) {
                        totalAmountVal = totalAmountVal.add(cheque.getAmount());
                    }
                    if (cheque.getBatchId() != null && !cheque.getBatchId().trim().isEmpty()) {
                        uniqueBatches.add(cheque.getBatchId());
                    }
                }
            }

            String batchIdStr = "";
            if (uniqueBatches.size() == 1) {
                batchIdStr = uniqueBatches.iterator().next();
            }

            // Retrieve admin details
            String adminName = "Admin";
            org.zkoss.zk.ui.Session zkSession = org.zkoss.zk.ui.Sessions.getCurrent();
            if (zkSession != null) {
                com.cts.uam.model.User sessionUser = (com.cts.uam.model.User) zkSession
                        .getAttribute(SecurityUtil.SESSION_USER_KEY);
                String sessionUserName = sessionUser != null ? sessionUser.getUsername() : "System";
                if (sessionUserName != null && !sessionUserName.trim().isEmpty()) {
                    adminName = sessionUserName;
                }
            }

            // Retrieve login time
            long loginTimeMs = System.currentTimeMillis();
            if (zkSession != null && zkSession.getNativeSession() instanceof jakarta.servlet.http.HttpSession) {
                try {
                    loginTimeMs = ((jakarta.servlet.http.HttpSession) zkSession.getNativeSession()).getCreationTime();
                } catch (Exception exception) {
                    // fallback
                }
            }
            LocalDateTime loginDateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(loginTimeMs),
                    java.time.ZoneId.systemDefault());
            String loginTimeStr = loginDateTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
            String generatedTimeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));

            // Select JRXML path and populate data source collection
            String templatePath = "";
            List<Map<String, ?>> jasperData = new ArrayList<>();
            Map<String, Object> parameters = new HashMap<>();

            parameters.put("adminName", adminName);
            parameters.put("loginTime", loginTimeStr);
            parameters.put("dateRange", dateRangeStr);
            parameters.put("generatedAt", generatedTimeStr);
            parameters.put("totalAmount", formatCurrencyToIndianRupees(totalAmountVal));

            if ("CXF_REPORT".equals(activeReportTab) || "CIBF_REPORT".equals(activeReportTab)) {
                templatePath = "/zul/outward/jrxml-files/cxf_cibf_report.jrxml";
                parameters.put("totalCountLabel", "Total Batches");
                parameters.put("totalCountValue", String.valueOf(totalBatches));

                for (Object item : itemsToExport) {
                    ReportBatchDTO batch = (ReportBatchDTO) item;
                    Map<String, Object> map = new HashMap<>();
                    map.put("fileId", batch.getBatchId());
                    map.put("fileName",
                            "CXF_REPORT".equals(activeReportTab) ? batch.getCxfFileName() : batch.getCibfFileName());
                    map.put("fileType", "CXF_REPORT".equals(activeReportTab) ? "CXF" : "CIBF");
                    map.put("totalRecords", batch.getTotalCheques());
                    map.put("totalAmount", batch.getTotalAmount());
                    map.put("status", batch.getStatus());
                    map.put("generatedDate", batch.getGeneratedAt());
                    jasperData.add(map);
                }
            } else if ("BATCH_SUMMARY".equals(activeReportTab)) {
                if (itemsToExport.size() == 1) {
                    // Single Batch - Export its cheques details
                    templatePath = "/zul/outward/jrxml-files/cheque_report.jrxml";
                    parameters.put("totalCountLabel", "Total Cheques");

                    ReportBatchDTO singleBatch = (ReportBatchDTO) itemsToExport.get(0);
                    List<ReportChequeDTO> allCheques = service.getAllCheques();
                    List<ReportChequeDTO> batchCheques = new java.util.ArrayList<>();
                    for (ReportChequeDTO cheque : allCheques) {
                        if (cheque.getBatchId() != null && cheque.getBatchId().equals(singleBatch.getBatchId())) {
                            batchCheques.add(cheque);
                        }
                    }
                    parameters.put("totalCountValue", String.valueOf(batchCheques.size()));

                    for (ReportChequeDTO cheque : batchCheques) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("chequeNo", cheque.getChequeNo());
                        map.put("batchId", cheque.getBatchId());
                        map.put("amount", cheque.getAmount());
                        map.put("chequeDate", formatChequeDateStringForPdf(cheque.getChequeDate()));
                        map.put("status", cheque.getVerStatus());
                        jasperData.add(map);
                    }
                } else {
                    // Multiple Batches - Export batch summary list
                    templatePath = "/zul/outward/jrxml-files/batch_summary_report.jrxml";
                    parameters.put("totalCountLabel", "Total Batches");
                    parameters.put("totalCountValue", String.valueOf(totalBatches));

                    for (Object item : itemsToExport) {
                        ReportBatchDTO batch = (ReportBatchDTO) item;
                        Map<String, Object> map = new HashMap<>();
                        map.put("batchId", batch.getBatchId());
                        map.put("totalCheques", batch.getTotalCheques());
                        map.put("totalAmount", batch.getTotalAmount());
                        map.put("createdDate", batch.getCreatedAt());
                        map.put("status", batch.getStatus());
                        jasperData.add(map);
                    }
                }
            } else if ("CHEQUE_REPORT".equals(activeReportTab)) {
                templatePath = "/zul/outward/jrxml-files/cheque_report.jrxml";
                parameters.put("totalCountLabel", "Total Cheques");
                parameters.put("totalCountValue", String.valueOf(totalCheques));

                for (Object item : itemsToExport) {
                    ReportChequeDTO cheque = (ReportChequeDTO) item;
                    Map<String, Object> map = new HashMap<>();
                    map.put("chequeNo", cheque.getChequeNo());
                    map.put("batchId", cheque.getBatchId());
                    map.put("amount", cheque.getAmount());
                    map.put("chequeDate", formatChequeDateStringForPdf(cheque.getChequeDate()));
                    map.put("status", cheque.getVerStatus());
                    jasperData.add(map);
                }
            } else {
                templatePath = "/zul/outward/jrxml-files/fallback_report.jrxml";
                parameters.put("ReportTitle", "Outward Reports Export");
                parameters.put("RecordCount", itemsToExport.size());
            }

            // Compile and render the report
            InputStream jrxmlStream = Sessions.getCurrent().getWebApp().getResourceAsStream(templatePath);
            if (jrxmlStream == null) {
                throw new java.io.FileNotFoundException("JRXML template file not found: " + templatePath);
            }
            JasperReport jasperReport = JasperCompileManager.compileReport(jrxmlStream);
            JRMapCollectionDataSource dataSource = new JRMapCollectionDataSource(jasperData);
            JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, dataSource);

            byte[] pdfBytes = JasperExportManager.exportReportToPdf(jasperPrint);

            String pdfName = activeReportTab.toLowerCase() + "_report_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".pdf";
            Filedownload.save(pdfBytes, "application/pdf", pdfName);

        } catch (Exception exception) {
            LOG.log(Level.SEVERE, "exportToPdf failed", exception);
            try {
                Messagebox.show("Export failed: " + exception.toString(), "Error", Messagebox.OK, Messagebox.ERROR);
            } catch (Exception messageboxException) {
                // Ignore fallback
            }
        }
    }

    /**
     * Computes bounds of dates extracted across lists.
     */
    private String determinePdfDateRange(List<?> itemsToExport) {
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>();
        for (Object item : itemsToExport) {
            LocalDate extractedDate = null;
            if (item instanceof ReportBatchDTO) {
                ReportBatchDTO batch = (ReportBatchDTO) item;
                LocalDateTime dateTime = "CXF_REPORT".equals(activeReportTab) ? batch.getGeneratedAt()
                        : batch.getCreatedAt();
                if (dateTime != null) {
                    extractedDate = dateTime.toLocalDate();
                }
            } else if (item instanceof ReportChequeDTO) {
                ReportChequeDTO cheque = (ReportChequeDTO) item;
                extractedDate = parseChequeDateStringToLocalDate(cheque.getChequeDate());
            }
            if (extractedDate != null) {
                dates.add(extractedDate);
            }
        }

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        if (!dates.isEmpty()) {
            LocalDate minDate = dates.first();
            LocalDate maxDate = dates.last();
            if (minDate.equals(maxDate)) {
                return minDate.format(dateFormatter);
            } else {
                return minDate.format(dateFormatter) + " to " + maxDate.format(dateFormatter);
            }
        }

        Date fromDateValue = fromDate.getValue();
        Date toDateValue = toDate.getValue();
        if (fromDateValue != null && toDateValue != null) {
            LocalDate fromLocalDate = new java.sql.Date(fromDateValue.getTime()).toLocalDate();
            LocalDate toLocalDate = new java.sql.Date(toDateValue.getTime()).toLocalDate();
            if (fromLocalDate.equals(toLocalDate)) {
                return fromLocalDate.format(dateFormatter);
            } else {
                return fromLocalDate.format(dateFormatter) + " to " + toLocalDate.format(dateFormatter);
            }
        } else if (fromDateValue != null) {
            LocalDate fromLocalDate = new java.sql.Date(fromDateValue.getTime()).toLocalDate();
            return fromLocalDate.format(dateFormatter) + " to All";
        } else if (toDateValue != null) {
            LocalDate toLocalDate = new java.sql.Date(toDateValue.getTime()).toLocalDate();
            return "All to " + toLocalDate.format(dateFormatter);
        }
        return "All to All";
    }

    /**
     * Standardizes raw cheque date strings for PDF display.
     */
    private String formatChequeDateStringForPdf(String dateString) {
        if (dateString == null || dateString.isBlank()) {
            return "";
        }
        if (dateString.matches("\\d{4}-\\d{2}-\\d{2}")) {
            String[] parts = dateString.split("-");
            return parts[2] + "-" + parts[1] + "-" + parts[0];
        }
        if (dateString.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return dateString.replace('/', '-');
        }
        return dateString;
    }

    /**
     * Appends text cells to list item rows.
     */
    private void appendListcellWithTextAndStyle(Listitem row, String value, String style) {
        Listcell textCell = new Listcell(value != null ? value : "—");
        if (style != null) {
            textCell.setStyle(style);
        }
        row.appendChild(textCell);
    }

    /**
     * Appends long string values as list cells with full tooltip capabilities.
     */
    private void appendLongValueCell(Listitem row, String value) {
        String displayVal = (value != null && !value.isEmpty()) ? value : "—";
        Listcell tooltipCell = new Listcell(displayVal);
        if (value != null && !value.isEmpty() && !value.equals("—")) {
            tooltipCell.setTooltiptext(value);
        }
        row.appendChild(tooltipCell);
    }

    /**
     * Instantiates status indicator badges dynamically.
     */
    private Listcell createListcellWithStatusPill(String statusLabel, String cssClass) {
        Listcell pillCell = new Listcell();
        Label statusLabelComponent = new Label(statusLabel != null ? statusLabel : "—");
        statusLabelComponent.setSclass(cssClass);
        pillCell.appendChild(statusLabelComponent);
        return pillCell;
    }

    /**
     * Resolves display labels for batch statuses.
     */
    private String formatStatusStringToFriendlyLabel(String statusString) {
        if (statusString == null) {
            return "—";
        }
        BatchStatus bs = BatchStatus.fromDb(statusString);
        if (bs == BatchStatus.CXF_CIBF_GENERATED) {
            return "CXF-CIBF generated";
        }
        switch (statusString) {
            case "VER2_DONE":
                return "Verified";
            case "VER1_DONE":
                return "Ver-I Done";
            case "SCAN_DONE":
                return "Scan Done";
            case "PENDING":
            case "Pending":
                return "Pending";
            case "V1_PENDING":
                return "V1 Pending";
            case "V2_PENDING":
                return "V2 Pending";
            case "VERIFIED":
            case "Verified":
                return "Verified";
            case "REJECTED":
            case "Rejected":
                return "Rejected";
            case "Draft":
                return "Draft";
            case "ReadyForVerification":
                return "Ready For Verification";
            case "VerificationInProgress":
                return "Verification In Progress";
            default:
                return statusString;
        }
    }

    /**
     * Resolves CSS chip styles for batch statuses.
     */
    private String retrieveCssClassForBatchStatusPill(String statusString) {
        if (statusString == null) {
            return "chip ch-draft";
        }
        BatchStatus bs = BatchStatus.fromDb(statusString);
        if (bs == BatchStatus.CXF_CIBF_GENERATED) {
            return "chip ch-approved";
        }
        switch (statusString) {
            case "VER2_DONE":
            case "VERIFIED":
            case "Verified":
                return "chip ch-verified";
            case "VER1_DONE":
            case "V1_PENDING":
            case "V2_PENDING":
            case "VerificationInProgress":
                return "chip ch-verification";
            case "SCAN_DONE":
                return "chip ch-pending";
            case "REJECTED":
            case "Rejected":
                return "chip ch-rejected";
            case "Draft":
                return "chip ch-draft";
            default:
                return "chip ch-draft";
        }
    }

    /**
     * Resolves display labels for cheque verifications.
     */
    private String formatChequeStatusStringToFriendlyLabel(String verStatus) {
        if (verStatus == null) {
            return "Pending";
        }
        switch (verStatus) {
            case "VERIFIED":
            case "Verified":
                return "Accepted";
            case "REJECTED":
            case "Rejected":
                return "Rejected";
            case "V1_PENDING":
                return "Pending";
            case "V2_PENDING":
                return "Referred";
            case "Sent_Back":
            case "SENT BACK":
            case "SentBack":
                return "Sent Back";
            default:
                return verStatus.toUpperCase();
        }
    }

    /**
     * Resolves CSS chip styles for cheque verifications.
     */
    private String retrieveCssClassForChequeStatusPill(String verStatus) {
        if (verStatus == null) {
            return "chip ch-pending";
        }
        switch (verStatus) {
            case "VERIFIED":
            case "Verified":
                return "chip ch-verified";
            case "REJECTED":
            case "Rejected":
                return "chip ch-rejected";
            case "V1_PENDING":
                return "chip ch-pending";
            case "V2_PENDING":
                return "chip ch-verification";
            case "Sent_Back":
            case "SENT BACK":
            case "SentBack":
                return "chip ch-draft";
            default:
                return "chip ch-pending";
        }
    }

    /**
     * Formats decimal currency values to standard Indian Rupee format.
     */
    private String formatCurrencyToIndianRupees(BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        try {
            return RUPEE_FORMATTER.format(amount);
        } catch (Exception exception) {
            return amount.toPlainString();
        }
    }

    /**
     * Formats large currency values into short Indian Rupee notation
     * (Lakhs/Crores).
     */
    private String formatCurrencyToShortIndianRupees(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return "₹0";
        }
        double amountDouble = amount.doubleValue();
        if (amountDouble >= 10000000.0) {
            double croreValue = amountDouble / 10000000.0;
            return String.format("₹%.1f Cr", croreValue);
        } else if (amountDouble >= 100000.0) {
            double lakhValue = amountDouble / 100000.0;
            return String.format("₹%.1f L", lakhValue);
        } else {
            return RUPEE_FORMATTER.format(amount).replaceAll("\\.00$", "");
        }
    }

    /**
     * Formats LocalDateTime to a string representation using DATE_TIME_FORMATTER.
     */
    private String formatLocalDateTimeToString(LocalDateTime dt) {
        return dt != null ? dt.format(DATE_TIME_FORMATTER) : "—";
    }

    /**
     * Replaces a null or empty string with an em-dash placeholder.
     */
    private String substituteNullWithPlaceholder(String value) {
        return (value == null || value.trim().isEmpty()) ? "—" : value;
    }
}