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
import com.cts.uam.service.RoleServiceImpl;
import com.cts.uam.service.UserService;
import com.cts.uam.service.UserServiceImpl;
import com.cts.util.SecurityUtil;

/**
 * PendingApprovalComposer - handles the Pending User Approvals page
 * (pending-approval.zul).
 *
 * What this page does:
 * - Shows all users with status = PENDING (waiting for checker approval)
 * - Approve -> user becomes ACTIVE, a one-time temp password is shown to the
 * checker
 * - Reject -> user status becomes REJECTED, reason is saved. Row stays in DB.
 *
 * Maker-Checker rule enforced here:
 * The person who created the request (maker) cannot approve or reject it.
 * A different logged-in user (checker) must do it.
 * Approve/Reject buttons are disabled if checker == maker.
 */
public class PendingApprovalComposer extends SelectorComposer<Component> {

    // Date format used on this page: day-month-year hour:minute
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    // How many rows to show per page in the list
    private static final int PAGE_SIZE = 5;

    // ZK session key to hold temp passwords temporarily (userId -> password)
    // Used in case the checker navigates away and comes back before giving the
    // password
    private static final String SESSION_KEY_TEMP_PW = "navbharat_temp_passwords";

    // ── Wired UI — Approval List ──────────────────────────────────
    @Wire("#lstUamApprovals")
    private Listbox lstUamApprovals; // table showing pending users

    @Wire("#lblUamCount")
    private Label lblUamCount; // shows "(3 pending)" count at top

    @Wire("#lblPaginationInfo")
    private Label lblPaginationInfo; // shows "Showing 1-5 of 12 requests"

    @Wire("#btnFirstPage")
    private Button btnFirstPage;

    @Wire("#btnPrevPage")
    private Button btnPrevPage;

    @Wire("#btnNextPage")
    private Button btnNextPage;

    @Wire("#btnLastPage")
    private Button btnLastPage;

    @Wire("#lblPageNum")
    private Label lblPageNum; // shows "Page 1 of 3"

    // ── Wired UI — Detail Modal ───────────────────────────────────
    // Modal that pops up when checker clicks "View" on a pending user row
    @Wire("#detailModalOverlay")
    private Div detailModalOverlay;

    @Wire("#lblDetailTitle")
    private Label lblDetailTitle; // modal header title

    @Wire("#dUserId")
    private Label dUserId; // username of the user being reviewed

    @Wire("#dActionType")
    private Label dActionType; // always "CREATE" for this page

    @Wire("#dRequestedBy")
    private Label dRequestedBy; // the maker who submitted this request

    @Wire("#dUserStatus")
    private Label dUserStatus; // current status (PENDING)

    @Wire("#dFullName")
    private Label dFullName;

    @Wire("#dEmail")
    private Label dEmail;

    @Wire("#dMobile")
    private Label dMobile;

    @Wire("#dRole")
    private Label dRole; // assigned role name

    @Wire("#dCreatedAt")
    private Label dCreatedAt; // when the request was submitted

    @Wire("#dLockMinutes")
    private Label dLockMinutes; // not used for CREATE requests

    @Wire("#dLockRow")
    private Div dLockRow; // hidden on CREATE requests

    @Wire("#dRejectSection")
    private Div dRejectSection; // appears when checker clicks Reject

    @Wire("#txtRejectRemarks")
    private Textbox txtRejectRemarks; // checker types rejection reason here

    @Wire("#dSelfWarning")
    private Div dSelfWarning; // warning shown if checker == maker

    @Wire("#btnModalApprove")
    private Button btnModalApprove;

    @Wire("#btnModalReject")
    private Button btnModalReject;

    // ── State ─────────────────────────────────────────────────────
    private UserDTO selectedUser = null; // user whose modal is currently open
    private int currentPage = 0; // current page index (0-based)
    private long totalPendingCount = 0; // total pending users in DB

    private final UserService userService = new UserServiceImpl();
    private final RoleService roleService = new RoleServiceImpl();

    // ── Init ──────────────────────────────────────────────────────

