package com.cts.uam.composer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;

import com.cts.uam.model.Permission;
import com.cts.uam.model.Role;
import com.cts.uam.service.RoleServiceImpl;
import com.cts.util.SecurityUtil;

/**
 * RoleManagementComposer - handles the Role Management page (role-mgmt.zul).
 *
 * The page has two panels:
 * LEFT : Role list with search and pagination (clickable cards)
 * RIGHT : Editor form for the selected role (permissions, description,
 * activate/deactivate)
 *
 * Rules:
 * Create new role -> goes to PENDING status -> checker must approve before it
 * becomes usable
 * Edit a role -> saved directly (no approval needed)
 * Enable/Disable -> saved directly (no approval needed)
 */
public class RoleManagementComposer extends SelectorComposer<Component> {

    // How many role cards to show per page on the left panel
    private static final int ROLE_PAGE_SIZE = 6;

    // ── LEFT PANEL - UI components ────────────────────────────────
    @Wire("#roleListContainer")
    private Div roleListContainer; // role cards are rendered here

    @Wire("#txtRoleSearch")
    private Textbox txtRoleSearch; // search input box

    @Wire("#lblRoleCount")
    private Label lblRoleCount; // shows "12 Roles"

    @Wire("#lblRolePaginationInfo")
    private Label lblRolePaginationInfo; // shows "1-6 of 12"

    @Wire("#lblRolePageNum")
    private Label lblRolePageNum; // shows "Page 1 of 2"

    @Wire("#btnRoleFirstPage")
    private Button btnRoleFirstPage;

    @Wire("#btnRolePrevPage")
    private Button btnRolePrevPage;

    @Wire("#btnRoleNextPage")
    private Button btnRoleNextPage;

    @Wire("#btnRoleLastPage")
    private Button btnRoleLastPage;

    // ── RIGHT PANEL - Editor form ─────────────────────────────────
    @Wire("#permTreeContainer")
    private Div permTreeContainer; // permission module+checkbox tree goes here

    @Wire("#editorEmptyState")
    private Div editorEmptyState; // "Select a role to modify" placeholder

    @Wire("#editorForm")
    private Div editorForm; // the actual edit form

    @Wire("#txtRoleName")
    private Textbox txtRoleName;

    @Wire("#txtRoleDesc")
    private Textbox txtRoleDesc;

    @Wire("#lblEditorTitle")
    private Label lblEditorTitle;

    @Wire("#lblRoleNameHint")
    private Label lblRoleNameHint; // hint text below the role name field

    @Wire("#btnToggleActive")
    private Button btnToggleActive; // shows "Activate" or "Deactivate"

    @Wire("#btnSaveRole")
    private Button btnSaveRole;

    @Wire("#btnDeleteRole")
    private Button btnDeleteRole; // hidden - delete not yet implemented

    // ── State ─────────────────────────────────────────────────────
    private Role selectedRole = null; // which role is open in the editor (null = New Role mode)
    private int currentPage = 0; // which page of the role list is showing
    private long totalRoleCount = 0; // total roles matching current search query

    private final RoleServiceImpl roleService = new RoleServiceImpl();

    // ── INIT ──────────────────────────────────────────────────────

    /**
     * Runs once when the page loads.
     * Shows the "select a role" placeholder on the right and loads roles on the
     * left.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        showEditorEmptyState(); // show "select a role" placeholder on right panel
        loadRolesFromDb(); // load roles from DB and show them on left panel
    }

    // ── LEFT PANEL - Role list + search + pagination ──────────────

    /**
     * Loads roles from DB starting from page 0.
     * Called on page open and after any save or cancel action.
     */
    private void loadRolesFromDb() {
        currentPage = 0;
        applySearchAndRender();
    }

    /**
     * Fires when user types in the search box.
     * Resets to page 0 so search always starts from the first result.
     */
    @Listen("onChanging = #txtRoleSearch; onChange = #txtRoleSearch")
    public void onSearchRoles() {
        currentPage = 0;
        applySearchAndRender();
    }

    /**
     * Counts roles matching the current search query, then renders the current
     * page.
     * DB-level search via LIKE on role_name and description.
     */
    private void applySearchAndRender() {
        String query = txtRoleSearch.getValue().trim().toLowerCase();
        totalRoleCount = roleService.countRoles(query);
        renderCurrentPage();
    }

