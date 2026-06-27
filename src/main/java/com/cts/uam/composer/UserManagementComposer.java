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
import com.cts.uam.service.RoleServiceImpl;
import com.cts.uam.service.UserServiceImpl;
import com.cts.util.SecurityUtil;

/**
 * UserManagementComposer - handles the User Management page (user-mgmt.zul).
 *
 * This page lets admins manage all user accounts in the system.
 *
 * What you can do here:
 * Create user -> goes to PENDING status -> checker must approve before user can
 * log in
 * Edit user -> saved directly (no approval needed)
 * Change PW -> saved directly (no approval needed)
 * Status change -> Disable / Lock / Unlock / Terminate / Re-open (all direct,
 * no approval)
 */
public class UserManagementComposer extends SelectorComposer<Component> {

    // Date format used for "last login" display: day-month-year hour:minute
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    // How many users to show per page in the table
    private static final int PAGE_SIZE = 5;

    // Year value used to represent "locked indefinitely" (no real expiry)
    private static final int INDEFINITE_LOCK_YEAR = 9999;

    // ── TABLE + FILTER - UI components ───────────────────────────
    @Wire("#lstUsers")
    private Listbox lstUsers; // main user table
    @Wire("#lblUserCount")
    private Label lblUserCount; // shows "15 Users"
    @Wire("#txtFilterUserId")
    private Textbox txtFilterUserId; // filter by username
    @Wire("#txtFilterName")
    private Textbox txtFilterName; // filter by full name
    @Wire("#cmbFilterStatus")
    private Combobox cmbFilterStatus; // filter by status dropdown
    @Wire("#lblPaginationInfo")
    private Label lblPaginationInfo; // shows "1-5 of 15 users"
    @Wire("#lblPageNum")
    private Label lblPageNum; // shows "Page 1 of 3"
    @Wire("#btnFirstPage")
    private Button btnFirstPage;
    @Wire("#btnPrevPage")
    private Button btnPrevPage;
    @Wire("#btnNextPage")
    private Button btnNextPage;
    @Wire("#btnLastPage")
    private Button btnLastPage;

    // ── ADD/EDIT DRAWER - slides in from the right ────────────────
    @Wire("#userDrawer")
    private Div userDrawer; // the drawer panel itself
    @Wire("#lblDrawerTitle")
    private Label lblDrawerTitle; // "Add New User" or "Edit User: john"
    @Wire("#txtUserId")
    private Textbox txtUserId; // username input
    @Wire("#txtFullName")
    private Textbox txtFullName; // full name input
    @Wire("#cmbRole")
    private Combobox cmbRole; // role selection dropdown
    @Wire("#txtEmail")
    private Textbox txtEmail; // email input
    @Wire("#txtMobile")
    private Textbox txtMobile; // mobile number input

    // ── CHANGE PASSWORD MODAL ─────────────────────────────────────
    @Wire("#changePwModal")
    private Div changePwModal; // the modal panel
    @Wire("#lblChangePwUser")
    private Label lblChangePwUser; // shows whose password is changing
    @Wire("#txtAdminNewPw")
    private Textbox txtAdminNewPw; // new password input
    @Wire("#txtAdminConfirmPw")
    private Textbox txtAdminConfirmPw; // confirm new password input
    @Wire("#lblChangePwError")
    private Label lblChangePwError; // inline error message

    // ── State ─────────────────────────────────────────────────────
    private UserDTO editingUser = null; // null = Add mode, non-null = Edit mode
    private UserDTO passwordTargetUser = null; // user whose password is being changed
    private int currentPage = 0;
    private long totalUserCount = 0; // total users matching current filters
    private Map<Long, String> roleNameCache = new HashMap<>(); // roleId -> roleName lookup

    private final UserServiceImpl userService = new UserServiceImpl();
    private final RoleServiceImpl roleService = new RoleServiceImpl();

    // ── INIT ──────────────────────────────────────────────────────

