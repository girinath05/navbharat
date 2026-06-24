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
import com.cts.uam.enums.UserStatus;
import com.cts.uam.model.Role;
import com.cts.uam.service.RoleService;
import com.cts.uam.service.UserService;
import com.cts.util.SecurityUtil;

/**
 * UserManagementComposer — user-mgmt.zul.
 *
 * Flows:
 * - Create user → PENDING, goes to Pending User Approvals.
 * - Edit / Change PW → applied directly (future: will route to maker-checker).
 * - Status actions → applied directly, no maker-checker.
 */
public class UserManagementComposer extends SelectorComposer<Component> {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final int PAGE_SIZE = 5;

    // ── Wired UI — Table & Filters ────────────────────────────────
    @Wire("#lstUsers")
    private Listbox lstUsers;
    @Wire("#lblUserCount")
    private Label lblUserCount;
    @Wire("#txtFilterUserId")
    private Textbox txtFilterUserId;
    @Wire("#txtFilterName")
    private Textbox txtFilterName;
    @Wire("#cmbFilterStatus")
    private Combobox cmbFilterStatus;
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

    // ── Wired UI — Add/Edit Drawer ────────────────────────────────
    @Wire("#userDrawer")
    private Div userDrawer;
    @Wire("#lblDrawerTitle")
    private Label lblDrawerTitle;
    @Wire("#txtUserId")
    private Textbox txtUserId;
    @Wire("#txtFullName")
    private Textbox txtFullName;
    @Wire("#cmbRole")
    private Combobox cmbRole;
    @Wire("#txtEmail")
    private Textbox txtEmail;
    @Wire("#txtMobile")
    private Textbox txtMobile;

    // ── Wired UI — Change Password Modal ──────────────────────────
    @Wire("#changePwModal")
    private Div changePwModal;
    @Wire("#lblChangePwUser")
    private Label lblChangePwUser;
    @Wire("#txtAdminNewPw")
    private Textbox txtAdminNewPw;
    @Wire("#txtAdminConfirmPw")
    private Textbox txtAdminConfirmPw;
    @Wire("#lblChangePwError")
    private Label lblChangePwError;

    // ── State ─────────────────────────────────────────────────────
    private UserDTO editingUser = null; // null = Add mode, non-null = Edit mode
    private UserDTO passwordTargetUser = null;
    private int currentPage = 0;
    private List<UserDTO> cachedUserList;
    private Map<Long, String> roleNameCache = new HashMap<>();

    private final UserService userService = new UserService();
    private final RoleService roleService = new RoleService();

