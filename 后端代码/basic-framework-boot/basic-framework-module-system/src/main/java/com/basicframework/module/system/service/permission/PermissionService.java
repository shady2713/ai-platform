package com.basicframework.module.system.service.permission;

import static java.util.Collections.singleton;

import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import java.util.Collection;
import java.util.Set;

/**
 * 权限 Service 接口
 * <p>
 * 提供用户-角色、角色-菜单、角色-部门的关联权限处理
 *
 */
public interface PermissionService {

    /**
     * 判断是否有权限，任一一个即可
     *
     * @param userId      用户编号
     * @param permissions 权限
     * @return 是否
     */
    boolean hasAnyPermissions(Long userId, String... permissions);

    /**
     * 判断是否有角色，任一一个即可
     *
     * @param roles 角色数组
     * @return 是否
     */
    boolean hasAnyRoles(Long userId, String... roles);

    // ========== 角色-菜单的相关方法  ==========

    /**
     * 设置角色菜单
     *
     * @param operatorUserId 操作者用户编号
     * @param roleId  角色编号
     * @param menuIds 菜单编号集合
     */
    void assignRoleMenu(Long operatorUserId, Long roleId, Set<Long> menuIds);

    /**
     * 处理角色删除时，删除关联授权数据
     *
     * @param roleId 角色编号
     */
    void processRoleDeleted(Long roleId);

    /**
     * 处理菜单删除时，删除关联授权数据
     *
     * @param menuId 菜单编号
     */
    void processMenuDeleted(Long menuId);

    /**
     * 获得角色拥有的菜单编号集合
     *
     * @param roleId 角色编号
     * @return 菜单编号集合
     */
    default Set<Long> getRoleMenuListByRoleId(Long roleId) {
        return getRoleMenuListByRoleId(singleton(roleId));
    }

    /**
     * 获得角色们拥有的菜单编号集合
     *
     * @param roleIds 角色编号数组
     * @return 菜单编号集合
     */
    Set<Long> getRoleMenuListByRoleId(Collection<Long> roleIds);

    /**
     * 获得拥有指定菜单的角色编号数组
     *
     * @param menuId 菜单编号
     * @return 角色编号数组
     */
    Set<Long> getMenuRoleIdListByMenuId(Long menuId);

    // ========== 用户-角色的相关方法  ==========

    /**
     * 设置用户角色
     *
     * @param operatorUserId 操作者用户编号
     * @param userId 用户编号
     * @param roleIds 角色编号集合
     */
    void assignUserRole(Long operatorUserId, Long userId, Set<Long> roleIds);

    /**
     * 校验删除/禁用目标用户的权限：目标持有启用中超管角色时，操作者本身必须持有启用中的超管角色
     *
     * @param operatorUserId 操作者用户编号
     * @param targetUserIds 目标用户编号数组
     */
    void validatePrivilegedUserMutation(Long operatorUserId, Collection<Long> targetUserIds);

    /**
     * 处理用户删除时，删除关联授权数据
     *
     * @param userId 用户编号
     */
    void processUserDeleted(Long userId);

    /**
     * 获得拥有多个角色的用户编号集合
     *
     * @param roleIds 角色编号集合
     * @return 用户编号集合
     */
    Set<Long> getUserRoleIdListByRoleId(Collection<Long> roleIds);

    /**
     * 获得用户拥有的角色编号集合
     *
     * @param userId 用户编号
     * @return 角色编号集合
     */
    Set<Long> getUserRoleIdListByUserId(Long userId);

    // ========== 用户-部门的相关方法  ==========

    /**
     * 设置角色的数据权限
     *
     * @param operatorUserId 操作者用户编号
     * @param roleId           角色编号
     * @param dataScope        数据范围
     * @param dataScopeDeptIds 部门编号数组
     */
    void assignRoleDataScope(Long operatorUserId, Long roleId, Integer dataScope, Set<Long> dataScopeDeptIds);

    /**
     * 获得登陆用户的部门数据权限
     *
     * @param userId 用户编号
     * @return 部门数据权限
     */
    DeptDataPermissionRespDTO getDeptDataPermission(Long userId);
}
