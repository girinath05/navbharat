package com.cts.uam.composer;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.*;

import com.cts.uam.dto.UserDTO;
import com.cts.uam.model.Role;
import com.cts.uam.service.RoleService;
import com.cts.uam.service.UserService;
import com.cts.util.SecurityUtil;

/**
 * PendingApprovalComposer — handles pending-approval.zul (USER approvals).
 *
 * Shows all users with status=PENDING.
 * Approve → status becomes ACTIVE.
 * Reject → status becomes REJECTED, reason saved to DB. Row is NOT deleted.
 */
public class PendingApprovalComposer extends SelectorComposer<Component> {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final int PAGE_SIZE = 5;

    /** Session key for temp passwords: userId → temp password string. */
    private static final String SESSION_KEY_TEMP_PW = "navbharat_temp_passwords";

    // ── Wired UI — List ───────────────────────────────────────────
    @Wire("#lstUamApprovals")
    private Listbox lstUamApprovals;
    @Wire("#lblUamCount")
    private Label lblUamCount;
    @Wire("#lblPaginationInfo")
    private Label lblPaginationInfo;
    @Wire("#btnFirstPage")
    private Button btnFirstPage;
    @Wire("#btnPrevPage")
    private Button btnPrevPage;
    @Wire("#btnNextPage")
    private Button btnNextPage;
    @Wire("#btnLastPage")
    private Button btnLastPage;
    @Wire("#lblPageNum")
    private Label lblPageNum;

    // ── Wired UI — Detail Modal ───────────────────────────────────
    @Wire("#detailModalOverlay")
    private Div detailModalOverlay;
    @Wire("#lblDetailTitle")
    private Label lblDetailTitle;
    @Wire("#dUserId")
    private Label dUserId;
    @Wire("#dActionType")
    private Label dActionType;
    @Wire("#dRequestedBy")
    private Label dRequestedBy;
    @Wire("#dUserStatus")
    private Label dUserStatus;
    @Wire("#dFullName")
    private Label dFullName;
    @Wire("#dEmail")
    private Label dEmail;
    @Wire("#dMobile")
    private Label dMobile;
    @Wire("#dRole")
    private Label dRole;
    @Wire("#dCreatedAt")
    private Label dCreatedAt;
    @Wire("#dLockMinutes")
    private Label dLockMinutes;
    @Wire("#dLockRow")
    private Div dLockRow;
    @Wire("#dRejectSection")
    private Div dRejectSection;
    @Wire("#txtRejectRemarks")
    private Textbox txtRejectRemarks;
    @Wire("#dSelfWarning")
    private Div dSelfWarning;
    @Wire("#btnModalApprove")
    private Button btnModalApprove;
    @Wire("#btnModalReject")
    private Button btnModalReject;

    // ── State ─────────────────────────────────────────────────────
    private UserDTO selectedUser = null;
    private int currentPage = 0;
    private List<UserDTO> cachedPendingUsers;

    private final UserService userService = new UserService();
    private final RoleService roleService = new RoleService();

