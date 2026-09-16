package com.basicframework.module.system.service.permission;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.module.system.enums.ErrorCodeConstants.MENU_IS_DISABLE;
import static com.basicframework.module.system.enums.ErrorCodeConstants.MENU_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN;
import static com.basicframework.module.system.enums.LogRecordConstants.*;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.ROLE_PERMISSION_CHANGED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_ROLE_CHANGED;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ArrayUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleMenuDO;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import com.basicframework.module.system.dal.mysql.permission.RoleMenuMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.user.AdminUserService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限 Service 实现类
 *
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final RoleMenuMapper roleMenuMapper;

    private final UserRoleMapper userRoleMapper;

    private final RoleService roleService;

    private final MenuService menuService;

    private final DeptService deptService;

    private final AdminUserService userService;

    private final UserSessionRevocationPublisher sessionRevocationPublisher;

    @Override
    public boolean hasAnyPermissions(Long userId, String... permissions) {
        // 如果为空，说明已经有权限
        if (ArrayUtil.isEmpty(permissions)) {
            return true;
        }

        // 获得当前登录的角色。如果为空，说明没有权限
        List<RoleDO> roles = getEnableUserRoleListByUserId(userId);
        if (CollUtil.isEmpty(roles)) {
            return false;
        }

        // 情况一：遍历判断每个权限，如果有一满足，说明有权限
        for (String permission : permissions) {
            if (hasAnyPermission(roles, permission)) {
                return true;
            }
        }

        // 情况二：如果是超管，也说明有权限
        return roleService.hasAnySuperAdmin(convertSet(roles, RoleDO::getId));
    }

    /**
     * 判断指定角色，是否拥有该 permission 权限
     *
     * @param roles 指定角色数组
     * @param permission 权限标识
     * @return 是否拥有
     */
    private boolean hasAnyPermission(List<RoleDO> roles, String permission) {
        List<Long> menuIds = menuService.getMenuIdListByPermission(permission);
        // 采用严格模式，如果权限找不到对应的 Menu 的话，也认为没有权限
        if (CollUtil.isEmpty(menuIds)) {
            return false;
        }

        // 判断是否有权限
        Set<Long> roleIds = convertSet(roles, RoleDO::getId);
        for (Long menuId : menuIds) {
            // 获得拥有该菜单的角色编号集合
            Set<Long> menuRoleIds = getMenuRoleIdListByMenuId(menuId);
            // 如果有交集，说明有权限
            if (CollUtil.containsAny(menuRoleIds, roleIds)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasAnyRoles(Long userId, String... roles) {
        // 如果为空，说明已经有权限
        if (ArrayUtil.isEmpty(roles)) {
            return true;
        }

        // 获得当前登录的角色。如果为空，说明没有权限
        List<RoleDO> roleList = getEnableUserRoleListByUserId(userId);
        if (CollUtil.isEmpty(roleList)) {
            return false;
        }

        // 判断是否有角色
        Set<String> userRoles = convertSet(roleList, RoleDO::getCode);
        return CollUtil.containsAny(userRoles, Sets.newHashSet(roles));
    }

    // ========== 角色-菜单的相关方法  ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_PERMISSION_TYPE,
            subType = SYSTEM_PERMISSION_ASSIGN_ROLE_MENU_SUB_TYPE,
            bizNo = "{{#roleId}}",
            success = SYSTEM_PERMISSION_ASSIGN_ROLE_MENU_SUCCESS)
    public void assignRoleMenu(Long operatorUserId, Long roleId, Set<Long> menuIds) {
        roleService.validateRoleList(Collections.singleton(roleId));
        validatePrivilegedRoleMutation(operatorUserId, Collections.singleton(roleId));
        validateMenuList(menuIds);
        // 获得角色拥有菜单编号
        Set<Long> dbMenuIds = convertSet(roleMenuMapper.selectListByRoleId(roleId), RoleMenuDO::getMenuId);
        // 计算新增和删除的菜单编号
        Set<Long> menuIdList = CollUtil.emptyIfNull(menuIds);
        Collection<Long> createMenuIds = CollUtil.subtract(menuIdList, dbMenuIds);
        Collection<Long> deleteMenuIds = CollUtil.subtract(dbMenuIds, menuIdList);
        // 执行新增和删除。对于已经授权的菜单，不用做任何处理
        if (CollUtil.isNotEmpty(createMenuIds)) {
            roleMenuMapper.insertBatch(CollectionUtils.convertList(createMenuIds, menuId -> {
                RoleMenuDO entity = new RoleMenuDO();
                entity.setRoleId(roleId);
                entity.setMenuId(menuId);
                return entity;
            }));
        }
        if (CollUtil.isNotEmpty(deleteMenuIds)) {
            roleMenuMapper.deleteListByRoleIdAndMenuIds(roleId, deleteMenuIds);
        }
        if (CollUtil.isNotEmpty(createMenuIds) || CollUtil.isNotEmpty(deleteMenuIds)) {
            sessionRevocationPublisher.revokeAdminSessions(
                    getUserRoleIdListByRoleId(Collections.singleton(roleId)), ROLE_PERMISSION_CHANGED);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processRoleDeleted(Long roleId) {
        Set<Long> affectedUserIds = getUserRoleIdListByRoleId(Collections.singleton(roleId));
        // 标记删除 UserRole
        userRoleMapper.deleteListByRoleId(roleId);
        // 标记删除 RoleMenu
        roleMenuMapper.deleteListByRoleId(roleId);
        sessionRevocationPublisher.revokeAdminSessions(affectedUserIds, ROLE_PERMISSION_CHANGED);
    }

    @Override
    public void processMenuDeleted(Long menuId) {
        Set<Long> affectedRoleIds = convertSet(roleMenuMapper.selectListByMenuId(menuId), RoleMenuDO::getRoleId);
        Set<Long> affectedUserIds = getUserRoleIdListByRoleId(affectedRoleIds);
        roleMenuMapper.deleteListByMenuId(menuId);
        sessionRevocationPublisher.revokeAdminSessions(affectedUserIds, ROLE_PERMISSION_CHANGED);
    }

    @Override
    public Set<Long> getRoleMenuListByRoleId(Collection<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptySet();
        }

        // 如果是管理员的情况下，获取全部菜单编号
        if (roleService.hasAnySuperAdmin(roleIds)) {
            return convertSet(menuService.getMenuList(), MenuDO::getId);
        }
        // 如果是非管理员的情况下，获得拥有的菜单编号
        return convertSet(roleMenuMapper.selectListByRoleId(roleIds), RoleMenuDO::getMenuId);
    }

    @Override
    public Set<Long> getMenuRoleIdListByMenuId(Long menuId) {
        return convertSet(roleMenuMapper.selectListByMenuId(menuId), RoleMenuDO::getRoleId);
    }

    // ========== 用户-角色的相关方法  ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_PERMISSION_TYPE,
            subType = SYSTEM_PERMISSION_ASSIGN_USER_ROLE_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_PERMISSION_ASSIGN_USER_ROLE_SUCCESS)
    public void assignUserRole(Long operatorUserId, Long userId, Set<Long> roleIds) {
        userService.validateUserList(Collections.singleton(userId));
        roleService.validateRoleList(roleIds);
        // 获得角色拥有角色编号
        Set<Long> dbRoleIds = convertSet(userRoleMapper.selectListByUserId(userId), UserRoleDO::getRoleId);
        Set<Long> involvedRoleIds = new HashSet<>(dbRoleIds);
        involvedRoleIds.addAll(CollUtil.emptyIfNull(roleIds));
        validatePrivilegedRoleMutation(operatorUserId, involvedRoleIds);
        // 计算新增和删除的角色编号
        Set<Long> roleIdList = CollUtil.emptyIfNull(roleIds);
        Collection<Long> createRoleIds = CollUtil.subtract(roleIdList, dbRoleIds);
        Collection<Long> deleteMenuIds = CollUtil.subtract(dbRoleIds, roleIdList);
        // 执行新增和删除。对于已经授权的角色，不用做任何处理
        if (!CollectionUtil.isEmpty(createRoleIds)) {
            userRoleMapper.insertBatch(CollectionUtils.convertList(createRoleIds, roleId -> {
                UserRoleDO entity = new UserRoleDO();
                entity.setUserId(userId);
                entity.setRoleId(roleId);
                return entity;
            }));
        }
        if (!CollectionUtil.isEmpty(deleteMenuIds)) {
            userRoleMapper.deleteListByUserIdAndRoleIdIds(userId, deleteMenuIds);
        }
        if (!CollectionUtil.isEmpty(createRoleIds) || !CollectionUtil.isEmpty(deleteMenuIds)) {
            sessionRevocationPublisher.revokeAdminSession(userId, USER_ROLE_CHANGED);
        }
    }

    @Override
    public void processUserDeleted(Long userId) {
        userRoleMapper.deleteListByUserId(userId);
    }

    @Override
    public Set<Long> getUserRoleIdListByUserId(Long userId) {
        return convertSet(userRoleMapper.selectListByUserId(userId), UserRoleDO::getRoleId);
    }

    @Override
    public Set<Long> getUserRoleIdListByRoleId(Collection<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptySet();
        }
        return convertSet(userRoleMapper.selectListByRoleIds(roleIds), UserRoleDO::getUserId);
    }

    private void validateMenuList(Collection<Long> menuIds) {
        if (CollUtil.isEmpty(menuIds)) {
            return;
        }
        Map<Long, MenuDO> menuMap = CollectionUtils.convertMap(menuService.getMenuList(menuIds), MenuDO::getId);
        menuIds.forEach(menuId -> {
            MenuDO menu = menuMap.get(menuId);
            if (menu == null) {
                throw exception(MENU_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(menu.getStatus())) {
                throw exception(MENU_IS_DISABLE, menu.getName());
            }
        });
    }

    /**
     * 删除/禁用持有启用中超管角色的用户会改变认证面，与角色授权变更同级约束：操作者本身必须持有启用中的超管角色；
     * 操作者身份缺失时按非特权处理（fail-closed）。
     */
    @Override
    public void validatePrivilegedUserMutation(Long operatorUserId, Collection<Long> targetUserIds) {
        if (operatorUserId != null && hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode())) {
            return;
        }
        for (Long targetUserId : targetUserIds) {
            if (hasAnyRoles(targetUserId, RoleCodeEnum.SUPER_ADMIN.getCode())) {
                throw exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN);
            }
        }
    }

    /** 超级管理员绕过普通权限与数据范围，因此任何触及该角色的授权变更都要求操作者本身持有启用中的超级管理员角色。 */
    private void validatePrivilegedRoleMutation(Long operatorUserId, Collection<Long> involvedRoleIds) {
        if (roleService.hasAnySuperAdmin(involvedRoleIds)
                && !hasAnyRoles(operatorUserId, RoleCodeEnum.SUPER_ADMIN.getCode())) {
            throw exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN);
        }
    }

    /**
     * 获得用户拥有的角色，并且这些角色是开启状态的
     *
     * @param userId 用户编号
     * @return 用户拥有的角色
     */
    @VisibleForTesting
    List<RoleDO> getEnableUserRoleListByUserId(Long userId) {
        // 获得用户拥有的角色编号
        Set<Long> roleIds = getUserRoleIdListByUserId(userId);
        // 获得角色数组，并过滤被禁用的角色。
        return roleService.getRoleList(roleIds).stream()
                .filter(role -> CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()))
                .toList();
    }

    // ========== 用户-部门的相关方法  ==========

    @Override
    @LogRecord(
            type = SYSTEM_PERMISSION_TYPE,
            subType = SYSTEM_PERMISSION_ASSIGN_ROLE_DATA_SCOPE_SUB_TYPE,
            bizNo = "{{#roleId}}",
            success = SYSTEM_PERMISSION_ASSIGN_ROLE_DATA_SCOPE_SUCCESS)
    public void assignRoleDataScope(Long operatorUserId, Long roleId, Integer dataScope, Set<Long> dataScopeDeptIds) {
        roleService.validateRoleList(Collections.singleton(roleId));
        validatePrivilegedRoleMutation(operatorUserId, Collections.singleton(roleId));
        roleService.updateRoleDataScope(roleId, dataScope, dataScopeDeptIds);
    }

    @Override
    @DataPermission(enable = false) // 关闭数据权限，不然就会出现递归获取数据权限的问题
    public DeptDataPermissionRespDTO getDeptDataPermission(Long userId) {
        // 获得用户的角色
        List<RoleDO> roles = getEnableUserRoleListByUserId(userId);

        // 如果角色为空，则只能查看自己
        DeptDataPermissionRespDTO result = new DeptDataPermissionRespDTO();
        if (CollUtil.isEmpty(roles)) {
            result.setSelf(true);
            return result;
        }

        // 获得用户的部门编号的缓存，通过 Guava 的 Suppliers 惰性求值，即有且仅有第一次发起 DB 的查询
        Supplier<Long> userDeptId =
                Suppliers.memoize(() -> userService.getUser(userId).getDeptId());
        Supplier<Set<Long>> childDeptIds = Suppliers.memoize(() -> deptService.getChildDeptIdList(userDeptId.get()));
        // 遍历每个角色，计算
        for (RoleDO role : roles) {
            DataScopeEnum dataScope = requireDataScope(role);
            switch (dataScope) {
                case ALL -> result.setAll(true);
                case DEPT_CUSTOM -> {
                    CollUtil.addAll(result.getDeptIds(), role.getDataScopeDeptIds());
                    CollUtil.addAll(result.getDeptIds(), userDeptId.get());
                }
                case DEPT_ONLY -> CollectionUtils.addIfNotNull(result.getDeptIds(), userDeptId.get());
                case DEPT_AND_CHILD -> {
                    CollUtil.addAll(result.getDeptIds(), childDeptIds.get());
                    CollUtil.addAll(result.getDeptIds(), userDeptId.get());
                }
                case SELF -> result.setSelf(true);
                default -> throw new IllegalStateException("Unsupported data scope: " + dataScope);
            }
        }
        return result;
    }

    private DataScopeEnum requireDataScope(RoleDO role) {
        DataScopeEnum dataScope = DataScopeEnum.fromScope(role.getDataScope());
        if (dataScope == null) {
            throw new IllegalStateException(
                    "Role(" + role.getId() + ") has invalid data scope: " + role.getDataScope());
        }
        return dataScope;
    }
}
