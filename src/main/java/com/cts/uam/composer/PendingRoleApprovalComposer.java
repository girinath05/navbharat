package com.cts.uam.composer;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.*;

import com.cts.uam.model.Role;
import com.cts.uam.service.RoleServiceImpl;
import com.cts.util.SecurityUtil;

/**
 * PendingRoleApprovalComposer - handles the Pending Role Approvals page
 * (pending-role-approval.zul).
 *
 * This page is for the CHECKER. It shows all roles waiting for approval.
 *
 * What the checker can do:
 * Approve -> role status changes from PENDING to ACTIVE (role becomes usable)
 * Reject -> role status changes to REJECTED (record stays in DB, not deleted)
 *
 * Four-eyes rule: the person who created the role (maker) cannot approve it
 * themselves.
 * Approve/Reject buttons are disabled if checker == maker.
 */
public class PendingRoleApprovalComposer extends SelectorComposer<Component> {

    // Date format used when showing "submitted at" time
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    // How many rows to show per page
    private static final int PAGE_SIZE = 5;

    // ── LIST TABLE - UI components ────────────────────────────────
    @Wire("#lstRoleApprovals")
    private Listbox lstRoleApprovals; // table showing pending roles

    @Wire("#lblRoleApprovalCount")
    private Label lblRoleApprovalCount; // shows "(3 pending)"

    @Wire("#lblPaginationInfo")
    private Label lblPaginationInfo; // shows "1-5 of 8 requests"

    @Wire("#lblPageNum")
    private Label lblPageNum; // shows "Page 1 of 2"

    @Wire("#btnFirstPage")
    private Button btnFirstPage;

    @Wire("#btnPrevPage")
    private Button btnPrevPage;

    @Wire("#btnNextPage")
    private Button btnNextPage;

    @Wire("#btnLastPage")
    private Button btnLastPage;

    // ── DETAIL MODAL - UI components ──────────────────────────────
    // Shown when checker clicks View or Approve/Reject on a row
    @Wire("#roleDetailOverlay")
    private Div roleDetailOverlay; // the full modal panel

    @Wire("#rdRoleName")
    private Label rdRoleName; // role name in modal

    @Wire("#rdActionType")
    private Label rdActionType; // always "CREATE" for this page

    @Wire("#rdCurrentDesc")
    private Label rdCurrentDesc; // role description

    @Wire("#rdMakerId")
    private Label rdMakerId; // who submitted this role (maker)

    @Wire("#rdSubmittedAt")
    private Label rdSubmittedAt; // when it was submitted

    @Wire("#rdCurrentStatus")
    private Label rdCurrentStatus; // current status (PENDING)

    @Wire("#rdPermListSection")
    private Div rdPermListSection; // section showing assigned permissions

    @Wire("#rdPermList")
    private Div rdPermList; // permission key badges go here

    @Wire("#rdRejectSection")
    private Div rdRejectSection; // rejection reason input - hidden until Reject is clicked

    @Wire("#txtRoleRejectRemarks")
    private Textbox txtRoleRejectRemarks; // checker types rejection reason here

    @Wire("#rdSelfWarning")
    private Div rdSelfWarning; // warning shown if checker == maker

    @Wire("#btnRoleModalApprove")
    private Button btnRoleModalApprove; // Approve button in modal

    @Wire("#btnRoleModalReject")
    private Button btnRoleModalReject; // Reject button in modal

    // ── State ─────────────────────────────────────────────────────
    private Role selectedRole = null; // role currently open in the detail modal
    private int currentPage = 0; // which page is currently showing (0-based)
    private long totalPendingRoleCount = 0; // total pending roles in DB

    private final RoleServiceImpl roleService = new RoleServiceImpl();

    // ── INIT ──────────────────────────────────────────────────────

    /**
     * Runs once when the page loads.
     * Loads all pending roles from DB and renders the first page.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        loadPendingRoles();
    }

    // ── LOAD + RENDER ─────────────────────────────────────────────

    /**
     * Fetches total pending role count from DB, updates the badge label,
     * then renders the current page of the list.
     * Called on page load and after every approve/reject action.
     */
    private void loadPendingRoles() {
        if (lstRoleApprovals == null)
            return;

        totalPendingRoleCount = roleService.countPendingRoles();

        // Update the "(N pending)" badge at the top of the page
        if (lblRoleApprovalCount != null)
            lblRoleApprovalCount.setValue("(" + totalPendingRoleCount + " pending)");

        renderCurrentPage();
    }

