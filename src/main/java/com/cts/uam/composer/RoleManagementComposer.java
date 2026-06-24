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
import com.cts.uam.service.RoleService;
import com.cts.util.SecurityUtil;

/**
 * RoleManagementComposer — role-mgmt.zul
 *
 * Left panel  : paginated role list with search filter.
 * Right panel : role editor (name, description, permission tree).
 *
 * Flows:
 *   Create role → PENDING, goes to Pending Role Approvals.
 *   Edit role   → applied directly.
 *   Enable/Disable → applied directly, no maker-checker.
 */
public class RoleManagementComposer extends SelectorComposer<Component> {

    // ── Pagination config ─────────────────────────────────────────
    private static final int ROLE_PAGE_SIZE = 6;

    // ── Wired — Left panel ────────────────────────────────────────
    @Wire("#roleListContainer")
    private Div roleListContainer;
    @Wire("#txtRoleSearch")
    private Textbox txtRoleSearch;
    @Wire("#lblRoleCount")
    private Label lblRoleCount;
    @Wire("#lblRolePaginationInfo")
    private Label lblRolePaginationInfo;
    @Wire("#lblRolePageNum")
    private Label lblRolePageNum;
    @Wire("#btnRoleFirstPage")
    private Button btnRoleFirstPage;
    @Wire("#btnRolePrevPage")
    private Button btnRolePrevPage;
    @Wire("#btnRoleNextPage")
    private Button btnRoleNextPage;
    @Wire("#btnRoleLastPage")
    private Button btnRoleLastPage;

    // ── Wired — Right panel (editor) ──────────────────────────────
    @Wire("#permTreeContainer")
    private Div permTreeContainer;
    @Wire("#editorEmptyState")
    private Div editorEmptyState;
    @Wire("#editorForm")
    private Div editorForm;
    @Wire("#txtRoleName")
    private Textbox txtRoleName;
    @Wire("#txtRoleDesc")
    private Textbox txtRoleDesc;
    @Wire("#lblEditorTitle")
    private Label lblEditorTitle;
    @Wire("#lblRoleNameHint")
    private Label lblRoleNameHint;
    @Wire("#btnToggleActive")
    private Button btnToggleActive;
    @Wire("#btnSaveRole")
    private Button btnSaveRole;
    @Wire("#btnDeleteRole")
    private Button btnDeleteRole;

    // ── State ─────────────────────────────────────────────────────
    private Role selectedRole = null;
    private List<Role> allRoles = new ArrayList<>();
    private List<Role> filteredRoles = new ArrayList<>();
    private int currentRolePage = 0;

    private final RoleService roleService = new RoleService();

