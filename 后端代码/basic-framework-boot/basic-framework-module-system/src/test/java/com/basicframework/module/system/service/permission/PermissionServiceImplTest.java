package com.basicframework.module.system.service.permission;

import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.ROLE_PERMISSION_CHANGED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_ROLE_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleMenuDO;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.permission.RoleMenuMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PermissionServiceImpl} 授权流程的单元测试
 *
 * 覆盖关联表物理删除契约：三张关联表（system_user_role、
 * system_role_menu、system_user_post）已加 V3 组合唯一约束，取消授权必须物理删除，
 * 否则残留 deleted=1 的行会让再次授权的 insertBatch 撞唯一键。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Spy
    @InjectMocks
    private PermissionServiceImpl permissionService;

    @Mock
    private RoleMenuMapper roleMenuMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleService roleService;

    @Mock
    private MenuService menuService;

    @Mock
    private DeptService deptService;

    @Mock
    private AdminUserService userService;

    @Mock
    private UserSessionRevocationPublisher sessionRevocationPublisher;

    // ========== 用户-角色：授权 -> 取消 -> 再授权 ==========

    @Test
    void assignUserRole_revokeThenReassign_noUniqueKeyConflict() {
        Long operatorUserId = 9001L;
        Long userId = 1001L;
        Long roleId = 201L;

        // 1. 首次授权：库中无关联，直接 insertBatch
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(Collections.emptyList());
        permissionService.assignUserRole(operatorUserId, userId, Set.of(roleId));
        verify(userRoleMapper)
                .insertBatch(argThat(list ->
                        list.size() == 1 && roleId.equals(list.iterator().next().getRoleId())));

        // 2. 取消授权：走物理删除方法，物理删后该行不存在（count 为 0），而非 deleted=1 残留
        UserRoleDO dbRole = new UserRoleDO();
        dbRole.setUserId(userId);
        dbRole.setRoleId(roleId);
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(List.of(dbRole));
        permissionService.assignUserRole(operatorUserId, userId, Set.of());
        verify(userRoleMapper).deleteListByUserIdAndRoleIdIds(eq(userId), argThat(roleIds -> roleIds.contains(roleId)));

        // 3. 再次授权：物理删后查询无残留，insertBatch 可重复执行，不撞 uk_user_id_role_id 唯一键
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(Collections.emptyList());
        permissionService.assignUserRole(operatorUserId, userId, Set.of(roleId));
        verify(userRoleMapper, times(2))
                .insertBatch(argThat(list ->
                        list.size() == 1 && roleId.equals(list.iterator().next().getRoleId())));
        verify(sessionRevocationPublisher, times(3)).revokeAdminSession(userId, USER_ROLE_CHANGED);
        verify(userService, times(3)).validateUserList(Set.of(userId));
        verify(roleService, times(3))
                .validateRoleList(argThat(roleIds -> roleIds.isEmpty() || roleIds.contains(roleId)));
    }

    @Test
    void assignUserRole_validatesTargetsBeforeReadingRelationships() {
        Long operatorUserId = 9001L;
        Long userId = 1001L;
        Set<Long> roleIds = Set.of(201L);

        permissionService.assignUserRole(operatorUserId, userId, roleIds);

        verify(userService).validateUserList(Set.of(userId));
        verify(roleService).validateRoleList(roleIds);
    }

    @Test
    void assignUserRole_nonSuperAdminCannotGrantSuperAdminRole() {
        Long operatorUserId = 9001L;
        Long userId = 1001L;
        Long superAdminRoleId = 1L;
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(Collections.emptyList());
        when(roleService.hasAnySuperAdmin(Set.of(superAdminRoleId))).thenReturn(true);
        doReturn(false).when(permissionService).hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode());

        assertThatThrownBy(() -> permissionService.assignUserRole(operatorUserId, userId, Set.of(superAdminRoleId)))
                .hasMessageContaining("只有超级管理员");

        verify(userRoleMapper, never()).insertBatch(argThat(list -> true));
    }

    @Test
    void validatePrivilegedUserMutation_nonSuperAdminCannotTouchSuperAdminUser() {
        doReturn(false).when(permissionService).hasAnyRoles(9001L, RoleCodeEnum.SUPER_ADMIN.getCode());
        doReturn(true).when(permissionService).hasAnyRoles(1001L, RoleCodeEnum.SUPER_ADMIN.getCode());

        assertThatThrownBy(() -> permissionService.validatePrivilegedUserMutation(9001L, List.of(1001L)))
                .hasMessageContaining("只有超级管理员");
    }

    @Test
    void validatePrivilegedUserMutation_superAdminOperatorOrPlainTargetPasses() {
        doReturn(true).when(permissionService).hasAnyRoles(9001L, RoleCodeEnum.SUPER_ADMIN.getCode());
        permissionService.validatePrivilegedUserMutation(9001L, List.of(1001L));

        doReturn(false).when(permissionService).hasAnyRoles(9002L, RoleCodeEnum.SUPER_ADMIN.getCode());
        doReturn(false).when(permissionService).hasAnyRoles(1002L, RoleCodeEnum.SUPER_ADMIN.getCode());
        permissionService.validatePrivilegedUserMutation(9002L, List.of(1002L));
    }

    @Test
    void validatePrivilegedUserMutation_missingOperatorIsNotPrivileged() {
        doReturn(true).when(permissionService).hasAnyRoles(1001L, RoleCodeEnum.SUPER_ADMIN.getCode());

        assertThatThrownBy(() -> permissionService.validatePrivilegedUserMutation(null, List.of(1001L)))
                .hasMessageContaining("只有超级管理员");
    }

    @Test
    void assignUserRole_nonSuperAdminCannotRevokeSuperAdminRole() {
        Long operatorUserId = 9001L;
        Long userId = 1001L;
        Long superAdminRoleId = 1L;
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(List.of(userRole(userId, superAdminRoleId)));
        when(roleService.hasAnySuperAdmin(Set.of(superAdminRoleId))).thenReturn(true);
        doReturn(false).when(permissionService).hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode());

        assertThatThrownBy(() -> permissionService.assignUserRole(operatorUserId, userId, Set.of()))
                .hasMessageContaining("只有超级管理员");

        verify(userRoleMapper, never()).deleteListByUserIdAndRoleIdIds(eq(userId), argThat(roleIds -> true));
    }

    @Test
    void assignUserRole_superAdminCanGrantSuperAdminRole() {
        Long operatorUserId = 1L;
        Long userId = 1001L;
        Long superAdminRoleId = 1L;
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(Collections.emptyList());
        when(roleService.hasAnySuperAdmin(Set.of(superAdminRoleId))).thenReturn(true);
        doReturn(true).when(permissionService).hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode());

        permissionService.assignUserRole(operatorUserId, userId, Set.of(superAdminRoleId));

        verify(userRoleMapper)
                .insertBatch(argThat(list -> list.size() == 1
                        && superAdminRoleId.equals(list.iterator().next().getRoleId())));
        verify(sessionRevocationPublisher).revokeAdminSession(userId, USER_ROLE_CHANGED);
    }

    // ========== 角色-菜单：授权 -> 取消 -> 再授权 ==========

    @Test
    void assignRoleMenu_revokeThenReassign_noUniqueKeyConflict() {
        Long operatorUserId = 9001L;
        Long roleId = 301L;
        Long menuId = 401L;
        when(menuService.getMenuList(Set.of(menuId))).thenReturn(List.of(enabledMenu(menuId)));

        // 1. 首次授权：库中无关联，直接 insertBatch
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(Collections.emptyList());
        permissionService.assignRoleMenu(operatorUserId, roleId, Set.of(menuId));
        verify(roleMenuMapper)
                .insertBatch(argThat(list ->
                        list.size() == 1 && menuId.equals(list.iterator().next().getMenuId())));

        // 2. 取消授权：走物理删除方法，物理删后该行不存在（count 为 0），而非 deleted=1 残留
        RoleMenuDO dbMenu = new RoleMenuDO();
        dbMenu.setRoleId(roleId);
        dbMenu.setMenuId(menuId);
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(List.of(dbMenu));
        permissionService.assignRoleMenu(operatorUserId, roleId, Set.of());
        verify(roleMenuMapper).deleteListByRoleIdAndMenuIds(eq(roleId), argThat(menuIds -> menuIds.contains(menuId)));

        // 3. 再次授权：物理删后查询无残留，insertBatch 可重复执行，不撞 uk_role_id_menu_id 唯一键
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(Collections.emptyList());
        permissionService.assignRoleMenu(operatorUserId, roleId, Set.of(menuId));
        verify(roleMenuMapper, times(2))
                .insertBatch(argThat(list ->
                        list.size() == 1 && menuId.equals(list.iterator().next().getMenuId())));
        verify(roleService, times(3)).validateRoleList(Set.of(roleId));
    }

    @Test
    void assignRoleMenu_changed_revokesAffectedUserSessions() {
        Long operatorUserId = 9001L;
        Long roleId = 301L;
        Long menuId = 401L;
        Long userId = 1001L;
        when(menuService.getMenuList(Set.of(menuId))).thenReturn(List.of(enabledMenu(menuId)));
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(Collections.emptyList());
        UserRoleDO userRole = new UserRoleDO().setUserId(userId).setRoleId(roleId);
        when(userRoleMapper.selectListByRoleIds(Set.of(roleId))).thenReturn(List.of(userRole));

        permissionService.assignRoleMenu(operatorUserId, roleId, Set.of(menuId));

        verify(sessionRevocationPublisher).revokeAdminSessions(Set.of(userId), ROLE_PERMISSION_CHANGED);
    }

    @Test
    void assignRoleMenu_rejectsMissingMenuBeforeReadingRelationships() {
        Long operatorUserId = 9001L;
        Long roleId = 301L;
        Long menuId = 401L;
        when(menuService.getMenuList(Set.of(menuId))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> permissionService.assignRoleMenu(operatorUserId, roleId, Set.of(menuId)))
                .hasMessageContaining("菜单不存在");

        verify(roleService).validateRoleList(Set.of(roleId));
        verify(roleMenuMapper, never()).selectListByRoleId(roleId);
    }

    @Test
    void assignRoleMenu_rejectsDisabledMenuBeforeReadingRelationships() {
        Long operatorUserId = 9001L;
        Long roleId = 301L;
        Long menuId = 401L;
        MenuDO disabledMenu = enabledMenu(menuId);
        disabledMenu.setName("已停用菜单");
        disabledMenu.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(menuService.getMenuList(Set.of(menuId))).thenReturn(List.of(disabledMenu));

        assertThatThrownBy(() -> permissionService.assignRoleMenu(operatorUserId, roleId, Set.of(menuId)))
                .hasMessageContaining("已被禁用");

        verify(roleMenuMapper, never()).selectListByRoleId(roleId);
    }

    @Test
    void assignRoleMenu_nonSuperAdminCannotChangeSuperAdminRole() {
        Long operatorUserId = 9001L;
        Long superAdminRoleId = 1L;
        Long menuId = 401L;
        when(roleService.hasAnySuperAdmin(Set.of(superAdminRoleId))).thenReturn(true);
        doReturn(false).when(permissionService).hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode());

        assertThatThrownBy(() -> permissionService.assignRoleMenu(operatorUserId, superAdminRoleId, Set.of(menuId)))
                .hasMessageContaining("只有超级管理员");

        verify(menuService, never()).getMenuList(Set.of(menuId));
        verify(roleMenuMapper, never()).selectListByRoleId(superAdminRoleId);
    }

    @Test
    void getUserRoleIdListByRoleId_emptyRoleIds_skipsDatabaseQuery() {
        assertEquals(Collections.emptySet(), permissionService.getUserRoleIdListByRoleId(Collections.emptySet()));

        verify(userRoleMapper, never()).selectListByRoleIds(Collections.emptySet());
    }

    @Test
    void hasAnyPermissions_matchesMenuRoleAndRejectsUnknownPermission() {
        Long userId = 1001L;
        RoleDO role = role(201L, "auditor", DataScopeEnum.SELF, CommonStatusEnum.ENABLE);
        doReturn(List.of(role)).when(permissionService).getEnableUserRoleListByUserId(userId);
        when(menuService.getMenuIdListByPermission("system:audit:query")).thenReturn(List.of(401L));
        when(menuService.getMenuIdListByPermission("system:unknown")).thenReturn(Collections.emptyList());
        when(menuService.getMenuIdListByPermission("system:audit:update")).thenReturn(List.of(402L));
        when(roleMenuMapper.selectListByMenuId(401L)).thenReturn(List.of(roleMenu(role.getId(), 401L)));
        when(roleMenuMapper.selectListByMenuId(402L)).thenReturn(List.of(roleMenu(999L, 402L)));

        assertThat(permissionService.hasAnyPermissions(userId, "system:audit:query"))
                .isTrue();
        assertThat(permissionService.hasAnyPermissions(userId, "system:unknown"))
                .isFalse();
        assertThat(permissionService.hasAnyPermissions(userId, "system:audit:update"))
                .isFalse();
        assertThat(permissionService.hasAnyPermissions(userId)).isTrue();
    }

    @Test
    void hasAnyPermissions_superAdminFallbackAndNoRoleFailClosed() {
        Long userId = 1001L;
        RoleDO role = role(1L, "super_admin", DataScopeEnum.ALL, CommonStatusEnum.ENABLE);
        doReturn(List.of(role)).when(permissionService).getEnableUserRoleListByUserId(userId);
        when(menuService.getMenuIdListByPermission("system:unregistered")).thenReturn(Collections.emptyList());
        when(roleService.hasAnySuperAdmin(Set.of(role.getId()))).thenReturn(true);

        assertThat(permissionService.hasAnyPermissions(userId, "system:unregistered"))
                .isTrue();

        doReturn(Collections.emptyList()).when(permissionService).getEnableUserRoleListByUserId(userId);
        assertThat(permissionService.hasAnyPermissions(userId, "system:unregistered"))
                .isFalse();
    }

    @Test
    void hasAnyRoles_matchesEnabledRoleOnly() {
        Long userId = 1001L;
        RoleDO role = role(201L, "auditor", DataScopeEnum.SELF, CommonStatusEnum.ENABLE);
        doReturn(List.of(role)).when(permissionService).getEnableUserRoleListByUserId(userId);

        assertThat(permissionService.hasAnyRoles(userId, "operator", "auditor")).isTrue();
        assertThat(permissionService.hasAnyRoles(userId, "operator")).isFalse();
        assertThat(permissionService.hasAnyRoles(userId)).isTrue();

        doReturn(Collections.emptyList()).when(permissionService).getEnableUserRoleListByUserId(userId);
        assertThat(permissionService.hasAnyRoles(userId, "auditor")).isFalse();
    }

    @Test
    void getEnableUserRoleList_filtersDisabledRolesWithoutMutatingCacheResult() {
        Long userId = 1001L;
        RoleDO enabled = role(201L, "auditor", DataScopeEnum.SELF, CommonStatusEnum.ENABLE);
        RoleDO disabled = role(202L, "retired", DataScopeEnum.SELF, CommonStatusEnum.DISABLE);
        when(userRoleMapper.selectListByUserId(userId))
                .thenReturn(List.of(userRole(userId, enabled.getId()), userRole(userId, disabled.getId())));
        List<RoleDO> cachedRoles = List.of(enabled, disabled);
        when(roleService.getRoleList(Set.of(enabled.getId(), disabled.getId()))).thenReturn(cachedRoles);

        assertThat(permissionService.getEnableUserRoleListByUserId(userId)).containsExactly(enabled);
        assertThat(cachedRoles).containsExactly(enabled, disabled);
    }

    @Test
    void getRoleMenuList_handlesEmptySuperAdminAndRegularRoles() {
        assertThat(permissionService.getRoleMenuListByRoleId(Collections.emptySet()))
                .isEmpty();

        MenuDO menu = new MenuDO();
        menu.setId(401L);
        when(roleService.hasAnySuperAdmin(Set.of(1L))).thenReturn(true);
        when(menuService.getMenuList()).thenReturn(List.of(menu));
        assertThat(permissionService.getRoleMenuListByRoleId(Set.of(1L))).containsExactly(401L);

        when(roleService.hasAnySuperAdmin(Set.of(201L))).thenReturn(false);
        when(roleMenuMapper.selectListByRoleId(Set.of(201L))).thenReturn(List.of(roleMenu(201L, 402L)));
        assertThat(permissionService.getRoleMenuListByRoleId(Set.of(201L))).containsExactly(402L);
    }

    @Test
    void processRoleAndMenuDeleted_removeRelationsBeforeRevokingSessions() {
        Long roleId = 201L;
        Long menuId = 401L;
        Long userId = 1001L;
        when(userRoleMapper.selectListByRoleIds(Set.of(roleId))).thenReturn(List.of(userRole(userId, roleId)));
        when(roleMenuMapper.selectListByMenuId(menuId)).thenReturn(List.of(roleMenu(roleId, menuId)));

        permissionService.processRoleDeleted(roleId);
        permissionService.processMenuDeleted(menuId);

        verify(userRoleMapper).deleteListByRoleId(roleId);
        verify(roleMenuMapper).deleteListByRoleId(roleId);
        verify(roleMenuMapper).deleteListByMenuId(menuId);
        verify(sessionRevocationPublisher, times(2)).revokeAdminSessions(Set.of(userId), ROLE_PERMISSION_CHANGED);
    }

    @Test
    void processUserDeletedAndAssignRoleDataScope_delegateToOwningServices() {
        Long userId = 1001L;
        Long roleId = 201L;
        Set<Long> deptIds = Set.of(7L, 8L);

        permissionService.processUserDeleted(userId);
        permissionService.assignRoleDataScope(9001L, roleId, DataScopeEnum.DEPT_CUSTOM.getScope(), deptIds);

        verify(userRoleMapper).deleteListByUserId(userId);
        verify(roleService).updateRoleDataScope(roleId, DataScopeEnum.DEPT_CUSTOM.getScope(), deptIds);
    }

    @Test
    void assignRoleDataScope_nonSuperAdminCannotChangeSuperAdminRole() {
        Long operatorUserId = 9001L;
        Long superAdminRoleId = 1L;
        when(roleService.hasAnySuperAdmin(Set.of(superAdminRoleId))).thenReturn(true);
        doReturn(false).when(permissionService).hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode());

        assertThatThrownBy(() -> permissionService.assignRoleDataScope(
                        operatorUserId, superAdminRoleId, DataScopeEnum.ALL.getScope(), Set.of()))
                .hasMessageContaining("只有超级管理员");

        verify(roleService, never()).updateRoleDataScope(superAdminRoleId, DataScopeEnum.ALL.getScope(), Set.of());
    }

    @Test
    void getDeptDataPermission_combinesEverySupportedScopeAndMemoizesUserDepartment() {
        Long userId = 1001L;
        List<RoleDO> roles = List.of(
                role(1L, "all", DataScopeEnum.ALL, CommonStatusEnum.ENABLE),
                role(2L, "custom", DataScopeEnum.DEPT_CUSTOM, CommonStatusEnum.ENABLE, Set.of(7L)),
                role(3L, "department", DataScopeEnum.DEPT_ONLY, CommonStatusEnum.ENABLE),
                role(4L, "children", DataScopeEnum.DEPT_AND_CHILD, CommonStatusEnum.ENABLE),
                role(5L, "self", DataScopeEnum.SELF, CommonStatusEnum.ENABLE));
        doReturn(roles).when(permissionService).getEnableUserRoleListByUserId(userId);
        when(userService.getUser(userId))
                .thenReturn(AdminUserDO.builder().id(userId).deptId(8L).build());
        when(deptService.getChildDeptIdList(8L)).thenReturn(Set.of(9L, 10L));

        DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);

        assertThat(result.getAll()).isTrue();
        assertThat(result.getSelf()).isTrue();
        assertThat(result.getDeptIds()).containsExactlyInAnyOrder(7L, 8L, 9L, 10L);
        verify(userService).getUser(userId);
    }

    @Test
    void getDeptDataPermission_noRolesRestrictsToSelf() {
        Long userId = 1001L;
        doReturn(Collections.emptyList()).when(permissionService).getEnableUserRoleListByUserId(userId);

        DeptDataPermissionRespDTO result = permissionService.getDeptDataPermission(userId);

        assertThat(result.getAll()).isFalse();
        assertThat(result.getSelf()).isTrue();
        assertThat(result.getDeptIds()).isEmpty();
    }

    @Test
    void getDeptDataPermission_invalidScopeFailsClosed() {
        Long userId = 1001L;
        RoleDO invalidRole = role(201L, "corrupt", null, CommonStatusEnum.ENABLE);
        doReturn(List.of(invalidRole)).when(permissionService).getEnableUserRoleListByUserId(userId);

        assertThatThrownBy(() -> permissionService.getDeptDataPermission(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Role(201)")
                .hasMessageContaining("invalid data scope");

        invalidRole.setDataScope(99);
        assertThatThrownBy(() -> permissionService.getDeptDataPermission(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
    }

    private static RoleDO role(Long id, String code, DataScopeEnum dataScope, CommonStatusEnum status) {
        return role(id, code, dataScope, status, Collections.emptySet());
    }

    private static RoleDO role(
            Long id, String code, DataScopeEnum dataScope, CommonStatusEnum status, Set<Long> deptIds) {
        RoleDO role = new RoleDO();
        role.setId(id);
        role.setCode(code);
        role.setStatus(status.getStatus());
        role.setDataScope(dataScope == null ? null : dataScope.getScope());
        role.setDataScopeDeptIds(deptIds);
        return role;
    }

    private static MenuDO enabledMenu(Long id) {
        MenuDO menu = new MenuDO();
        menu.setId(id);
        menu.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return menu;
    }

    private static RoleMenuDO roleMenu(Long roleId, Long menuId) {
        RoleMenuDO roleMenu = new RoleMenuDO();
        roleMenu.setRoleId(roleId);
        roleMenu.setMenuId(menuId);
        return roleMenu;
    }

    private static UserRoleDO userRole(Long userId, Long roleId) {
        UserRoleDO userRole = new UserRoleDO();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }
}
