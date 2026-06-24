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
import com.cts.uam.service.RoleService;
import com.cts.util.SecurityUtil;

/**
 * PendingRoleApprovalComposer — handles pending-role-approval.zul.
 *
 * Shows all roles with status=PENDING.
 * Approve → status becomes ACTIVE.
 * Reject  → status becomes REJECTED, reason saved to DB. Row is NOT deleted.
 */
public class PendingRoleApprovalComposer extends SelectorComposer<Component> {

    private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final int PAGE_SIZE = 5;

    // ── Wired UI — List ───────────────────────────────────────────
    @Wire("#lstRoleApprovals")      private Listbox lstRoleApprovals;
    @Wire("#lblRoleApprovalCount")  private Label   lblRoleApprovalCount;
    @Wire("#lblPaginationInfo")     private Label   lblPaginationInfo;
    @Wire("#btnFirstPage")          private Button  btnFirstPage;
    @Wire("#btnPrevPage")           private Button  btnPrevPage;
    @Wire("#btnNextPage")           private Button  btnNextPage;
    @Wire("#btnLastPage")           private Button  btnLastPage;
    @Wire("#lblPageNum")            private Label   lblPageNum;

    // ── Wired UI — Detail Modal ───────────────────────────────────
    @Wire("#roleDetailOverlay")     private Div     roleDetailOverlay;
    @Wire("#rdRoleName")            private Label   rdRoleName;
    @Wire("#rdActionType")          private Label   rdActionType;
    @Wire("#rdCurrentDesc")         private Label   rdCurrentDesc;
    @Wire("#rdMakerId")             private Label   rdMakerId;
    @Wire("#rdSubmittedAt")         private Label   rdSubmittedAt;
    @Wire("#rdCurrentStatus")       private Label   rdCurrentStatus;
    @Wire("#rdPermListSection")     private Div     rdPermListSection;
    @Wire("#rdPermList")            private Div     rdPermList;
    @Wire("#rdRejectSection")       private Div     rdRejectSection;
    @Wire("#txtRoleRejectRemarks")  private Textbox txtRoleRejectRemarks;
    @Wire("#rdSelfWarning")         private Div     rdSelfWarning;
    @Wire("#btnRoleModalApprove")   private Button  btnRoleModalApprove;
    @Wire("#btnRoleModalReject")    private Button  btnRoleModalReject;

    // ── State ─────────────────────────────────────────────────────
    private Role selectedRole = null;
    private int currentPage = 0;
    private List<Role> cachedPendingRoles;
    private final RoleService roleService = new RoleService();

