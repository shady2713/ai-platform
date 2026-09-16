package com.basicframework.module.system.api.permission;

import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;

/** 跨模块权限查询契约。只暴露权限判定与数据权限读取，不暴露权限变更能力。 */
public interface PermissionCommonApi {

    /**
     * 判断用户是否拥有给定权限中的任意一项。
     *
     * @param userId 用户编号
     * @param permissions 待判定的权限标识
     * @return 存在任意匹配权限时返回 {@code true}
     */
    boolean hasAnyPermissions(Long userId, String... permissions);

    /**
     * 判断用户是否拥有给定角色中的任意一项。
     *
     * @param userId 用户编号
     * @param roles 待判定的角色标识
     * @return 存在任意匹配角色时返回 {@code true}
     */
    boolean hasAnyRoles(Long userId, String... roles);

    /**
     * 获得用户当前生效的部门数据权限。
     *
     * @param userId 用户编号
     * @return 部门数据权限
     */
    DeptDataPermissionRespDTO getDeptDataPermission(Long userId);
}