    /**
     * Runs once when the page loads.
     * Fills the role dropdown, sets the default filter, and loads the user list.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        populateRoleDropdown(); // fill the role dropdown in the drawer form
        cmbFilterStatus.setSelectedIndex(0); // default filter = show all statuses
        loadAndRenderUsers(); // load users from DB and show in table
    }

    // ── ROLE DROPDOWN ─────────────────────────────────────────────

    /**
     * Fills the role dropdown in the drawer form with all ACTIVE roles from DB.
     * Only ACTIVE roles are shown - you cannot assign a disabled role to a user.
     */
    private void populateRoleDropdown() {
        cmbRole.getChildren().clear();
        for (Role role : roleService.getActiveRoles()) {
            Comboitem item = new Comboitem(role.getRoleName());
            item.setValue(role.getId());
            item.setDescription(role.getDescription() != null ? role.getDescription() : "");
            cmbRole.appendChild(item);
        }
    }

    // ── SEARCH + FILTER ───────────────────────────────────────────

    // Runs when Search button is clicked - resets to page 0 and reloads with
    // filters
    @Listen("onClick = #btnSearch")
    public void onClickSearch() {
        currentPage = 0;
        loadAndRenderUsers();
    }

    // Runs when Reset button is clicked - clears all filter inputs and reloads
    @Listen("onClick = #btnReset")
    public void onClickReset() {
        txtFilterUserId.setValue("");
        txtFilterName.setValue("");
        cmbFilterStatus.setSelectedIndex(0);
        currentPage = 0;
        loadAndRenderUsers();
    }

    /**
     * Builds the role name cache, counts matching users, then renders the current
     * page.
     * Role name cache (roleId -> roleName) prevents a DB call per row when
     * rendering.
     */
    private void loadAndRenderUsers() {
        // Build role name lookup map once - reused for every row on the current page
        roleNameCache = new HashMap<>();
        for (Role role : roleService.getAllRoles())
            roleNameCache.put(role.getId(), role.getRoleName());

        totalUserCount = currentFilterCount();
        renderCurrentPage();
    }

    // ── PAGINATION ────────────────────────────────────────────────

    // Returns the total count of users matching the current filter inputs
    private long currentFilterCount() {
        String usernameFilter = txtFilterUserId.getValue().trim();
        String nameFilter = txtFilterName.getValue().trim();
        String statusFilter = cmbFilterStatus.getSelectedItem() != null
                ? cmbFilterStatus.getSelectedItem().getValue().toString()
                : "";
        return userService.countUsers(usernameFilter, nameFilter, statusFilter);
    }

    /**
     * Fetches only the current page's rows from DB (LIMIT/OFFSET) and renders them.
     * Updates page labels and enables/disables navigation buttons.
     */
    private void renderCurrentPage() {
        lstUsers.getItems().clear();

        String usernameFilter = txtFilterUserId.getValue().trim();
        String nameFilter = txtFilterName.getValue().trim();
        String statusFilter = cmbFilterStatus.getSelectedItem() != null
                ? cmbFilterStatus.getSelectedItem().getValue().toString()
                : "";

        int totalPages = Math.max(1, (int) Math.ceil((double) totalUserCount / PAGE_SIZE));

        // Keep page in valid range (count can shrink after status actions)
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        int offset = currentPage * PAGE_SIZE;
        List<UserDTO> pageData = userService.searchUsersAsDTO(
                usernameFilter, nameFilter, statusFilter, offset, PAGE_SIZE);

        int from = offset;
        int to = offset + pageData.size();

        // Update info labels
        lblUserCount.setValue(totalUserCount + " Users");
        lblPaginationInfo.setValue(
                "Showing " + (totalUserCount == 0 ? 0 : from + 1) + "-" + to + " of " + totalUserCount + " users");
        lblPageNum.setValue("Page " + (currentPage + 1) + " of " + totalPages);

        // Disable buttons that cannot be used on this page
        btnFirstPage.setDisabled(currentPage == 0);
        btnPrevPage.setDisabled(currentPage == 0);
        btnNextPage.setDisabled(to >= totalUserCount);
        btnLastPage.setDisabled(to >= totalUserCount);

        for (UserDTO user : pageData)
            lstUsers.appendChild(buildUserRow(user));
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
        currentPage = Math.max(0, (int) Math.ceil((double) totalUserCount / PAGE_SIZE) - 1);
        renderCurrentPage();
    }

