package com.basicframework.module.system.api.permission;

import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import com.basicframework.module.system.service.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 权限 API 实现类
 *
 */
@Service
@RequiredArgsConstructor
public class PermissionApiImpl implements PermissionCommonApi {

    private final PermissionService permissionService;

    @Override
    public boolean hasAnyPermissions(Long userId, String... permissions) {
        return permissionService.hasAnyPermissions(userId, permissions);
    }

    @Override
    public boolean hasAnyRoles(Long userId, String... roles) {
        return permissionService.hasAnyRoles(userId, roles);
    }

    @Override
    public DeptDataPermissionRespDTO getDeptDataPermission(Long userId) {
        return permissionService.getDeptDataPermission(userId);
    }
}