    // ── PAGINATION ────────────────────────────────────────────────

    /**
     * Fetches only the current page's rows from DB (LIMIT/OFFSET) and renders them.
     * Also updates page labels and enables/disables navigation buttons.
     */
    private void renderCurrentPage() {
        lstRoleApprovals.getItems().clear();

        int totalPages = Math.max(1, (int) Math.ceil((double) totalPendingRoleCount / PAGE_SIZE));

        // Clamp page index in valid range (count shrinks after approve/reject)
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        int offset = currentPage * PAGE_SIZE;
        List<Role> pageRoles = roleService.getPendingRoles(offset, PAGE_SIZE);

        int from = offset;
        int to = offset + pageRoles.size();

        // Update info labels
        if (lblPaginationInfo != null)
            lblPaginationInfo.setValue(
                    totalPendingRoleCount == 0 ? "No pending requests"
                            : "Showing " + (from + 1) + "-" + to + " of " + totalPendingRoleCount + " requests");
        if (lblPageNum != null)
            lblPageNum.setValue("Page " + (currentPage + 1) + " of " + totalPages);

        // Disable buttons that cannot be used on this page
        if (btnFirstPage != null)
            btnFirstPage.setDisabled(currentPage == 0);
        if (btnPrevPage != null)
            btnPrevPage.setDisabled(currentPage == 0);
        if (btnNextPage != null)
            btnNextPage.setDisabled(to >= totalPendingRoleCount);
        if (btnLastPage != null)
            btnLastPage.setDisabled(to >= totalPendingRoleCount);

        // Render one table row per role on this page
        int rowNum = from + 1;
        for (Role role : pageRoles)
            lstRoleApprovals.appendChild(buildTableRow(role, rowNum++));
    }

    // ── Pagination button click handlers ──────────────────────────

    @Listen("onClick = #btnFirstPage")
    public void onClickFirstPage() {
        currentPage = 0;
        renderCurrentPage();
    }

    @Listen("onClick = #btnPrevPage")
    public void onClickPrevPage() {
        currentPage--;
        renderCurrentPage();
    }

    @Listen("onClick = #btnNextPage")
    public void onClickNextPage() {
        currentPage++;
        renderCurrentPage();
    }

    @Listen("onClick = #btnLastPage")
    public void onClickLastPage() {
        currentPage = Math.max(0, (int) Math.ceil((double) totalPendingRoleCount / PAGE_SIZE) - 1);
        renderCurrentPage();
    }

    // ── TABLE ROW BUILDER ─────────────────────────────────────────

    /**
     * Builds one row in the pending roles table.
     * Columns: # | Role Name | Description | Maker | Submitted At | Actions
     */
    private Listitem buildTableRow(Role role, int rowNum) {
        Listitem row = new Listitem();
        row.setValue(role);

        String description = role.getDescription() != null ? role.getDescription() : "-";
        String maker = role.getCreatedBy() != null ? role.getCreatedBy() : "-";
        String submittedAt = role.getCreatedAt() != null ? role.getCreatedAt().format(DT_FORMAT) : "-";

        row.appendChild(new Listcell(String.valueOf(rowNum))); // row number
        row.appendChild(new Listcell(role.getRoleName())); // role name

        // Description cell - allow wrapping for long text
        Listcell descCell = new Listcell(description);
        descCell.setSclass("desc-cell");
        row.appendChild(descCell);

        row.appendChild(new Listcell(maker)); // maker who submitted
        row.appendChild(new Listcell(submittedAt)); // when submitted
        row.appendChild(buildActionButtonsCell(role)); // View / Approve / Reject buttons

        return row;
    }