    /**
     * Runs once when the page loads.
     * Loads all pending users from DB and renders the first page.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        loadPendingUsers();
    }

    // ── Load Pending Users ────────────────────────────────────────

    /**
     * Fetches total pending user count from DB, updates the badge label,
     * then renders the current page of the list.
     * Called on page load and after every approve/reject action.
     */
    private void loadPendingUsers() {
        if (lstUamApprovals == null)
            return;

        totalPendingCount = userService.countPendingUsers();

        // Update the "(N pending)" badge at the top of the page
        if (lblUamCount != null)
            lblUamCount.setValue("(" + totalPendingCount + " pending)");

        renderCurrentPage();
    }

    // ── Pagination ────────────────────────────────────────────────

    /**
     * Fetches only the current page's rows from DB (LIMIT/OFFSET)
     * and redraws the table. Also updates pagination labels and
     * enables/disables navigation buttons.
     */
    private void renderCurrentPage() {
        lstUamApprovals.getItems().clear();

        int totalPages = totalPendingCount == 0 ? 1 : (int) Math.ceil((double) totalPendingCount / PAGE_SIZE);

        // Clamp currentPage so it stays valid after approve/reject shrinks the count
        if (currentPage > totalPages - 1)
            currentPage = totalPages - 1;
        if (currentPage < 0)
            currentPage = 0;

        int offset = currentPage * PAGE_SIZE;
        List<UserDTO> pageUsers = userService.getPendingUsers(offset, PAGE_SIZE);

        int fromIdx = offset;
        int toIdx = offset + pageUsers.size();

        // Update info labels
        if (lblPaginationInfo != null)
            lblPaginationInfo.setValue(
                    "Showing " + (totalPendingCount == 0 ? 0 : fromIdx + 1) + "–" + toIdx + " of " + totalPendingCount
                            + " requests");
        if (lblPageNum != null)
            lblPageNum.setValue("Page " + (currentPage + 1) + " of " + totalPages);

        // Disable navigation buttons at boundaries
        if (btnFirstPage != null)
            btnFirstPage.setDisabled(currentPage == 0);
        if (btnPrevPage != null)
            btnPrevPage.setDisabled(currentPage == 0);
        if (btnNextPage != null)
            btnNextPage.setDisabled(toIdx >= totalPendingCount);
        if (btnLastPage != null)
            btnLastPage.setDisabled(toIdx >= totalPendingCount);

        // Build a role name lookup once so we don't call DB again per row
        Map<Long, String> roleNameMap = buildRoleNameMap();

        // Render one table row per user on this page
        int rowNum = fromIdx + 1;
        for (UserDTO user : pageUsers)
            lstUamApprovals.appendChild(buildTableRow(user, rowNum++, roleNameMap));
    }

    // Go to first page
    @Listen("onClick = #btnFirstPage")
    public void onClickFirstPage() {
        currentPage = 0;
        renderCurrentPage();
    }

    // Go to previous page
    @Listen("onClick = #btnPrevPage")
    public void onClickPrevPage() {
        currentPage--;
        renderCurrentPage();
    }

    // Go to next page
    @Listen("onClick = #btnNextPage")
    public void onClickNextPage() {
        currentPage++;
        renderCurrentPage();
    }

    // Go to last page
    @Listen("onClick = #btnLastPage")
    public void onClickLastPage() {
        int totalPages = totalPendingCount == 0 ? 1 : (int) Math.ceil((double) totalPendingCount / PAGE_SIZE);
        currentPage = totalPages - 1;
        renderCurrentPage();
    }

    /**
     * Builds a map of roleId -> roleName from all active roles.
     * Used to show role names in the table instead of raw IDs.
     */
    private Map<Long, String> buildRoleNameMap() {
        Map<Long, String> map = new HashMap<>();
        for (Role role : roleService.getAllRoles())
            map.put(role.getId(), role.getRoleName());
        return map;
    }

    // ── Row Builder ───────────────────────────────────────────────