    // ── USER TABLE ROW BUILDER ────────────────────────────────────

    /**
     * Builds one row in the user table.
     * Columns: Username | Full Name | Role | Status | Last Login | Actions
     */
    private Listitem buildUserRow(UserDTO user) {
        Listitem row = new Listitem();
        row.setValue(user);

        // Username in monospace font to make it visually distinct
        Listcell usernameCell = new Listcell();
        Label usernameLabel = new Label(user.getUsername());
        usernameLabel.setSclass("mono");
        usernameCell.appendChild(usernameLabel);
        row.appendChild(usernameCell);

        row.appendChild(new Listcell(user.getFullName()));
        row.appendChild(new Listcell(resolveRoleName(user)));

        // Status badge - color varies by status (green, red, orange, etc.)
        Listcell statusCell = new Listcell();
        Label statusLabel = new Label(user.getStatus());
        statusLabel.setSclass("badge " + statusToBadgeCss(user.getStatus()));
        statusCell.appendChild(statusLabel);
        row.appendChild(statusCell);

        // Last login time, or "Never" if the user has never logged in
        row.appendChild(new Listcell(
                user.getLastLogin() != null
                        ? user.getLastLogin().format(DATE_TIME_FORMAT)
                        : "Never"));

        // Actions dropdown menu (Edit, Change Password, status actions)
        Listcell actionsCell = new Listcell();
        Div actionsDiv = new Div();
        actionsDiv.setSclass("action-cell");
        buildActionMenu(actionsDiv, user);
        actionsCell.appendChild(actionsDiv);
        row.appendChild(actionsCell);

        return row;
    }

    /**
     * Returns the role name for a user.
     * Tries roleId from the cache first, falls back to roleLabel on the DTO.
     */
    private String resolveRoleName(UserDTO user) {
        if (user.getRoleId() != null)
            return roleNameCache.getOrDefault(user.getRoleId(), "-");
        return user.getRoleLabel() != null ? user.getRoleLabel() : "-";
    }