    // ── Init ──────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        populateRoleDropdown();
        cmbFilterStatus.setSelectedIndex(0);
        loadAndRenderUsers();
    }

    // ── Role Dropdown ─────────────────────────────────────────────

    private void populateRoleDropdown() {
        cmbRole.getChildren().clear();
        for (Role role : roleService.getActiveRoles()) {
            Comboitem item = new Comboitem(role.getRoleName());
            item.setValue(role.getId());
            item.setDescription(role.getDescription() != null ? role.getDescription() : "");
            cmbRole.appendChild(item);
        }
    }

    // ── Search / Filter ───────────────────────────────────────────

    @Listen("onClick = #btnSearch")
    public void onClickSearch() {
        currentPage = 0;
        loadAndRenderUsers();
    }

    @Listen("onClick = #btnReset")
    public void onClickReset() {
        txtFilterUserId.setValue("");
        txtFilterName.setValue("");
        cmbFilterStatus.setSelectedIndex(0);
        currentPage = 0;
        loadAndRenderUsers();
    }

    private void loadAndRenderUsers() {
        String usernameFilter = txtFilterUserId.getValue().trim();
        String nameFilter = txtFilterName.getValue().trim();
        String statusFilter = cmbFilterStatus.getSelectedItem() != null
                ? cmbFilterStatus.getSelectedItem().getValue().toString()
                : "";

        cachedUserList = userService.searchUsersAsDTO(usernameFilter, nameFilter, statusFilter);

        roleNameCache = new HashMap<>();
        for (Role role : roleService.getAllRoles())
            roleNameCache.put(role.getId(), role.getRoleName());

        lblUserCount.setValue(cachedUserList.size() + " Users");
        renderCurrentPage();
    }

    // ── Pagination ────────────────────────────────────────────────

    private void renderCurrentPage() {
        lstUsers.getItems().clear();
        int total = cachedUserList.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
        if (currentPage > totalPages - 1)
            currentPage = totalPages - 1; // clamp after filters/actions shrink the list
        if (currentPage < 0)
            currentPage = 0;

        int fromIdx = currentPage * PAGE_SIZE;
        int toIdx = Math.min(fromIdx + PAGE_SIZE, total);

        lblPaginationInfo.setValue(
                "Showing " + (total == 0 ? 0 : fromIdx + 1) + "–" + toIdx + " of " + total + " users");
        lblPageNum.setValue("Page " + (currentPage + 1) + " of " + totalPages);
        btnFirstPage.setDisabled(currentPage == 0);
        btnPrevPage.setDisabled(currentPage == 0);
        btnNextPage.setDisabled(toIdx >= total);
        btnLastPage.setDisabled(toIdx >= total);

        for (int i = fromIdx; i < toIdx; i++)
            lstUsers.appendChild(buildUserRow(cachedUserList.get(i)));
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
        int total = cachedUserList.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
        currentPage = totalPages - 1;
        renderCurrentPage();
    }

    // ── User Table Row ────────────────────────────────────────────

    private Listitem buildUserRow(UserDTO user) {
        Listitem row = new Listitem();
        row.setValue(user);

        // Username (monospace)
        Listcell usernameCell = new Listcell();
        Label usernameLabel = new Label(user.getUsername());
        usernameLabel.setSclass("mono");
        usernameCell.appendChild(usernameLabel);
        row.appendChild(usernameCell);

        row.appendChild(new Listcell(user.getFullName()));

        // Role name
        String roleName = resolveRoleName(user);
        row.appendChild(new Listcell(roleName));

        // Status badge
        Listcell statusCell = new Listcell();
        Label statusLabel = new Label(user.getStatus());
        statusLabel.setSclass("badge " + statusBadgeCssClass(user.getStatus()));
        statusCell.appendChild(statusLabel);
        row.appendChild(statusCell);

        // Last login
        row.appendChild(new Listcell(
                user.getLastLogin() != null ? user.getLastLogin().format(DATE_TIME_FORMAT) : "Never"));

        // Action — single dropdown trigger
        Listcell actionsCell = new Listcell();
        Div actionsDiv = new Div();
        actionsDiv.setSclass("action-cell");
        buildActionMenu(actionsDiv, user);
        actionsCell.appendChild(actionsDiv);
        row.appendChild(actionsCell);

        return row;
    }

    private String resolveRoleName(UserDTO user) {
        if (user.getRoleId() != null)
            return roleNameCache.getOrDefault(user.getRoleId(), "—");
        return user.getRoleLabel() != null ? user.getRoleLabel() : "—";
    }

    private String statusBadgeCssClass(String status) {
        if (status == null)
            return "badge-active";
        return switch (status) {
            case "ACTIVE" -> "badge-active";
            case "DISABLED" -> "badge-disabled";
            case "LOCKED" -> "badge-locked";
            case "TERMINATED" -> "badge-terminated";
            default -> "badge-active";
        };
    }

    // ── Action Menu (single dropdown, replaces the old multi-button grid) ──

    private void buildActionMenu(Div container, UserDTO user) {
        String status = user.getStatus() != null ? user.getStatus() : "ACTIVE";
        boolean isTerminated = UserStatus.TERMINATED.name().equals(status);

        Button btnActions = new Button("Actions ▾");
        btnActions.setSclass("btn btn-outline btn-sm btn-actions-trigger");
        container.appendChild(btnActions);

        // Menupopup must be a sibling, not a child — Button doesn't allow children.
        // setPopup() wires it to open on click in the correct position automatically.
        Menupopup popup = new Menupopup();
        popup.setSclass("actions-menupopup");
        container.appendChild(popup);
        btnActions.setPopup(popup);

        if (!isTerminated) {
            addMenuItem(popup, "Edit", null, e -> openEditDrawer(user));
            addMenuItem(popup, "Change Password", null, e -> openChangePasswordModal(user));
            popup.appendChild(new Menuseparator());
        }

        addStatusMenuItems(popup, user, status);

        appendLockExpiryLabel(container, user);
    }

    private void addStatusMenuItems(Menupopup popup, UserDTO user, String status) {
        switch (status) {
            case "ACTIVE" -> {
                addMenuItem(popup, "Disable", "menu-item-warning", e -> confirmStatusAction(user, "DISABLE"));
                addMenuItem(popup, "Lock", "menu-item-danger", e -> confirmStatusAction(user, "LOCK"));
                addMenuItem(popup, "Terminate", "menu-item-danger", e -> confirmStatusAction(user, "TERMINATE"));
            }
            case "DISABLED" -> {
                addMenuItem(popup, "Enable", "menu-item-success", e -> confirmStatusAction(user, "ENABLE"));
                addMenuItem(popup, "Terminate", "menu-item-danger", e -> confirmStatusAction(user, "TERMINATE"));
            }
            case "LOCKED" -> {
                addMenuItem(popup, "Unlock", "menu-item-success", e -> confirmStatusAction(user, "UNLOCK"));
                addMenuItem(popup, "Terminate", "menu-item-danger", e -> confirmStatusAction(user, "TERMINATE"));
            }
            case "TERMINATED" ->
                addMenuItem(popup, "Re-open", "menu-item-success", e -> confirmStatusAction(user, "OPEN"));
        }
    }

    private void appendLockExpiryLabel(Div container, UserDTO user) {
        // Sirf LOCKED status pe dikhao
        if (!"LOCKED".equals(user.getStatus()))
            return;

        if (user.getLockedUntil() != null
                && user.getLockedUntil().getYear() < INDEFINITE_LOCK_YEAR
                && user.getLockedUntil().isAfter(java.time.LocalDateTime.now())) {

            Label expiryLabel = new Label("🔒 Until " + user.getLockedUntil().format(DATE_TIME_FORMAT));
            expiryLabel.setSclass("lock-expiry-label");

            Div expiryDiv = new Div();
            expiryDiv.setSclass("lock-expiry-row");
            expiryDiv.appendChild(expiryLabel);
            container.appendChild(expiryDiv);
        }
    }

    private static final int INDEFINITE_LOCK_YEAR = 9999;

    private void addMenuItem(Menupopup popup, String label, String cssClass,
            org.zkoss.zk.ui.event.EventListener<?> onClick) {
        Menuitem item = new Menuitem(label);
        if (cssClass != null)
            item.setSclass(cssClass);
        item.addEventListener("onClick", onClick);
        popup.appendChild(item);
    }

    // ── Add / Edit Drawer ─────────────────────────────────────────

    @Listen("onClick = #btnAddUser")
    public void onClickAddUser() {
        editingUser = null;
        clearDrawerForm();
        lblDrawerTitle.setValue("Add New User");
        txtUserId.setReadonly(false);
        openDrawer();
    }

    private void openEditDrawer(UserDTO user) {
        editingUser = user;
        clearDrawerForm();
        lblDrawerTitle.setValue("Edit User: " + user.getUsername());
        txtUserId.setValue(user.getUsername());
        txtUserId.setReadonly(true);
        txtFullName.setValue(user.getFullName());
        txtEmail.setValue(user.getEmail() != null ? user.getEmail() : "");
        txtMobile.setValue(user.getMobile() != null ? user.getMobile() : "");
        preselectRoleInDropdown(user.getRoleId());
        openDrawer();
    }

    private void preselectRoleInDropdown(Long roleId) {
        if (roleId == null)
            return;
        cmbRole.getItems().stream()
                .filter(item -> roleId.equals(item.getValue()))
                .findFirst()
                .ifPresent(cmbRole::setSelectedItem);
    }

    // ── Save User ─────────────────────────────────────────────────

    @Listen("onClick = #btnSaveUser")
    public void onClickSaveUser() {
        String username = txtUserId.getValue().trim().toLowerCase();
        String fullName = txtFullName.getValue().trim();
        String email = txtEmail.getValue().trim();
        String mobile = txtMobile.getValue().trim();

        if (!isDrawerInputValid(username, fullName, email, mobile))
            return;

        Long selectedRoleId = null;
        String selectedRoleName = "";
        if (cmbRole.getSelectedItem() != null) {
            selectedRoleId = ((Number) cmbRole.getSelectedItem().getValue()).longValue();
            selectedRoleName = cmbRole.getSelectedItem().getLabel();
        }

        String makerId = SecurityUtil.getCurrentUserId();

        if (editingUser == null) {
            submitNewUser(username, fullName, email, mobile, selectedRoleId, selectedRoleName, makerId);
        } else {
            saveUserEdits(fullName, email, mobile, selectedRoleId, selectedRoleName);
        }
    }

    private void submitNewUser(String username, String fullName, String email,
            String mobile, Long roleId, String roleName, String makerId) {
        try {
            userService.submitNewUserForApproval(
                    username, fullName, email, mobile, roleId, roleName, makerId);

            Messagebox.show(
                    "User \"" + username + "\" submitted for checker approval.",
                    "Submitted", Messagebox.OK, Messagebox.INFORMATION,
                    e -> {
                        onClickCloseDrawer();
                        loadAndRenderUsers();
                    });

        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private void saveUserEdits(String fullName, String email,
            String mobile, Long roleId, String roleName) {
        try {
            userService.editUser(editingUser.getId(), fullName, email, mobile, roleId, roleName);
            Messagebox.show("User details updated successfully.",
                    "Saved", Messagebox.OK, Messagebox.INFORMATION,
                    e -> {
                        onClickCloseDrawer();
                        loadAndRenderUsers();
                    });
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private boolean isDrawerInputValid(String username, String fullName,
            String email, String mobile) {

        if (editingUser == null) {
            String usernameError = userService.validateNewUsername(username);
            if (usernameError != null) {
                showError(usernameError);
                txtUserId.setFocus(true);
                return false;
            }
        } else if (username.isEmpty()) {
            showError("User ID is required.");
            txtUserId.setFocus(true);
            return false;
        }

        if (fullName.isEmpty()) {
            showError("Full name is required.");
            txtFullName.setFocus(true);
            return false;
        }
        if (!email.isEmpty() && !email.matches("^[\\w.+%-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            showError("Email format is invalid.");
            return false;
        }
        if (!mobile.isEmpty() && !mobile.matches("^\\d{10,11}$")) {
            showError("Mobile must be 10 or 11 digits.");
            txtMobile.setFocus(true);
            return false;
        }
        if (cmbRole.getSelectedItem() == null) {
            showError("Please assign a role.");
            return false;
        }
        return true;
    }

    // ── Status Actions (direct, no maker-checker) ─────────────────

    private void confirmStatusAction(UserDTO user, String action) {
        if ("LOCK".equals(action)) {
            showLockDurationDialog(user);
            return;
        }

        String confirmMessage = buildStatusConfirmMessage(user.getUsername(), action);
        Messagebox.show(confirmMessage, "Confirm",
                Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (Messagebox.ON_YES.equals(event.getName()))
                        executeStatusAction(user, action, null);
                });
    }

    private String buildStatusConfirmMessage(String username, String action) {
        return switch (action) {
            case "ENABLE" -> "Enable user \"" + username + "\"?";
            case "DISABLE" -> "Disable user \"" + username + "\"?";
            case "UNLOCK" -> "Unlock user \"" + username + "\"?";
            case "TERMINATE" -> "Terminate user \"" + username + "\"? This cannot easily be undone.";
            case "OPEN" -> "Re-open user \"" + username + "\"?";
            default -> "Apply \"" + action + "\" to \"" + username + "\"?";
        };
    }

    private void showLockDurationDialog(UserDTO user) {
        Window dialog = new Window();
        dialog.setTitle("Lock \"" + user.getUsername() + "\"");
        dialog.setBorder("normal");
        dialog.setClosable(true);
        dialog.setWidth("340px");
        dialog.setSclass("lock-duration-dlg");

        Vlayout layout = new Vlayout();
        layout.setSclass("lock-dlg-body");

        Label instruction = new Label("Lock duration (minutes). Enter 0 to lock indefinitely.");
        instruction.setMultiline(true);
        layout.appendChild(instruction);

        Intbox minutesInput = new Intbox();
        minutesInput.setValue(30);
        minutesInput.setWidth("100%");
        layout.appendChild(minutesInput);

        Hlayout buttons = new Hlayout();
        buttons.setSclass("lock-dlg-btns");

        Button btnLock = new Button("Lock");
        btnLock.setSclass("btn btn-danger btn-sm");
        btnLock.addEventListener("onClick", e -> {
            Integer minutes = minutesInput.getValue();
            if (minutes == null || minutes < 0) {
                showError("Enter 0 or a positive number.");
                return;
            }
            dialog.detach();
            String confirmMsg = minutes == 0
                    ? "Lock \"" + user.getUsername() + "\" indefinitely?"
                    : "Lock \"" + user.getUsername() + "\" for " + minutes + " minute(s)?";
            Messagebox.show(confirmMsg, "Confirm Lock",
                    Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                    ev -> {
                        if (Messagebox.ON_YES.equals(ev.getName()))
                            executeStatusAction(user, "LOCK", minutes);
                    });
        });

        Button btnCancel = new Button("Cancel");
        btnCancel.setSclass("btn btn-outline btn-sm");
        btnCancel.addEventListener("onClick", e -> dialog.detach());

        buttons.appendChild(btnLock);
        buttons.appendChild(btnCancel);
        layout.appendChild(buttons);
        dialog.appendChild(layout);
        dialog.setPage(getSelf().getPage());
        dialog.doHighlighted();
    }

    private void executeStatusAction(UserDTO user, String action, Integer lockMinutes) {
        try {
            userService.applyStatusAction(user.getId(), action, lockMinutes);
            loadAndRenderUsers();
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    // ── Drawer Helpers ────────────────────────────────────────────

    @Listen("onClick = #btnDrawerClose; onClick = #btnDrawerCancel")
    public void onClickCloseDrawer() {
        userDrawer.setSclass("user-drawer");
        editingUser = null;
    }

    private void openDrawer() {
        userDrawer.setSclass("user-drawer open");
    }

    private void clearDrawerForm() {
        txtUserId.setValue("");
        txtFullName.setValue("");
        txtEmail.setValue("");
        txtMobile.setValue("");
        cmbRole.setSelectedItem(null);
    }

    // ── Change Password Modal ─────────────────────────────────────

    private void openChangePasswordModal(UserDTO user) {
        passwordTargetUser = user;
        lblChangePwUser.setValue(user.getUsername());
        txtAdminNewPw.setValue("");
        txtAdminConfirmPw.setValue("");
        lblChangePwError.setValue("");
        changePwModal.setVisible(true);
    }

    @Listen("onClick = #btnCancelChangePw; onClick = #btnCloseChangePw")
    public void onClickCloseChangePasswordModal() {
        passwordTargetUser = null;
        lblChangePwError.setValue("");
        changePwModal.setVisible(false);
    }

    @Listen("onClick = #btnSubmitChangePw")
    public void onClickSubmitChangePassword() {
        if (passwordTargetUser == null) {
            lblChangePwError.setValue("No target user selected.");
            return;
        }

        String newPassword = txtAdminNewPw.getValue().trim();
        String confirmPassword = txtAdminConfirmPw.getValue().trim();

        if (newPassword.isEmpty() || confirmPassword.isEmpty()) {
            lblChangePwError.setValue("Please enter and confirm the new password.");
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            lblChangePwError.setValue("Passwords do not match.");
            return;
        }

        try {
            userService.changePassword(passwordTargetUser.getId(), newPassword);
            changePwModal.setVisible(false);
            passwordTargetUser = null;
            lblChangePwError.setValue("");
            Messagebox.show("Password changed successfully.",
                    "Done", Messagebox.OK, Messagebox.INFORMATION,
                    e -> loadAndRenderUsers());
        } catch (IllegalArgumentException ex) {
            lblChangePwError.setValue(ex.getMessage());
        } catch (Exception ex) {
            lblChangePwError.setValue("Failed to change password.");
        }
    }

    // ── Shared Helpers ────────────────────────────────────────────

    private void showError(String message) {
        Messagebox.show(message, "Error", Messagebox.OK, Messagebox.ERROR);
    }
}