    /**
     * Builds one row in the pending approvals table.
     * Columns: # | Username | Full Name | Role | Requested By | Requested At |
     * Actions
     */
    private Listitem buildTableRow(UserDTO user, int rowNum, Map<Long, String> roleNameMap) {
        Listitem row = new Listitem();
        row.setValue(user);

        String roleName = resolveRoleName(user, roleNameMap);
        String requestedAt = user.getCreatedAt() != null ? user.getCreatedAt().format(DT_FORMAT) : "—";
        String requestedBy = user.getRequestedBy() != null ? user.getRequestedBy() : "—";

        row.appendChild(new Listcell(String.valueOf(rowNum))); // row number
        row.appendChild(new Listcell(user.getUsername())); // new username
        row.appendChild(new Listcell(user.getFullName())); // new user's full name
        row.appendChild(new Listcell(roleName)); // assigned role
        row.appendChild(new Listcell(requestedBy)); // maker who submitted
        row.appendChild(new Listcell(requestedAt)); // when submitted
        row.appendChild(buildActionButtonsCell(user)); // View / Approve / Reject buttons

        return row;
    }

    /**
     * Builds the Actions cell for a row: three buttons (View, Approve, Reject).
     *
     * Four-eyes check: if the currently logged-in user submitted this request,
     * Approve and Reject are disabled with a tooltip explaining why.
     */
    private Listcell buildActionButtonsCell(UserDTO user) {
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
     * Returns true if the currently logged-in user is the one who submitted this
     * request.
     * Used to enforce the four-eyes rule on the row buttons.
     */
    private boolean isSelfRequest(String requestedBy) {
        String currentUserId = SecurityUtil.getCurrentUserId();
        return currentUserId != null && currentUserId.equals(requestedBy);
    }

    // ── Detail Modal ──────────────────────────────────────────────

    /**
     * Opens the detail modal for a pending user.
     * Fills in all user fields, applies the four-eyes check, and shows the overlay.
     *
     * If checker == maker, Approve and Reject buttons are disabled and a warning is
     * shown.
     */
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
            dRequestedBy.setValue(user.getRequestedBy() != null ? user.getRequestedBy() : "—");
        if (dUserStatus != null)
            dUserStatus.setValue(user.getStatus());
        if (dFullName != null)
            dFullName.setValue(user.getFullName() != null ? user.getFullName() : "—");
        if (dEmail != null)
            dEmail.setValue(user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : "—");
        if (dMobile != null)
            dMobile.setValue(user.getMobile() != null && !user.getMobile().isBlank() ? user.getMobile() : "—");
        if (dRole != null)
            dRole.setValue(roleName);
        if (dCreatedAt != null)
            dCreatedAt.setValue(user.getCreatedAt() != null ? user.getCreatedAt().format(DT_FORMAT) : "—");

        // Four-eyes check: if checker is the same as maker, disable approve/reject
        String currentUserId = SecurityUtil.getCurrentUserId();
        boolean isSelfApproval = currentUserId != null && currentUserId.equals(user.getRequestedBy());
        if (dSelfWarning != null)
            dSelfWarning.setVisible(isSelfApproval);
        if (btnModalApprove != null)
            btnModalApprove.setDisabled(isSelfApproval);
        if (btnModalReject != null)
            btnModalReject.setDisabled(isSelfApproval);

        // Lock row is only for lock/unlock actions - hide it for CREATE
        if (dLockRow != null)
            dLockRow.setVisible(false);
        if (dRejectSection != null)
            dRejectSection.setVisible(false);

        detailModalOverlay.setVisible(true);
    }

    // ── Approve ───────────────────────────────────────────────────