    /**
     * Returns the CSS class for the status badge based on status value.
     * Controls the badge color in the UI.
     */
    private String statusToBadgeCss(String status) {
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

    // ── ACTION MENU (dropdown per row) ────────────────────────────

    /**
     * Builds the "Actions ▾" dropdown menu for a user row.
     * Menu items change based on the user's current status.
     *
     * Self-restriction: if this row belongs to the currently logged-in user,
     * we show a disabled "You" badge instead of the Actions menu.
     * This prevents an admin from editing/disabling/terminating themselves.
     *
     * Technical note: Menupopup must be a sibling of Button, not a child.
     * setPopup() wires it to open when the button is clicked.
     */
    private void buildActionMenu(Div container, UserDTO user) {
        // Block all actions on the logged-in user's own row
        String loggedInUserId = SecurityUtil.getCurrentUserId();
        if (user.getUsername() != null && user.getUsername().equals(loggedInUserId)) {
            Label selfLabel = new Label("You");
            selfLabel.setSclass("badge badge-self");
            container.appendChild(selfLabel);
            return; // No action menu for self
        }

        String status = user.getStatus() != null ? user.getStatus() : "ACTIVE";
        boolean isTerminated = UserStatus.TERMINATED.name().equals(status);

        Button btnActions = new Button("Actions ▾");
        btnActions.setSclass("btn btn-outline btn-sm btn-actions-trigger");
        container.appendChild(btnActions);

        Menupopup popup = new Menupopup();
        popup.setSclass("actions-menupopup");
        container.appendChild(popup);
        btnActions.setPopup(popup);

        // Terminated users can only be re-opened - no edit or password change
        if (!isTerminated) {
            addMenuItem(popup, "Edit", null, e -> openEditDrawer(user));
            addMenuItem(popup, "Change Password", null, e -> openChangePasswordModal(user));
            popup.appendChild(new Menuseparator());
        }

        addStatusMenuItems(popup, user, status);
        appendLockExpiryLabel(container, user);
    }

    /**
     * Adds the correct status action items to the menu based on current status.
     *
     * ACTIVE -> Disable, Lock, Terminate
     * DISABLED -> Enable, Terminate
     * LOCKED -> Unlock, Terminate
     * TERMINATED -> Re-open
     */
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

    /**
     * For LOCKED users with a timed expiry, shows a small label under the Actions
     * button
     * showing when the lock will expire.
     * Does not show anything for indefinite locks (year 9999).
     */
    private void appendLockExpiryLabel(Div container, UserDTO user) {
        if (!"LOCKED".equals(user.getStatus()))
            return;

        boolean hasTimedExpiry = user.getLockedUntil() != null
                && user.getLockedUntil().getYear() < INDEFINITE_LOCK_YEAR
                && user.getLockedUntil().isAfter(java.time.LocalDateTime.now());

        if (!hasTimedExpiry)
            return;

        Label expiryLabel = new Label("🔒 Until " + user.getLockedUntil().format(DATE_TIME_FORMAT));
        expiryLabel.setSclass("lock-expiry-label");

        Div expiryDiv = new Div();
        expiryDiv.setSclass("lock-expiry-row");
        expiryDiv.appendChild(expiryLabel);
        container.appendChild(expiryDiv);
    }

    /**
     * Helper to add one item to a Menupopup.
     * 
     * @param cssClass pass null for no special styling
     */
    private void addMenuItem(Menupopup popup, String label, String cssClass,
            org.zkoss.zk.ui.event.EventListener<?> onClick) {
        Menuitem item = new Menuitem(label);
        if (cssClass != null)
            item.setSclass(cssClass);
        item.addEventListener("onClick", onClick);
        popup.appendChild(item);
    }

    // ── ADD / EDIT DRAWER ─────────────────────────────────────────

    // Runs when "Add New User" button is clicked - opens drawer in Add mode
    @Listen("onClick = #btnAddUser")
    public void onClickAddUser() {
        editingUser = null;
        clearDrawerForm();
        lblDrawerTitle.setValue("Add New User");
        txtUserId.setReadonly(false);
        openDrawer();
    }

    /**
     * Opens the drawer in Edit mode, pre-filled with the selected user's data.
     * Username is read-only because it cannot be changed after creation.
     */
    private void openEditDrawer(UserDTO user) {
        editingUser = user;
        clearDrawerForm();
        lblDrawerTitle.setValue("Edit User: " + user.getUsername());
        txtUserId.setValue(user.getUsername());
        txtUserId.setReadonly(true); // username cannot be changed
        txtFullName.setValue(user.getFullName());
        txtEmail.setValue(user.getEmail() != null ? user.getEmail() : "");
        txtMobile.setValue(user.getMobile() != null ? user.getMobile() : "");
        preselectRoleInDropdown(user.getRoleId());
        openDrawer();
    }

    /**
     * Selects the user's current role in the dropdown when opening edit mode.
     * Does nothing if roleId is null.
     */
    private void preselectRoleInDropdown(Long roleId) {
        if (roleId == null)
            return;
        cmbRole.getItems().stream()
                .filter(item -> roleId.equals(item.getValue()))
                .findFirst()
                .ifPresent(cmbRole::setSelectedItem);
    }

    // Opens the drawer by adding the "open" CSS class (triggers CSS slide-in
    // animation)
    private void openDrawer() {
        userDrawer.setSclass("user-drawer open");
    }

    // Closes the drawer and clears the editing state
    @Listen("onClick = #btnDrawerClose; onClick = #btnDrawerCancel")
    public void onClickCloseDrawer() {
        userDrawer.setSclass("user-drawer");
        editingUser = null;
    }

    // Clears all input fields in the drawer form
    private void clearDrawerForm() {
        txtUserId.setValue("");
        txtFullName.setValue("");
        txtEmail.setValue("");
        txtMobile.setValue("");
        cmbRole.setSelectedItem(null);
    }

    // ── SAVE USER (Add or Edit) ───────────────────────────────────

    /**
     * Runs when Save is clicked in the drawer.
     * Validates inputs, then either submits a new user for approval
     * or saves edits to an existing user directly.
     */
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

    /**
     * Submits a new user for checker approval (status = PENDING).
     * The user cannot log in until a checker approves.
     */
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

    // Saves edits to an existing user directly to DB (no approval needed)
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

    /**
     * Validates all drawer form inputs before saving.
     * Returns false and shows an error message if anything is invalid.
     *
     * Rules:
     * - Username: validated by service (format + uniqueness) for new users
     * - Full name: required
     * - Email: optional but must be valid format if provided
     * - Mobile: optional but must be 10 or 11 digits if provided
     * - Role: required
     */
    private boolean isDrawerInputValid(String username, String fullName,
            String email, String mobile) {

        if (editingUser == null) {
            // New user: validate username format and uniqueness via service
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

    // ── STATUS ACTIONS (Disable / Lock / Unlock / Terminate / Re-open) ──

    /**
     * Entry point for all status change actions from the Actions menu.
     * Lock is special - it needs a duration dialog first.
     * All other actions go straight to a simple confirm dialog.
     */
    private void confirmStatusAction(UserDTO user, String action) {
        if ("LOCK".equals(action)) {
            showLockDurationDialog(user); // Lock needs extra input for duration
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

    // Returns the appropriate confirmation message for each status action
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

    /**
     * Shows a dialog asking how long to lock the user.
     * Default is 30 minutes. Enter 0 to lock indefinitely (no expiry).
     * After entering the duration, shows a final confirm before locking.
     */
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
        minutesInput.setValue(30); // default: 30 minutes
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
            dialog.detach(); // close the duration dialog

            // Show final confirm before applying the lock
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

    /**
     * Calls the service to apply the status change, then reloads the user list.
     * 
     * @param lockMinutes only used for LOCK (0 = indefinite, positive = timed lock)
     */
    private void executeStatusAction(UserDTO user, String action, Integer lockMinutes) {
        try {
            userService.applyStatusAction(user.getId(), action, lockMinutes);
            loadAndRenderUsers();
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    // ── CHANGE PASSWORD MODAL ─────────────────────────────────────

    /**
     * Opens the Change Password modal for a user.
     * Clears previous input and error message before showing.
     */
    private void openChangePasswordModal(UserDTO user) {
        passwordTargetUser = user;
        lblChangePwUser.setValue(user.getUsername());
        txtAdminNewPw.setValue("");
        txtAdminConfirmPw.setValue("");
        lblChangePwError.setValue("");
        changePwModal.setVisible(true);
    }

    // Closes the Change Password modal and clears the state
    @Listen("onClick = #btnCancelChangePw; onClick = #btnCloseChangePw")
    public void onClickCloseChangePasswordModal() {
        passwordTargetUser = null;
        lblChangePwError.setValue("");
        changePwModal.setVisible(false);
    }

    /**
     * Runs when Submit is clicked in the Change Password modal.
     * Validates that both fields are filled and match, then changes the password.
     * Inline error message is shown if validation fails (no popup).
     */
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
            // Password strength rule violation - show inline (not as a popup)
            lblChangePwError.setValue(ex.getMessage());
        } catch (Exception ex) {
            lblChangePwError.setValue("Failed to change password.");
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────

    // Shows an error popup with the given message
    private void showError(String message) {
        Messagebox.show(message, "Error", Messagebox.OK, Messagebox.ERROR);
    }
}