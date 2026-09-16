package com.basicframework.module.system.api.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import com.basicframework.module.system.service.permission.PermissionService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PermissionApiImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class PermissionApiImplTest {

    @InjectMocks
    private PermissionApiImpl permissionApi;

    @Mock
    private PermissionService permissionService;

    @Test
    void hasAnyPermissions_delegatesToPermissionService() {
        when(permissionService.hasAnyPermissions(1L, "system:user:query", "system:user:update"))
                .thenReturn(true);

        assertThat(permissionApi.hasAnyPermissions(1L, "system:user:query", "system:user:update"))
                .isTrue();
    }

    @Test
    void hasAnyRoles_delegatesToPermissionService() {
        when(permissionService.hasAnyRoles(2L, "admin")).thenReturn(false);

        assertThat(permissionApi.hasAnyRoles(2L, "admin")).isFalse();
    }

    @Test
    void getDeptDataPermission_delegatesToPermissionService() {
        DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
        permission.setAll(true);
        permission.setDeptIds(Set.of(10L, 20L));
        when(permissionService.getDeptDataPermission(3L)).thenReturn(permission);

        assertThat(permissionApi.getDeptDataPermission(3L)).isSameAs(permission);
    }
}