    // ── Init ──────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        loadPending();
    }

    // ── Load Pending Roles ────────────────────────────────────────

    private void loadPending() {
        if (lstRoleApprovals == null) return;

        cachedPendingRoles = roleService.getPendingRoles();
        if (lblRoleApprovalCount != null)
            lblRoleApprovalCount.setValue("(" + cachedPendingRoles.size() + " pending)");

        renderCurrentPage();
    }

    // ── Pagination ────────────────────────────────────────────────

    private void renderCurrentPage() {
        lstRoleApprovals.getItems().clear();
        int total = cachedPendingRoles.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
        if (currentPage > totalPages - 1)
            currentPage = totalPages - 1; // clamp after an approve/reject shrinks the list
        if (currentPage < 0)
            currentPage = 0;

        int fromIdx = currentPage * PAGE_SIZE;
        int toIdx = Math.min(fromIdx + PAGE_SIZE, total);

        if (lblPaginationInfo != null)
            lblPaginationInfo.setValue(
                    "Showing " + (total == 0 ? 0 : fromIdx + 1) + "–" + toIdx + " of " + total + " requests");
        if (lblPageNum != null)
            lblPageNum.setValue("Page " + (currentPage + 1) + " of " + totalPages);
        if (btnFirstPage != null)
            btnFirstPage.setDisabled(currentPage == 0);
        if (btnPrevPage != null)
            btnPrevPage.setDisabled(currentPage == 0);
        if (btnNextPage != null)
            btnNextPage.setDisabled(toIdx >= total);
        if (btnLastPage != null)
            btnLastPage.setDisabled(toIdx >= total);

        for (int i = fromIdx; i < toIdx; i++)
            lstRoleApprovals.appendChild(buildPendingRoleRow(cachedPendingRoles.get(i), i + 1));
    }

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
        int total = cachedPendingRoles.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
        currentPage = totalPages - 1;
        renderCurrentPage();
    }

    // ── Row Builder ───────────────────────────────────────────────

      private Listitem buildPendingRoleRow(Role role, int rowNum) {
        Listitem row = new Listitem();
        row.setValue(role);
 
        String description = role.getDescription() != null ? role.getDescription() : "—";
        String maker       = role.getCreatedBy()   != null ? role.getCreatedBy()   : "—";
        String submittedAt = role.getCreatedAt()   != null
                ? role.getCreatedAt().format(DT_FORMAT) : "—";
 
        row.appendChild(new Listcell(String.valueOf(rowNum)));  // #
        row.appendChild(new Listcell(role.getRoleName()));      // Role Name
 
        // Description — wrap instead of truncate
        Listcell descCell = new Listcell(description);
        descCell.setSclass("desc-cell");
        row.appendChild(descCell);
 
        row.appendChild(new Listcell(maker));                   // Maker
        row.appendChild(new Listcell(submittedAt));             // Submitted At
        row.appendChild(buildActionButtonsCell(role));          // Actions
 
        return row;
    }

    private Listcell buildActionButtonsCell(Role role) {
        Div actionsDiv = new Div();
        actionsDiv.setSclass("action-cell");

        boolean isSelfRequest = isSelfRequest(role.getCreatedBy());

        Button btnView = new Button("View");
        btnView.setSclass("btn btn-outline btn-sm");
        btnView.addEventListener("onClick", e -> openDetailModal(role));

        Button btnApprove = new Button("✓ Approve");
        btnApprove.setSclass("btn btn-success btn-sm");
        btnApprove.setDisabled(isSelfRequest);
        if (isSelfRequest)
            btnApprove.setTooltiptext("You submitted this request — a different user must approve it.");
        btnApprove.addEventListener("onClick", e -> doApprove(role));

        Button btnReject = new Button("✗ Reject");
        btnReject.setSclass("btn btn-danger btn-sm");
        btnReject.setDisabled(isSelfRequest);
        if (isSelfRequest)
            btnReject.setTooltiptext("You submitted this request — a different user must reject it.");
        btnReject.addEventListener("onClick", e -> doReject(role));

        actionsDiv.appendChild(btnView);
        actionsDiv.appendChild(btnApprove);
        actionsDiv.appendChild(btnReject);

        Listcell cell = new Listcell();
        cell.appendChild(actionsDiv);
        return cell;
    }

    private boolean isSelfRequest(String createdBy) {
        String currentUserId = SecurityUtil.getCurrentUserId();
        return currentUserId != null && currentUserId.equals(createdBy);
    }

    // ── Detail Modal ──────────────────────────────────────────────

    private void openDetailModal(Role role) {
        selectedRole = role;
        if (roleDetailOverlay == null) return;

        String maker       = role.getCreatedBy()   != null ? role.getCreatedBy()   : "—";
        String description = role.getDescription() != null ? role.getDescription() : "—";
        String submittedAt = role.getCreatedAt()   != null
                ? role.getCreatedAt().format(DT_FORMAT) : "—";

        rdRoleName.setValue(role.getRoleName());
        if (rdActionType    != null) rdActionType.setValue("CREATE");
        if (rdMakerId       != null) rdMakerId.setValue(maker);
        if (rdSubmittedAt   != null) rdSubmittedAt.setValue(submittedAt);
        if (rdCurrentDesc   != null) rdCurrentDesc.setValue(description);
        if (rdCurrentStatus != null) rdCurrentStatus.setValue(role.getStatus().name());

        // Permissions list
        if (rdPermListSection != null) rdPermListSection.setVisible(true);
        if (rdPermList != null) {
            rdPermList.getChildren().clear();
            Set<String> permissions = roleService.getAssignedPermissionKeys(role.getId());
            if (permissions.isEmpty()) {
                Label noPerms = new Label("No permissions assigned.");
                noPerms.setStyle("color: var(--cts-text-muted); font-style: italic;");
                rdPermList.appendChild(noPerms);
            } else {
                for (String key : permissions) {
                    Label tag = new Label(key);
                    tag.setSclass("badge badge-active");
                    tag.setStyle("margin:2px;");
                    rdPermList.appendChild(tag);
                }
            }
        }

        // Four-eyes check — disable Approve if checker == maker
        String  currentUserId  = SecurityUtil.getCurrentUserId();
        boolean isSelfApproval = currentUserId != null
                && currentUserId.equals(role.getCreatedBy());
        if (rdSelfWarning       != null) rdSelfWarning.setVisible(isSelfApproval);
        if (btnRoleModalApprove != null) btnRoleModalApprove.setDisabled(isSelfApproval);
        if (btnRoleModalReject  != null) btnRoleModalReject.setDisabled(isSelfApproval);

        if (rdRejectSection != null) rdRejectSection.setVisible(false);
        roleDetailOverlay.setVisible(true);
    }

    // ── Approve ───────────────────────────────────────────────────

    private void doApprove(Role role) {
        Messagebox.show(
                "Approve creation of role \"" + role.getRoleName() + "\"?",
                "Confirm", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (!Messagebox.ON_YES.equals(event.getName())) return;
                    try {
                        roleService.approveRole(role.getId(), SecurityUtil.getCurrentUserId());
                        closeModal();
                        loadPending();
                        Messagebox.show("Role approved and activated successfully.",
                                "Done", Messagebox.OK, Messagebox.INFORMATION);
                    } catch (Exception ex) {
                        Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
                    }
                });
    }

    @Listen("onClick = #btnRoleModalApprove")
    public void onRoleModalApprove() {
        if (selectedRole != null) doApprove(selectedRole);
    }

    // ── Reject ────────────────────────────────────────────────────

    private void doReject(Role role) {
        openDetailModal(role);
        if (rdRejectSection != null) {
            rdRejectSection.setVisible(true);
            if (txtRoleRejectRemarks != null) txtRoleRejectRemarks.setValue("");
            if (btnRoleModalReject   != null) btnRoleModalReject.setLabel("Submit Rejection");
        }
    }

    @Listen("onClick = #btnRoleModalReject")
    public void onRoleModalReject() {
        if (selectedRole == null || rdRejectSection == null) return;

        // First click — show reject form
        if (!rdRejectSection.isVisible()) {
            rdRejectSection.setVisible(true);
            txtRoleRejectRemarks.setValue("");
            txtRoleRejectRemarks.setFocus(true);
            btnRoleModalReject.setLabel("Submit Rejection");
            return;
        }

        // Second click — submit rejection
        String remarks = txtRoleRejectRemarks.getValue().trim();
        if (remarks.isEmpty()) {
            Messagebox.show("Please enter a rejection reason.", "Required",
                    Messagebox.OK, Messagebox.ERROR);
            txtRoleRejectRemarks.setFocus(true);
            return;
        }
        try {
            roleService.rejectRole(
                    selectedRole.getId(), SecurityUtil.getCurrentUserId(), remarks);
            closeModal();
            loadPending();
            Messagebox.show("Role rejected. Record kept in database with status REJECTED.",
                    "Done", Messagebox.OK, Messagebox.INFORMATION);
        } catch (Exception ex) {
            Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
        }
    }

    // ── Close / Refresh ───────────────────────────────────────────

    @Listen("onClick = #btnRoleDetailClose; onClick = #btnRoleModalClose")
    public void onRoleDetailClose() { closeModal(); }

    @Listen("onClick = #btnRefreshRole")
    public void onRefresh() { loadPending(); }

    private void closeModal() {
        if (roleDetailOverlay  != null) roleDetailOverlay.setVisible(false);
        if (btnRoleModalReject != null) btnRoleModalReject.setLabel("✗ Reject");
        selectedRole = null;
    }
}