    /**
     * Builds the Actions cell: View, Approve, and Reject buttons.
     *
     * Four-eyes check: if the logged-in user created this role,
     * Approve and Reject are disabled to enforce the rule.
     */
    private Listcell buildActionButtonsCell(Role role) {
        boolean isSelfRequest = isSelfRequest(role.getCreatedBy());

        Button btnView = new Button("View");
        btnView.setSclass("btn btn-outline btn-sm");
        btnView.addEventListener("onClick", e -> openDetailModal(role));

        Button btnApprove = new Button("✓ Approve");
        btnApprove.setSclass("btn btn-success btn-sm");
        btnApprove.setDisabled(isSelfRequest);
        if (isSelfRequest)
            btnApprove.setTooltiptext("You submitted this request - a different user must approve it.");
        btnApprove.addEventListener("onClick", e -> doApprove(role));

        Button btnReject = new Button("✗ Reject");
        btnReject.setSclass("btn btn-danger btn-sm");
        btnReject.setDisabled(isSelfRequest);
        if (isSelfRequest)
            btnReject.setTooltiptext("You submitted this request - a different user must reject it.");
        btnReject.addEventListener("onClick", e -> doReject(role));

        Div actionsDiv = new Div();
        actionsDiv.setSclass("action-cell");
        actionsDiv.appendChild(btnView);
        actionsDiv.appendChild(btnApprove);
        actionsDiv.appendChild(btnReject);

        Listcell cell = new Listcell();
        cell.appendChild(actionsDiv);
        return cell;
    }

    /**
     * Returns true if the logged-in user is the same person who created this role.
     * Used to enforce the four-eyes rule.
     */
    private boolean isSelfRequest(String createdBy) {
        String currentUserId = SecurityUtil.getCurrentUserId();
        return currentUserId != null && currentUserId.equals(createdBy);
    }

    // ── DETAIL MODAL ──────────────────────────────────────────────

    /**
     * Opens the detail modal for the given role.
     * Shows role info, assigned permissions as badges, and Approve/Reject buttons.
     * If logged-in user is the maker, Approve and Reject are disabled with a
     * warning.
     */
    private void openDetailModal(Role role) {
        selectedRole = role;
        if (roleDetailOverlay == null)
            return;

        String maker = role.getCreatedBy() != null ? role.getCreatedBy() : "-";
        String description = role.getDescription() != null ? role.getDescription() : "-";
        String submittedAt = role.getCreatedAt() != null ? role.getCreatedAt().format(DT_FORMAT) : "-";

        // Fill in the modal fields
        rdRoleName.setValue(role.getRoleName());
        if (rdActionType != null)
            rdActionType.setValue("CREATE");
        if (rdMakerId != null)
            rdMakerId.setValue(maker);
        if (rdSubmittedAt != null)
            rdSubmittedAt.setValue(submittedAt);
        if (rdCurrentDesc != null)
            rdCurrentDesc.setValue(description);
        if (rdCurrentStatus != null)
            rdCurrentStatus.setValue(role.getStatus().name());

        // Show each assigned permission as a badge label
        if (rdPermListSection != null)
            rdPermListSection.setVisible(true);
        if (rdPermList != null) {
            rdPermList.getChildren().clear();
            Set<String> permissions = roleService.getAssignedPermissionKeys(role.getId());
            if (permissions.isEmpty()) {
                // Show a message if no permissions are assigned (edge case)
                Label noPerms = new Label("No permissions assigned.");
                noPerms.setStyle("color: var(--cts-text-muted); font-style: italic;");
                rdPermList.appendChild(noPerms);
            } else {
                for (String key : permissions) {
                    Label badge = new Label(key);
                    badge.setSclass("badge badge-active");
                    badge.setStyle("margin:2px;");
                    rdPermList.appendChild(badge);
                }
            }
        }

        // Four-eyes check: if checker is the same as maker, block approve and reject
        String currentUserId = SecurityUtil.getCurrentUserId();
        boolean isSelfApproval = currentUserId != null && currentUserId.equals(role.getCreatedBy());
        if (rdSelfWarning != null)
            rdSelfWarning.setVisible(isSelfApproval);
        if (btnRoleModalApprove != null)
            btnRoleModalApprove.setDisabled(isSelfApproval);
        if (btnRoleModalReject != null)
            btnRoleModalReject.setDisabled(isSelfApproval);

        // Hide the rejection input section until Reject is clicked
        if (rdRejectSection != null)
            rdRejectSection.setVisible(false);

        roleDetailOverlay.setVisible(true);
    }