    /**
     * Shows a "Are you sure?" dialog, then approves the user on confirm.
     * On success: user becomes ACTIVE, service returns a one-time temp password.
     * The temp password is shown in a popup and must be copied now - it won't be
     * shown again.
     */
    private void doApprove(UserDTO user) {
        Messagebox.show(
                "Approve creation of user \"" + user.getUsername() + "\"?",
                "Confirm", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (!Messagebox.ON_YES.equals(event.getName()))
                        return; // user clicked No - do nothing
                    try {
                        String tempPassword = userService.approveUser(
                                user.getId(), SecurityUtil.getCurrentUserId());

                        closeModal();
                        loadPendingUsers(); // reload list so approved user disappears

                        // Show the temp password - checker must copy and give it to the new user
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

    // Approve button inside the detail modal - delegates to doApprove()
    @Listen("onClick = #btnModalApprove")
    public void onModalApprove() {
        if (selectedUser != null)
            doApprove(selectedUser);
    }

    // ── Reject ────────────────────────────────────────────────────

    /**
     * Opens the detail modal and reveals the rejection remarks input.
     * Called when checker clicks Reject directly from the list row (not from inside
     * the modal).
     */
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

    /**
     * Reject button inside the detail modal - works in two clicks:
     * First click -> reveals the rejection remarks input box
     * Second click -> validates the reason and submits the rejection
     */
    @Listen("onClick = #btnModalReject")
    public void onModalReject() {
        if (selectedUser == null || dRejectSection == null)
            return;

        // First click: show the remarks input so checker can type a reason
        if (!dRejectSection.isVisible()) {
            dRejectSection.setVisible(true);
            txtRejectRemarks.setValue("");
            txtRejectRemarks.setFocus(true);
            btnModalReject.setLabel("Submit Rejection");
            return;
        }

        // Second click: validate and submit the rejection
        String remarks = txtRejectRemarks.getValue().trim();
        if (remarks.isEmpty()) {
            Messagebox.show("Please enter a rejection reason.", "Required",
                    Messagebox.OK, Messagebox.ERROR);
            txtRejectRemarks.setFocus(true);
            return;
        }

        try {
            userService.rejectUser(selectedUser.getId(), SecurityUtil.getCurrentUserId(), remarks);
            removeTempPasswordFromSession(selectedUser.getId());
            closeModal();
            loadPendingUsers(); // reload list so rejected user disappears
            Messagebox.show(
                    "User rejected. Record kept in database with status REJECTED.",
                    "Done", Messagebox.OK, Messagebox.INFORMATION);
        } catch (Exception ex) {
            Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
        }
    }

    // ── Close / Refresh ───────────────────────────────────────────

    // Close button on the modal
    @Listen("onClick = #btnDetailClose; onClick = #btnModalClose")
    public void onDetailClose() {
        closeModal();
    }

    // Manual refresh button - reloads the pending users list from DB
    @Listen("onClick = #btnRefresh")
    public void onRefresh() {
        loadPendingUsers();
    }

    /**
     * Hides the detail modal, resets the reject button label, and clears the
     * selected user.
     */
    private void closeModal() {
        if (detailModalOverlay != null)
            detailModalOverlay.setVisible(false);
        if (btnModalReject != null)
            btnModalReject.setLabel("✗ Reject"); // reset label ready for next open
        selectedUser = null;
    }

    // ── Private Helpers ───────────────────────────────────────────

    /**
     * Returns the display name for a user's role.
     * Priority: roleNameMap (from DB) -> roleLabel on the DTO -> "—"
     */
    private String resolveRoleName(UserDTO user, Map<Long, String> roleNameMap) {
        if (user.getRoleId() != null)
            return roleNameMap.getOrDefault(user.getRoleId(),
                    user.getRoleLabel() != null ? user.getRoleLabel() : "—");
        return user.getRoleLabel() != null ? user.getRoleLabel() : "—";
    }

    // ── Session-scoped Temp Password Storage ──────────────────────
    // Temp passwords are stored in the ZK session so the checker can
    // retrieve them if they navigate away and come back before giving
    // the password to the new user.

    // Stores a temp password in the ZK session for the given userId
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

    // Retrieves a previously stored temp password from the session
    @SuppressWarnings("unchecked")
    private String getTempPasswordFromSession(Long userId) {
        Map<Long, String> map = (Map<Long, String>) getSelf().getDesktop()
                .getSession().getAttribute(SESSION_KEY_TEMP_PW);
        return map != null ? map.get(userId) : null;
    }

    // Removes the temp password from the session after approve or reject completes
    @SuppressWarnings("unchecked")
    private void removeTempPasswordFromSession(Long userId) {
        Map<Long, String> map = (Map<Long, String>) getSelf().getDesktop()
                .getSession().getAttribute(SESSION_KEY_TEMP_PW);
        if (map != null)
            map.remove(userId);
    }
}