    // ── Init ──────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        loadPending();
    }

    // ── Load Pending Users ────────────────────────────────────────

    private void loadPending() {
        if (lstUamApprovals == null)
            return;

        cachedPendingUsers = userService.getPendingUsers();

        if (lblUamCount != null)
            lblUamCount.setValue("(" + cachedPendingUsers.size() + " pending)");

        renderCurrentPage();
    }

    // ── Pagination ────────────────────────────────────────────────

    private void renderCurrentPage() {
        lstUamApprovals.getItems().clear();
        int total = cachedPendingUsers.size();
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

        Map<Long, String> roleNameMap = buildRoleNameMap();
        for (int i = fromIdx; i < toIdx; i++)
            lstUamApprovals.appendChild(buildPendingUserRow(cachedPendingUsers.get(i), i + 1, roleNameMap));
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
        int total = cachedPendingUsers.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
        currentPage = totalPages - 1;
        renderCurrentPage();
    }

    private Map<Long, String> buildRoleNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Role role : roleService.getAllRoles())
            map.put(role.getId(), role.getRoleName());
        return map;
    }

    // ── Row Builder ───────────────────────────────────────────────

    private Listitem buildPendingUserRow(UserDTO user, int rowNum, Map<Long, String> roleNameMap) {
        Listitem row = new Listitem();
        row.setValue(user);

        String roleName = resolveRoleName(user, roleNameMap);
        String requestedAt = user.getCreatedAt() != null
                ? user.getCreatedAt().format(DT_FORMAT)
                : "—";
        String requestedBy = user.getRequestedBy() != null
                ? user.getRequestedBy()
                : "—";

        row.appendChild(new Listcell(String.valueOf(rowNum))); // #
        row.appendChild(new Listcell(user.getUsername())); // New username
        row.appendChild(new Listcell(user.getFullName())); // New user's full name
        row.appendChild(new Listcell(roleName)); // Assigned role
        row.appendChild(new Listcell(requestedBy)); // Maker who submitted
        row.appendChild(new Listcell(requestedAt)); // When submitted
        row.appendChild(buildActionButtonsCell(user)); // View / Approve / Reject

        return row;
    }

    private Listcell buildActionButtonsCell(UserDTO user) {
        Div actionsDiv = new Div();
        actionsDiv.setSclass("action-cell");

        boolean isSelfRequest = isSelfRequest(user.getRequestedBy());

        Button btnView = new Button("View");
        btnView.setSclass("btn btn-outline btn-sm");
        btnView.addEventListener("onClick", e -> openDetailModal(user));

        Button btnApprove = new Button("\u2713 Approve");
        btnApprove.setSclass("btn btn-success btn-sm");
        btnApprove.setDisabled(isSelfRequest);
        if (isSelfRequest)
            btnApprove.setTooltiptext("You submitted this request — a different user must approve it.");
        btnApprove.addEventListener("onClick", e -> doApprove(user));

        Button btnReject = new Button("\u2715 Reject");
        btnReject.setSclass("btn btn-danger btn-sm");
        btnReject.setDisabled(isSelfRequest);
        if (isSelfRequest)
            btnReject.setTooltiptext("You submitted this request — a different user must reject it.");
        btnReject.addEventListener("onClick", e -> doReject(user));

        actionsDiv.appendChild(btnView);
        actionsDiv.appendChild(btnApprove);
        actionsDiv.appendChild(btnReject);

        Listcell cell = new Listcell();
        cell.appendChild(actionsDiv);
        return cell;
    }

    private boolean isSelfRequest(String requestedBy) {
        String currentUserId = SecurityUtil.getCurrentUserId();
        return currentUserId != null && currentUserId.equals(requestedBy);
    }

    // ── Detail Modal ──────────────────────────────────────────────

    private void openDetailModal(UserDTO user) {
        selectedUser = user;
        if (detailModalOverlay == null)
            return;

        String roleName = resolveRoleName(user, buildRoleNameMap());

        lblDetailTitle.setValue("Pending: Create User — " + user.getUsername());
        dUserId.setValue(user.getUsername());
        if (dActionType != null)
            dActionType.setValue("CREATE");
        if (dRequestedBy != null)
            dRequestedBy.setValue(
                    user.getRequestedBy() != null ? user.getRequestedBy() : "—");
        dUserStatus.setValue(user.getStatus());
        if (dFullName != null)
            dFullName.setValue(
                    user.getFullName() != null ? user.getFullName() : "—");
        if (dEmail != null)
            dEmail.setValue(
                    user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : "—");
        if (dMobile != null)
            dMobile.setValue(
                    user.getMobile() != null && !user.getMobile().isBlank() ? user.getMobile() : "—");
        if (dRole != null)
            dRole.setValue(roleName);
        if (dCreatedAt != null)
            dCreatedAt.setValue(
                    user.getCreatedAt() != null ? user.getCreatedAt().format(DT_FORMAT) : "—");

        // Four-eyes check — disable Approve if checker == maker
        String currentUserId = SecurityUtil.getCurrentUserId();
        boolean isSelfApproval = currentUserId != null
                && currentUserId.equals(user.getRequestedBy());
        if (dSelfWarning != null)
            dSelfWarning.setVisible(isSelfApproval);
        if (btnModalApprove != null)
            btnModalApprove.setDisabled(isSelfApproval);
        if (btnModalReject != null)
            btnModalReject.setDisabled(isSelfApproval);

        if (dLockRow != null)
            dLockRow.setVisible(false);
        if (dRejectSection != null)
            dRejectSection.setVisible(false);
        detailModalOverlay.setVisible(true);
    }

    // ── Approve ───────────────────────────────────────────────────

    private void doApprove(UserDTO user) {
        Messagebox.show(
                "Approve creation of user \"" + user.getUsername() + "\"?",
                "Confirm", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (!Messagebox.ON_YES.equals(event.getName()))
                        return;
                    try {
                        String tempPassword = userService.approveUser(
                                user.getId(), SecurityUtil.getCurrentUserId());

                        closeModal();
                        loadPending();

                        Messagebox.show(
                                "User approved successfully.\n\n"
                                        + "──────────────────────────────\n"
                                        + "Username      : " + user.getUsername() + "\n"
                                        + "Temp Password : " + tempPassword + "\n"
                                        + "──────────────────────────────\n\n"
                                        + "⚠ Copy this password now and give it to the user.\n"
                                        + "It will NOT be shown again.",
                                "User Created — Temporary Password",
                                Messagebox.OK, Messagebox.INFORMATION);

                    } catch (Exception ex) {
                        Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
                    }
                });
    }

    @Listen("onClick = #btnModalApprove")
    public void onModalApprove() {
        if (selectedUser != null)
            doApprove(selectedUser);
    }

    // ── Reject ────────────────────────────────────────────────────

    private void doReject(UserDTO user) {
        openDetailModal(user);
        if (dRejectSection != null) {
            dRejectSection.setVisible(true);
            if (txtRejectRemarks != null)
                txtRejectRemarks.setValue("");
            if (btnModalReject != null)
                btnModalReject.setLabel("Submit Rejection");
        }
    }

    @Listen("onClick = #btnModalReject")
    public void onModalReject() {
        if (selectedUser == null || dRejectSection == null)
            return;

        // First click — show reject form
        if (!dRejectSection.isVisible()) {
            dRejectSection.setVisible(true);
            txtRejectRemarks.setValue("");
            txtRejectRemarks.setFocus(true);
            btnModalReject.setLabel("Submit Rejection");
            return;
        }

        // Second click — submit rejection
        String remarks = txtRejectRemarks.getValue().trim();
        if (remarks.isEmpty()) {
            Messagebox.show("Please enter a rejection reason.", "Required",
                    Messagebox.OK, Messagebox.ERROR);
            txtRejectRemarks.setFocus(true);
            return;
        }
        try {
            userService.rejectUser(
                    selectedUser.getId(), SecurityUtil.getCurrentUserId(), remarks);
            removeTempPasswordFromSession(selectedUser.getId());
            closeModal();
            loadPending();
            Messagebox.show("User rejected. Record kept in database with status REJECTED.",
                    "Done", Messagebox.OK, Messagebox.INFORMATION);
        } catch (Exception ex) {
            Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
        }
    }

    // ── Close / Refresh ───────────────────────────────────────────

    @Listen("onClick = #btnDetailClose; onClick = #btnModalClose")
    public void onDetailClose() {
        closeModal();
    }

    @Listen("onClick = #btnRefresh")
    public void onRefresh() {
        loadPending();
    }

    private void closeModal() {
        if (detailModalOverlay != null)
            detailModalOverlay.setVisible(false);
        if (btnModalReject != null)
            btnModalReject.setLabel("✗ Reject");
        selectedUser = null;
    }

    // ── Private Helper ────────────────────────────────────────────

    private String resolveRoleName(UserDTO user, Map<Long, String> roleNameMap) {
        if (user.getRoleId() != null)
            return roleNameMap.getOrDefault(user.getRoleId(),
                    user.getRoleLabel() != null ? user.getRoleLabel() : "—");
        return user.getRoleLabel() != null ? user.getRoleLabel() : "—";
    }

    // ── Session-scoped Temp Password Storage ──────────────────────

    @SuppressWarnings("unchecked")
    public static void storeTempPassword(
            org.zkoss.zk.ui.Session session, Long userId, String tempPassword) {
        Map<Long, String> map = (Map<Long, String>) session.getAttribute(SESSION_KEY_TEMP_PW);
        if (map == null) {
            map = new HashMap<>();
            session.setAttribute(SESSION_KEY_TEMP_PW, map);
        }
        map.put(userId, tempPassword);
    }

    @SuppressWarnings("unchecked")
    private String getTempPasswordFromSession(Long userId) {
        Map<Long, String> map = (Map<Long, String>) getSelf().getDesktop()
                .getSession().getAttribute(SESSION_KEY_TEMP_PW);
        return map != null ? map.get(userId) : null;
    }

    @SuppressWarnings("unchecked")
    private void removeTempPasswordFromSession(Long userId) {
        Map<Long, String> map = (Map<Long, String>) getSelf().getDesktop()
                .getSession().getAttribute(SESSION_KEY_TEMP_PW);
        if (map != null)
            map.remove(userId);
    }
}