    // ── APPROVE ───────────────────────────────────────────────────

    /**
     * Shows a confirm dialog, then approves the role on confirm.
     * On success: role status changes to ACTIVE.
     */
    private void doApprove(Role role) {
        Messagebox.show(
                "Approve creation of role \"" + role.getRoleName() + "\"?",
                "Confirm", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (!Messagebox.ON_YES.equals(event.getName()))
                        return; // user clicked No - do nothing
                    try {
                        roleService.approveRole(role.getId(), SecurityUtil.getCurrentUserId());
                        closeModal();
                        loadPendingRoles();
                        Messagebox.show("Role approved and activated successfully.",
                                "Done", Messagebox.OK, Messagebox.INFORMATION);
                    } catch (Exception ex) {
                        showError(ex.getMessage());
                    }
                });
    }

    // Approve button inside the modal - delegates to doApprove()
    @Listen("onClick = #btnRoleModalApprove")
    public void onRoleModalApprove() {
        if (selectedRole != null)
            doApprove(selectedRole);
    }

    // ── REJECT ────────────────────────────────────────────────────

    /**
     * Opens the detail modal and reveals the rejection reason input.
     * Called when Reject is clicked directly from the table row (not from inside
     * the modal).
     */
    private void doReject(Role role) {
        openDetailModal(role);
        if (rdRejectSection != null) {
            rdRejectSection.setVisible(true);
            if (txtRoleRejectRemarks != null)
                txtRoleRejectRemarks.setValue("");
            if (btnRoleModalReject != null)
                btnRoleModalReject.setLabel("Submit Rejection");
        }
    }

    /**
     * Reject button inside the modal - works in two clicks:
     * First click -> reveals the rejection reason input box
     * Second click -> validates the reason and submits the rejection
     */
    @Listen("onClick = #btnRoleModalReject")
    public void onRoleModalReject() {
        if (selectedRole == null || rdRejectSection == null)
            return;

        // First click: show the rejection reason input
        if (!rdRejectSection.isVisible()) {
            rdRejectSection.setVisible(true);
            txtRoleRejectRemarks.setValue("");
            txtRoleRejectRemarks.setFocus(true);
            btnRoleModalReject.setLabel("Submit Rejection");
            return;
        }

        // Second click: validate reason and submit
        String remarks = txtRoleRejectRemarks.getValue().trim();
        if (remarks.isEmpty()) {
            showError("Please enter a rejection reason.");
            txtRoleRejectRemarks.setFocus(true);
            return;
        }

        try {
            roleService.rejectRole(selectedRole.getId(), SecurityUtil.getCurrentUserId(), remarks);
            closeModal();
            loadPendingRoles();
            Messagebox.show("Role rejected. Record kept in database with status REJECTED.",
                    "Done", Messagebox.OK, Messagebox.INFORMATION);
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    // ── MODAL CLOSE + REFRESH ─────────────────────────────────────

    // Close button inside the modal
    @Listen("onClick = #btnRoleDetailClose; onClick = #btnRoleModalClose")
    public void onRoleDetailClose() {
        closeModal();
    }

    // Manual refresh button - reloads the pending roles list from DB
    @Listen("onClick = #btnRefreshRole")
    public void onRefresh() {
        loadPendingRoles();
    }

    /**
     * Hides the detail modal, resets the reject button label, and clears the
     * selected role.
     */
    private void closeModal() {
        if (roleDetailOverlay != null)
            roleDetailOverlay.setVisible(false);
        if (btnRoleModalReject != null)
            btnRoleModalReject.setLabel("✗ Reject"); // reset label ready for next open
        selectedRole = null;
    }

    // ── HELPERS ───────────────────────────────────────────────────

    // Shows an error popup with the given message
    private void showError(String message) {
        Messagebox.show(message, "Error", Messagebox.OK, Messagebox.ERROR);
    }
}