    // ── Init ──────────────────────────────────────────────────────

    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);
        showEditorEmptyState();
        loadAndRenderRoleList();
    }

    // ═══════════════════════════════════════════════════════════════
    // LEFT PANEL — Role list + search + pagination
    // ═══════════════════════════════════════════════════════════════


    //this functions runs when page opens
    private void loadAndRenderRoleList() {
        allRoles = roleService.getAllRoles();
        applySearchFilter();
    }
    
    //this runs when we write to search on searchbox
    @Listen("onChanging = #txtRoleSearch; onChange = #txtRoleSearch")
    public void onSearchRoles() {
        currentRolePage = 0;  // reset to first page on new search
        applySearchFilter();
    }

    /** Filter allRoles by search query, then render. */
    //it is used to get role list whether we search or not
    private void applySearchFilter() {
        String query = txtRoleSearch.getValue().trim().toLowerCase();
        if (query.isEmpty()) {
            filteredRoles = new ArrayList<>(allRoles);
        } else {
            filteredRoles = allRoles.stream()
                    .filter(r -> roleMatchesSearch(r, query))
                    .toList();
        }
        lblRoleCount.setValue(allRoles.size() + " Roles");
        implementPaginationInRoleList();
    }

    //it is used when in filter stream try to search role matches with query and it isused to pass true or false based on
    //matches
    private boolean roleMatchesSearch(Role role, String query) {
        return role.getRoleName().toLowerCase().contains(query)
                || (role.getDescription() != null
                        && role.getDescription().toLowerCase().contains(query));
    }

    /** Render only current page slice from filteredRoles. */
    //it is used to implement pagination in role list
    private void implementPaginationInRoleList() {
        roleListContainer.getChildren().clear();

        int total = filteredRoles.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / ROLE_PAGE_SIZE);

        // Clamp page index
        if (currentRolePage >= totalPages) currentRolePage = totalPages - 1;
        if (currentRolePage < 0) currentRolePage = 0;

        int from = currentRolePage * ROLE_PAGE_SIZE;
        int to   = Math.min(from + ROLE_PAGE_SIZE, total);

        // Update pagination labels
        lblRolePaginationInfo.setValue(
                total == 0 ? "No roles" : (from + 1) + "–" + to + " of " + total);
        lblRolePageNum.setValue((currentRolePage + 1) + " / " + totalPages);

        // Disable/enable pagination buttons
        btnRoleFirstPage.setDisabled(currentRolePage == 0);
        btnRolePrevPage.setDisabled(currentRolePage == 0);
        btnRoleNextPage.setDisabled(to >= total);
        btnRoleLastPage.setDisabled(to >= total);

        if (total == 0) {
            roleListContainer.appendChild(buildEmptyListMessage());
            return;
        }

        for (int i = from; i < to; i++) {
            roleListContainer.appendChild(buildRoleListItem(filteredRoles.get(i)));
        }
    }

    // ── Pagination button listeners ───────────────────────────────

    @Listen("onClick = #btnRoleFirstPage")
    public void onClickRoleFirstPage() {
        currentRolePage = 0;
        implementPaginationInRoleList();
    }

    @Listen("onClick = #btnRolePrevPage")
    public void onClickRolePrevPage() {
        currentRolePage--;
        implementPaginationInRoleList();
    }

    @Listen("onClick = #btnRoleNextPage")
    public void onClickRoleNextPage() {
        currentRolePage++;
        implementPaginationInRoleList();
    }

    @Listen("onClick = #btnRoleLastPage")
    public void onClickRoleLastPage() {
        int total = filteredRoles.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / ROLE_PAGE_SIZE);
        currentRolePage = totalPages - 1;
        implementPaginationInRoleList();
    }

    // ── List item builders ────────────────────────────────────────
    // this used to show empty message when role is empty on the place of role list
    private Div buildEmptyListMessage() {
        Div emptyMsg = new Div();
        emptyMsg.setSclass("role-list-empty");
        Label label = new Label("No roles found.");
        label.setSclass("role-list-empty-lbl");
        emptyMsg.appendChild(label);
        return emptyMsg;
    }
    
    //it is used to create role list of each role on list 
    private Div buildRoleListItem(Role role) {
        boolean isSelected = selectedRole != null
                && role.getId().equals(selectedRole.getId());

        Div item = new Div();
        item.setSclass(isSelected ? "role-item selected" : "role-item");
        item.setAttribute("roleId", role.getId());

        // Name + description block
        Div info = new Div();
        info.setSclass("role-item-info");

        Label nameLabel = new Label(role.getRoleName());
        nameLabel.setSclass("role-item-name");

        Div descDiv = new Div();
        descDiv.setSclass("role-item-desc");
        descDiv.appendChild(new Label(
                role.getDescription() != null ? role.getDescription() : "—"));

        info.appendChild(nameLabel);
        info.appendChild(descDiv);

        // Status badge
        Label statusBadge = new Label(role.isActive() ? "Active" : "Inactive");
        statusBadge.setSclass(role.isActive() ? "badge badge-active" : "badge badge-inactive");

        item.appendChild(info);
        item.appendChild(statusBadge);
        item.addEventListener("onClick", e -> loadRoleIntoEditor(role));
        return item;
    }

    // ═══════════════════════════════════════════════════════════════
    // RIGHT PANEL — Editor: Add new role
    // ═══════════════════════════════════════════════════════════════
    //when we click add role button then this is used to load form to create new role
    @Listen("onClick = #btnAddRole")
    public void onClickAddRole() {
        selectedRole = null;
        clearEditorForm();
        txtRoleName.setReadonly(false);
        lblEditorTitle.setValue("NEW ROLE");
        lblRoleNameHint.setValue("Uppercase, underscores only. Cannot be changed after creation.");
        btnToggleActive.setVisible(false);
        btnDeleteRole.setVisible(false);
        buildPermissionTree(null);
        showEditorForm();
        txtRoleName.setFocus(true);
        refreshSelectionHighlight(null);
    }

    // ═══════════════════════════════════════════════════════════════
    // RIGHT PANEL — Editor: Load existing role
    // ═══════════════════════════════════════════════════════════════
    // it is run when when select any role and from role list on right sections
    private void loadRoleIntoEditor(Role role) {
        selectedRole = role;
        txtRoleName.setValue(role.getRoleName());
        txtRoleName.setReadonly(true);
        txtRoleDesc.setValue(role.getDescription() != null ? role.getDescription() : "");
        lblEditorTitle.setValue("MODIFY ROLE — " + role.getRoleName());
        lblRoleNameHint.setValue("Role name cannot be changed after creation.");
        setupToggleButton(role);
        btnDeleteRole.setVisible(false);

        Set<String> assignedKeys = roleService.getAssignedPermissionKeys(role.getId());
        buildPermissionTree(assignedKeys);
        showEditorForm();
        refreshSelectionHighlight(role.getId());
    }
    
    //it is used to show deactivate or activate on button of the toggle button based on on role is active or inactive
    private void setupToggleButton(Role role) {
        boolean isActive = role.isActive();
        btnToggleActive.setLabel(isActive ? "Deactivate" : "Activate");
        btnToggleActive.setSclass(isActive ? "btn btn-outline btn-sm" : "btn btn-success btn-sm");
        btnToggleActive.setVisible(true);
    }

    // ── Cancel ────────────────────────────────────────────────────
    //this function will remove the edit form editor space
    @Listen("onClick = #btnCancelRole")
    public void onClickCancelRole() {
        selectedRole = null;
        showEditorEmptyState();
        refreshSelectionHighlight(null);
    }

    // ── Save ──────────────────────────────────────────────────────
    //this function is called when we create and make changes in roles
    @Listen("onClick = #btnSaveRole")
    public void onClickSaveRole() {
        String roleName = selectedRole != null
                ? selectedRole.getRoleName()
                : txtRoleName.getValue().trim().toUpperCase();
        String description = txtRoleDesc.getValue().trim();

        if (!isEditorInputValid(roleName, description)) return;

        Set<String> checkedPermissions = collectCheckedPermissions(permTreeContainer);
        if (checkedPermissions.isEmpty()) {
            showError("Please select at least one permission for this role.");
            return;
        }

        String makerId = SecurityUtil.getCurrentUserId();

        try {
            if (selectedRole == null) {
                // CREATE → submit for checker approval
                roleService.submitNewRoleForApproval(roleName, description, checkedPermissions, makerId);
                Messagebox.show(
                        "New role submitted for checker approval.\n"
                                + "It will appear in Pending Role Approvals.",
                        "Submitted", Messagebox.OK, Messagebox.INFORMATION,
                        e -> { onClickCancelRole(); loadAndRenderRoleList(); });
            } else {
                // EDIT → applied directly
                roleService.updateRoleDirectly(selectedRole.getId(), description, checkedPermissions, makerId);
                Messagebox.show("Role updated successfully.",
                        "Saved", Messagebox.OK, Messagebox.INFORMATION,
                        e -> { onClickCancelRole(); loadAndRenderRoleList(); });
            }
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private boolean isEditorInputValid(String roleName, String description) {
        if (selectedRole == null) {
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

    // ── Toggle Enable / Disable ───────────────────────────────────

    @Listen("onClick = #btnToggleActive")
    public void onClickToggleActive() {
        if (selectedRole == null) return;

        boolean isCurrentlyActive = selectedRole.isActive();
        String message = isCurrentlyActive
                ? "Deactivate role \"" + selectedRole.getRoleName()
                        + "\"? Users assigned to it will lose access."
                : "Activate role \"" + selectedRole.getRoleName() + "\"?";

        Messagebox.show(message, "Confirm",
                Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
                event -> {
                    if (!Messagebox.ON_YES.equals(event.getName())) return;
                    try {
                        if (isCurrentlyActive) {
                            roleService.deactivateRole(selectedRole.getId());
                        } else {
                            roleService.activateRole(selectedRole.getId());
                        }
                        Messagebox.show(
                                "Role " + (isCurrentlyActive ? "deactivated" : "activated") + " successfully.",
                                "Done", Messagebox.OK, Messagebox.INFORMATION,
                                e -> { onClickCancelRole(); loadAndRenderRoleList(); });
                    } catch (Exception ex) {
                        showError(ex.getMessage());
                    }
                });
    }

    // Stub — no delete in current workflow
    @Listen("onClick = #btnDeleteRole")
    public void onClickDeleteRole() { /* intentionally empty */ }

    // ═══════════════════════════════════════════════════════════════
    // PERMISSION TREE
    // ═══════════════════════════════════════════════════════════════
    //this is used create all permission in div container like tree
    private void buildPermissionTree(Set<String> assignedKeys) {
        permTreeContainer.getChildren().clear();
        Map<String, List<Permission>> grouped = roleService.getPermissionsGroupedByModule();

        for (Map.Entry<String, List<Permission>> entry : grouped.entrySet()) {
            permTreeContainer.appendChild(
                    buildModuleBlock(entry.getKey(), entry.getValue(), assignedKeys));
        }
    }

    //this is the helper method to create permissions like tree in role management
    private Div buildModuleBlock(String moduleName, List<Permission> permissions,
            Set<String> assignedKeys) {

        long assignedCount = assignedKeys == null ? 0
                : permissions.stream()
                        .filter(p -> assignedKeys.contains(p.getPermissionKey()))
                        .count();

        Div moduleBlock = new Div();
        moduleBlock.setSclass("perm-module");

        Checkbox moduleCheckbox = buildModuleCheckbox(assignedCount, permissions.size());
        Div childrenContainer = buildPermissionChildren(permissions, assignedKeys, moduleCheckbox);

        // Module header row: checkbox + icon + name
        Div header = new Div();
        header.setSclass("perm-module-header");

        Label iconLabel = new Label(moduleIcon(moduleName));
        iconLabel.setSclass("perm-module-icon " + moduleIconCssClass(moduleName));

        Label nameLabel = new Label(moduleName);
        nameLabel.setSclass("perm-module-name");

        header.appendChild(moduleCheckbox);
        header.appendChild(iconLabel);
        header.appendChild(nameLabel);

        moduleBlock.appendChild(header);
        moduleBlock.appendChild(childrenContainer);
        return moduleBlock;
    }

    //this is used to create checkbox with 
    private Checkbox buildModuleCheckbox(long assignedCount, int totalCount) {
        Checkbox moduleCheckbox = new Checkbox();
        moduleCheckbox.setSclass("perm-module-chk");
        moduleCheckbox.setChecked(assignedCount == totalCount && totalCount > 0);
        moduleCheckbox.setIndeterminate(assignedCount > 0 && assignedCount < totalCount);
        return moduleCheckbox;
    }
    //
    private Div buildPermissionChildren(List<Permission> permissions,
            Set<String> assignedKeys, Checkbox moduleCheckbox) {

        Div childrenContainer = new Div();
        childrenContainer.setSclass("perm-children");

        for (Permission permission : permissions) {
            Div row = new Div();
            row.setSclass("perm-child");

            Checkbox permCheckbox = new Checkbox(permission.getDisplayName());
            permCheckbox.setId("chk_" + permission.getPermissionKey());
            permCheckbox.setValue(permission.getPermissionKey());
            permCheckbox.setChecked(
                    assignedKeys != null && assignedKeys.contains(permission.getPermissionKey()));
            permCheckbox.addEventListener("onCheck",
                    e -> syncModuleCheckboxState(moduleCheckbox, childrenContainer));

            row.appendChild(permCheckbox);
            childrenContainer.appendChild(row);
        }

        // Module checkbox → check/uncheck all children
        moduleCheckbox.addEventListener("onCheck", e -> {
            boolean checked = moduleCheckbox.isChecked();
            for (Component child : childrenContainer.getChildren()) {
                if (child instanceof Div row) {
                    for (Component c : row.getChildren()) {
                        if (c instanceof Checkbox cb) cb.setChecked(checked);
                    }
                }
            }
            moduleCheckbox.setIndeterminate(false);
        });

        return childrenContainer;
    }

    private void syncModuleCheckboxState(Checkbox moduleCheckbox, Div childrenContainer) {
        long total = 0, checked = 0;
        for (Component child : childrenContainer.getChildren()) {
            if (child instanceof Div row) {
                for (Component c : row.getChildren()) {
                    if (c instanceof Checkbox cb && cb.getValue() != null) {
                        total++;
                        if (cb.isChecked()) checked++;
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
                if (!key.isEmpty()) result.add(key);
            }
            collectCheckedRecursive(child, result);
        }
    }

    // ── Module icon helpers ───────────────────────────────────────

    private String moduleIcon(String moduleName) {
        String lower = moduleName.toLowerCase();
        if (lower.contains("admin") || lower.contains("administration")) return "⚙";
        if (lower.contains("dashboard"))  return "▦";
        if (lower.contains("inward"))     return "↙";
        if (lower.contains("outward"))    return "↗";
        if (lower.contains("report"))     return "📊";
        if (lower.contains("uam") || lower.contains("user")) return "👤";
        return "⚙";
    }

    private String moduleIconCssClass(String moduleName) {
        String lower = moduleName.toLowerCase();
        if (lower.contains("admin") || lower.contains("administration")) return "icon-admin";
        if (lower.contains("dashboard"))  return "icon-dashboard";
        if (lower.contains("inward"))     return "icon-inward";
        if (lower.contains("outward"))    return "icon-outward";
        if (lower.contains("report"))     return "icon-reports";
        if (lower.contains("uam") || lower.contains("user")) return "icon-uam";
        return "icon-admin";
    }

    // ═══════════════════════════════════════════════════════════════
    // UI STATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private void showEditorForm() {
        editorEmptyState.setVisible(false);
        editorForm.setVisible(true);
    }
    //it will show editor form with empty textboxes or when we click on create new roles then it will work.
    private void showEditorEmptyState() {
        editorForm.setVisible(false);
        editorEmptyState.setVisible(true);
        lblEditorTitle.setValue("SELECT A ROLE TO MODIFY");
    }

    private void clearEditorForm() {
        txtRoleName.setValue("");
        txtRoleDesc.setValue("");
    }

    private void refreshSelectionHighlight(Long selectedRoleId) {
        for (Component child : roleListContainer.getChildren()) {
            if (child instanceof Div item) {
                Object itemRoleId = item.getAttribute("roleId");
                boolean isSelected = selectedRoleId != null && selectedRoleId.equals(itemRoleId);
                item.setSclass(isSelected ? "role-item selected" : "role-item");
            }
        }
    }

    private void showError(String message) {
        Messagebox.show(message, "Error", Messagebox.OK, Messagebox.ERROR);
    }
}