    /**
     * Fetches only the current page's roles from DB (LIMIT/OFFSET) and renders them
     * as cards.
     * Also updates pagination labels and enables/disables navigation buttons.
     */
    private void renderCurrentPage() {
        roleListContainer.getChildren().clear();

        String query = txtRoleSearch.getValue().trim().toLowerCase();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalRoleCount / ROLE_PAGE_SIZE));

        // Keep page index within valid range
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        int offset = currentPage * ROLE_PAGE_SIZE;
        List<Role> pageRoles = roleService.searchRoles(query, offset, ROLE_PAGE_SIZE);

        int from = offset;
        int to = offset + pageRoles.size();

        // Update info labels
        lblRoleCount.setValue(totalRoleCount + " Roles");
        lblRolePaginationInfo.setValue(
                totalRoleCount == 0 ? "No roles" : (from + 1) + "-" + to + " of " + totalRoleCount);
        lblRolePageNum.setValue((currentPage + 1) + " / " + totalPages);

        // Disable buttons that are not usable on this page
        btnRoleFirstPage.setDisabled(currentPage == 0);
        btnRolePrevPage.setDisabled(currentPage == 0);
        btnRoleNextPage.setDisabled(to >= totalRoleCount);
        btnRoleLastPage.setDisabled(to >= totalRoleCount);

        if (totalRoleCount == 0) {
            roleListContainer.appendChild(buildEmptyListMessage());
            return;
        }

        // Build a card for each role on this page
        for (Role role : pageRoles) {
            roleListContainer.appendChild(buildRoleCard(role));
        }
    }

    // ── Pagination button click handlers ──────────────────────────

    @Listen("onClick = #btnRoleFirstPage")
    public void onClickFirstPage() {
        currentPage = 0;
        renderCurrentPage();
    }

    @Listen("onClick = #btnRolePrevPage")
    public void onClickPrevPage() {
        currentPage--;
        renderCurrentPage();
    }

    @Listen("onClick = #btnRoleNextPage")
    public void onClickNextPage() {
        currentPage++;
        renderCurrentPage();
    }

    @Listen("onClick = #btnRoleLastPage")
    public void onClickLastPage() {
        currentPage = Math.max(0, (int) Math.ceil((double) totalRoleCount / ROLE_PAGE_SIZE) - 1);
        renderCurrentPage();
    }

    // ── Role card builders ────────────────────────────────────────

    /**
     * Returns a "No roles found." message block.
     * Shown when the role list is empty (no roles exist or search has no results).
     */
    private Div buildEmptyListMessage() {
        Div emptyMsg = new Div();
        emptyMsg.setSclass("role-list-empty");
        Label label = new Label("No roles found.");
        label.setSclass("role-list-empty-lbl");
        emptyMsg.appendChild(label);
        return emptyMsg;
    }

    /**
     * Builds one clickable card for a role on the left panel.
     * Shows: role name, description, and Active/Inactive badge.
     * Clicking the card loads that role into the right panel editor.
     */
    private Div buildRoleCard(Role role) {
        // Highlight this card if it is currently open in the editor
        boolean isSelected = selectedRole != null && role.getId().equals(selectedRole.getId());

        Div card = new Div();
        card.setSclass(isSelected ? "role-item selected" : "role-item");
        card.setAttribute("roleId", role.getId()); // stored so we can highlight it later

        // Role name and description block
        Div info = new Div();
        info.setSclass("role-item-info");

        Label nameLabel = new Label(role.getRoleName());
        nameLabel.setSclass("role-item-name");

        Div descDiv = new Div();
        descDiv.setSclass("role-item-desc");
        descDiv.appendChild(new Label(role.getDescription() != null ? role.getDescription() : "-"));

        info.appendChild(nameLabel);
        info.appendChild(descDiv);

        // Active or Inactive status badge
        Label statusBadge = new Label(role.isActive() ? "Active" : "Inactive");
        statusBadge.setSclass(role.isActive() ? "badge badge-active" : "badge badge-inactive");

        card.appendChild(info);
        card.appendChild(statusBadge);
        card.addEventListener("onClick", e -> loadRoleIntoEditor(role));
        return card;
    }

    // ── RIGHT PANEL - Editor ──────────────────────────────────────

    /**
     * Runs when "Add New Role" button is clicked.
     * Clears the form and shows it ready for creating a new role.
     * Permission tree shows all permissions unchecked.
     */
    @Listen("onClick = #btnAddRole")
    public void onClickAddRole() {
        selectedRole = null;
        clearEditorForm();
        txtRoleName.setReadonly(false);
        lblEditorTitle.setValue("NEW ROLE");
        lblRoleNameHint.setValue("Uppercase, underscores only. Cannot be changed after creation.");
        btnToggleActive.setVisible(false);
        btnDeleteRole.setVisible(false);
        buildPermissionTree(null); // null = all checkboxes unchecked
        showEditorForm();
        txtRoleName.setFocus(true);
        refreshSelectionHighlight(null); // no card highlighted on the left
    }

    /**
     * Loads a role into the editor when a card is clicked on the left panel.
     * Fills form fields and shows currently assigned permissions as checked.
     */
    private void loadRoleIntoEditor(Role role) {
        selectedRole = role;

        txtRoleName.setValue(role.getRoleName());
        txtRoleName.setReadonly(true);
        txtRoleDesc.setValue(role.getDescription() != null ? role.getDescription() : "");
        lblEditorTitle.setValue("MODIFY ROLE - " + role.getRoleName());
        lblRoleNameHint.setValue("Role name cannot be changed after creation.");
        btnDeleteRole.setVisible(false);

        // User cannot modify their own role
        boolean isOwnRole = role.getId().equals(SecurityUtil.getCurrentUser().getRoleId());
        if (isOwnRole) {
            lblEditorTitle.setValue("VIEW ROLE - " + role.getRoleName() + "  (read-only)");
            txtRoleDesc.setReadonly(true);
            btnSaveRole.setVisible(false);
            btnToggleActive.setVisible(false);
            // Permission checkboxes bhi disable karo
            setPermissionTreeReadOnly(true);
            showEditorForm();
            refreshSelectionHighlight(role.getId());
            return;
        }

        // Normal edit flow
        txtRoleDesc.setReadonly(false);
        btnSaveRole.setVisible(true);
        setPermissionTreeReadOnly(false);
        setupToggleButton(role);

        Set<String> assignedKeys = roleService.getAssignedPermissionKeys(role.getId());
        buildPermissionTree(assignedKeys);

        showEditorForm();
        refreshSelectionHighlight(role.getId());
    }

    // Enables or disables all checkboxes in the permission tree
    private void setPermissionTreeReadOnly(boolean readOnly) {
        permTreeContainer.getChildren().forEach(child -> child.getChildren().stream()
                .filter(c -> c instanceof Checkbox)
                .map(c -> (Checkbox) c)
                .forEach(cb -> cb.setDisabled(readOnly)));
    }

    /**
     * Sets the label and style of the Activate/Deactivate toggle button
     * based on the role's current status.
     *
     * Active role -> shows "Deactivate" (outline style)
     * Inactive role -> shows "Activate" (green style)
     */
    private void setupToggleButton(Role role) {
        boolean isActive = role.isActive();
        btnToggleActive.setLabel(isActive ? "Deactivate" : "Activate");
        btnToggleActive.setSclass(isActive ? "btn btn-outline btn-sm" : "btn btn-success btn-sm");
        btnToggleActive.setVisible(true);
    }

    // Runs when Cancel is clicked - hides the editor and clears the selection
    @Listen("onClick = #btnCancelRole")
    public void onClickCancelRole() {
        selectedRole = null;
        showEditorEmptyState();
        refreshSelectionHighlight(null);
    }

    /**
     * Runs when Save is clicked.
     *
     * New role -> submitted as PENDING (checker must approve before it becomes
     * ACTIVE)
     * Edit role -> saved directly to DB (no approval needed)
     *
     * Validates form inputs and ensures at least one permission is selected before
     * saving.
     */
    @Listen("onClick = #btnSaveRole")
    public void onClickSaveRole() {
        String roleName = selectedRole != null
                ? selectedRole.getRoleName()
                : txtRoleName.getValue().trim().toUpperCase();
        String description = txtRoleDesc.getValue().trim();

        if (!isEditorInputValid(roleName, description))
            return;

        Set<String> checkedPermissions = collectCheckedPermissions(permTreeContainer);
        if (checkedPermissions.isEmpty()) {
            showError("Please select at least one permission for this role.");
            return;
        }

        String makerId = SecurityUtil.getCurrentUserId();

        try {
            if (selectedRole == null) {
                // CREATE - goes to checker for approval before becoming ACTIVE
                roleService.submitNewRoleForApproval(roleName, description, checkedPermissions, makerId);
                Messagebox.show(
                        "New role submitted for checker approval.\n"
                                + "It will appear in Pending Role Approvals.",
                        "Submitted", Messagebox.OK, Messagebox.INFORMATION,
                        e -> {
                            onClickCancelRole();
                            loadRolesFromDb();
                        });
            } else {
                // EDIT - saved directly, no approval needed
                roleService.updateRoleDirectly(selectedRole.getId(), description, checkedPermissions, makerId);
                Messagebox.show("Role updated successfully.",
                        "Saved", Messagebox.OK, Messagebox.INFORMATION,
                        e -> {
                            onClickCancelRole();
                            loadRolesFromDb();
                        });
            }
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    /**
     * Validates form inputs before saving.
     * Returns false and shows an error if anything is wrong.
     */
    private boolean isEditorInputValid(String roleName, String description) {
        if (selectedRole == null) {
            // New role: run full name validation (format + uniqueness)
            String nameError = roleService.validateNewRoleName(roleName);
            if (nameError != null) {
                showError(nameError);
                txtRoleName.setFocus(true);
                return false;
            }
        } else if (roleName.isEmpty()) {
            showError("Role name is required.");
            txtRoleName.setFocus(true);
            return false;
        }

        String descError = roleService.validateDescription(description);
        if (descError != null) {
            showError(descError);
            return false;
        }
        return true;
    }

    /**
     * Runs when Activate/Deactivate button is clicked.
     * Shows a confirm dialog, then updates the role status in DB.
     * Deactivate will fail (with an error popup) if active users still have this
     * role.
     */
    @Listen("onClick = #btnToggleActive")
    public void onClickToggleActive() {
        if (selectedRole == null)
            return;

        boolean isCurrentlyActive = selectedRole.isActive();
        String confirmMessage = isCurrentlyActive
                ? "Deactivate role \"" + selectedRole.getRoleName() + "\"? Users with this role will lose access."
                : "Activate role \"" + selectedRole.getRoleName() + "\"?";

        Messagebox.show(confirmMessage, "Confirm",
                Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (!Messagebox.ON_YES.equals(event.getName()))
                        return;
                    try {
                        if (isCurrentlyActive) {
                            roleService.deactivateRole(selectedRole.getId());
                        } else {
                            roleService.activateRole(selectedRole.getId());
                        }
                        String doneMsg = "Role " + (isCurrentlyActive ? "deactivated" : "activated") + " successfully.";
                        Messagebox.show(doneMsg, "Done", Messagebox.OK, Messagebox.INFORMATION,
                                e -> {
                                    onClickCancelRole();
                                    loadRolesFromDb();
                                });
                    } catch (Exception ex) {
                        showError(ex.getMessage());
                    }
                });
    }

    // Delete is not implemented yet - button is hidden in the ZUL
    @Listen("onClick = #btnDeleteRole")
    public void onClickDeleteRole() {
        /* intentionally empty - delete not implemented */
    }

    // ── PERMISSION TREE - module-wise grouped checkboxes ──────────

    /**
     * Loads all permissions from DB (grouped by module) and builds the checkbox
     * tree.
     *
     * @param assignedKeys keys of permissions already assigned to this role (shown
     *                     checked).
     *                     Pass null for a new role (all checkboxes start
     *                     unchecked).
     */
    private void buildPermissionTree(Set<String> assignedKeys) {
        permTreeContainer.getChildren().clear();
        Map<String, List<Permission>> groupedByModule = roleService.getPermissionsGroupedByModule();

        // Build one collapsible block per module
        for (Map.Entry<String, List<Permission>> entry : groupedByModule.entrySet()) {
            Div moduleBlock = buildModuleBlock(entry.getKey(), entry.getValue(), assignedKeys);
            permTreeContainer.appendChild(moduleBlock);
        }
    }

    /**
     * Builds one module block in the permission tree.
     *
     * Structure:
     * [module checkbox] [icon] [module name]
     * -> [permission checkbox 1]
     * -> [permission checkbox 2]
     * -> ...
     *
     * Module checkbox state:
     * All assigned -> checked
     * Some assigned -> indeterminate (dash)
     * None assigned -> unchecked
     */
    private Div buildModuleBlock(String moduleName, List<Permission> permissions,
            Set<String> assignedKeys) {

        // Count how many of this module's permissions are already assigned
        long assignedCount = assignedKeys == null ? 0
                : permissions.stream()
                        .filter(p -> assignedKeys.contains(p.getPermissionKey()))
                        .count();

        Checkbox moduleCheckbox = new Checkbox();
        moduleCheckbox.setSclass("perm-module-chk");
        // All assigned -> checked; some assigned -> indeterminate; none -> unchecked
        moduleCheckbox.setChecked(assignedCount == permissions.size() && permissions.size() > 0);
        moduleCheckbox.setIndeterminate(assignedCount > 0 && assignedCount < permissions.size());

        // Build children first - needed to wire events with moduleCheckbox
        Div childrenDiv = buildPermissionChildrenDiv(permissions, assignedKeys, moduleCheckbox);

        // Header row: [checkbox] [icon] [module name]
        Div header = new Div();
        header.setSclass("perm-module-header");

        String[] iconAndClass = resolveModuleIconAndClass(moduleName);
        Label iconLabel = new Label(iconAndClass[0]);
        iconLabel.setSclass("perm-module-icon " + iconAndClass[1]);

        Label nameLabel = new Label(moduleName);
        nameLabel.setSclass("perm-module-name");

        header.appendChild(moduleCheckbox);
        header.appendChild(iconLabel);
        header.appendChild(nameLabel);

        Div moduleBlock = new Div();
        moduleBlock.setSclass("perm-module");
        moduleBlock.appendChild(header);
        moduleBlock.appendChild(childrenDiv);
        return moduleBlock;
    }

    /**
     * Builds the individual permission checkboxes inside a module.
     *
     * Two-way sync is wired here:
     * Child changes -> module checkbox state is recalculated
     * Module clicked -> all children are checked or unchecked together
     */
    private Div buildPermissionChildrenDiv(List<Permission> permissions,
            Set<String> assignedKeys, Checkbox moduleCheckbox) {

        Div childrenDiv = new Div();
        childrenDiv.setSclass("perm-children");

        for (Permission perm : permissions) {
            Checkbox permCheckbox = new Checkbox(perm.getDisplayName());
            permCheckbox.setId("chk_" + perm.getPermissionKey()); // "chk_" prefix marks these as leaf checkboxes
            permCheckbox.setValue(perm.getPermissionKey());
            permCheckbox.setChecked(assignedKeys != null && assignedKeys.contains(perm.getPermissionKey()));

            // When any child changes, recalculate the module checkbox state
            permCheckbox.addEventListener("onCheck",
                    e -> syncModuleCheckboxState(moduleCheckbox, childrenDiv));

            Div row = new Div();
            row.setSclass("perm-child");
            row.appendChild(permCheckbox);
            childrenDiv.appendChild(row);
        }

        // When module checkbox is clicked, check or uncheck all its children together
        moduleCheckbox.addEventListener("onCheck", e -> {
            boolean checked = moduleCheckbox.isChecked();
            for (Component row : childrenDiv.getChildren()) {
                if (row instanceof Div d) {
                    for (Component c : d.getChildren()) {
                        if (c instanceof Checkbox cb)
                            cb.setChecked(checked);
                    }
                }
            }
            moduleCheckbox.setIndeterminate(false);
        });

        return childrenDiv;
    }

    /**
     * Recalculates the module checkbox state after a child checkbox changes.
     *
     * 0 of N checked -> module unchecked
     * N of N checked -> module checked
     * 1 to N-1 checked -> module indeterminate (dash icon)
     */
    private void syncModuleCheckboxState(Checkbox moduleCheckbox, Div childrenDiv) {
        long total = 0, checked = 0;
        for (Component row : childrenDiv.getChildren()) {
            if (row instanceof Div d) {
                for (Component c : d.getChildren()) {
                    // Only count leaf checkboxes (the ones with a "chk_" prefixed id)
                    if (c instanceof Checkbox cb && cb.getValue() != null) {
                        total++;
                        if (cb.isChecked())
                            checked++;
                    }
                }
            }
        }

        if (checked == 0) {
            moduleCheckbox.setChecked(false);
            moduleCheckbox.setIndeterminate(false);
        } else if (checked == total) {
            moduleCheckbox.setChecked(true);
            moduleCheckbox.setIndeterminate(false);
        } else {
            moduleCheckbox.setChecked(false);
            moduleCheckbox.setIndeterminate(true);
        }
    }

    /**
     * Walks the entire permission tree and returns the keys of all checked
     * permissions.
     * Only picks checkboxes whose ID starts with "chk_" (skips module-level
     * checkboxes).
     */
    private Set<String> collectCheckedPermissions(Component parent) {
        Set<String> result = new HashSet<>();
        collectCheckedRecursive(parent, result);
        return result;
    }

    private void collectCheckedRecursive(Component parent, Set<String> result) {
        for (Component child : parent.getChildren()) {
            if (child instanceof Checkbox cb
                    && cb.isChecked()
                    && cb.getId() != null
                    && cb.getId().startsWith("chk_")) {
                String key = cb.getValue() != null ? cb.getValue().toString() : "";
                if (!key.isEmpty())
                    result.add(key);
            }
            collectCheckedRecursive(child, result);
        }
    }

    // ── MODULE ICON HELPER ────────────────────────────────────────

    /**
     * Maps each module name to its icon emoji and CSS class.
     * index 0 = emoji icon, index 1 = CSS class name.
     *
     * Merged from two separate methods (moduleIcon and moduleIconCssClass)
     * that had the same if-else logic repeated twice.
     */
    private static final Map<String, String[]> MODULE_ICON_MAP = Map.of(
            "admin", new String[] { "⚙", "icon-admin" },
            "dashboard", new String[] { "▦", "icon-dashboard" },
            "inward", new String[] { "↙", "icon-inward" },
            "outward", new String[] { "↗", "icon-outward" },
            "report", new String[] { "📊", "icon-reports" },
            "user", new String[] { "👤", "icon-uam" },
            "uam", new String[] { "👤", "icon-uam" });

    /**
     * Finds the icon and CSS class for the given module name.
     * Checks if the module name contains any known keyword.
     * Falls back to the admin icon if nothing matches.
     */
    private String[] resolveModuleIconAndClass(String moduleName) {
        String lower = moduleName.toLowerCase();
        return MODULE_ICON_MAP.entrySet().stream()
                .filter(e -> lower.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(new String[] { "⚙", "icon-admin" });
    }

    // ── UI STATE HELPERS ──────────────────────────────────────────

    // Shows the editor form on the right panel and hides the empty state
    private void showEditorForm() {
        editorEmptyState.setVisible(false);
        editorForm.setVisible(true);
    }

    // Shows the "Select a role to modify" placeholder and hides the editor form
    private void showEditorEmptyState() {
        editorForm.setVisible(false);
        editorEmptyState.setVisible(true);
        lblEditorTitle.setValue("SELECT A ROLE TO MODIFY");
    }

    // Clears the role name and description fields in the editor form
    private void clearEditorForm() {
        txtRoleName.setValue("");
        txtRoleDesc.setValue("");
    }

    /**
     * Highlights the currently selected role card on the left panel.
     * Removes the "selected" CSS class from all other cards.
     *
     * @param selectedRoleId the role to highlight, or null to clear all highlights
     */
    private void refreshSelectionHighlight(Long selectedRoleId) {
        for (Component child : roleListContainer.getChildren()) {
            if (child instanceof Div card) {
                Object cardRoleId = card.getAttribute("roleId");
                boolean isSelected = selectedRoleId != null && selectedRoleId.equals(cardRoleId);
                card.setSclass(isSelected ? "role-item selected" : "role-item");
            }
        }
    }

    // Shows an error popup with the given message
    private void showError(String message) {
        Messagebox.show(message, "Error", Messagebox.OK, Messagebox.ERROR);
    }
}
