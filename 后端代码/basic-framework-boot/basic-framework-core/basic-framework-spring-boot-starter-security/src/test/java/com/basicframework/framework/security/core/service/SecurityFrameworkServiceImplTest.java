package com.basicframework.framework.security.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 权限校验服务单元测试
 *
 * 覆盖 hasAnyPermissions/hasAnyRoles 的登录用户代理查询，以及未登录和权限缺失边界。
 * SecurityFrameworkUtils 为静态安全上下文，用 mockStatic。
 */
@ExtendWith(MockitoExtension.class)
class SecurityFrameworkServiceImplTest {

    @Mock
    private PermissionCommonApi permissionApi;

    private MockedStatic<SecurityFrameworkUtils> utils;

    @AfterEach
    void tearDown() {
        if (utils != null) {
            utils.close();
        }
    }

    @Test
    void hasAnyPermissions_queriesApiWithLoginUserId() {
        utils = mockStatic(SecurityFrameworkUtils.class);
        utils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(1L);
        when(permissionApi.hasAnyPermissions(1L, "system:user:query")).thenReturn(true);

        assertThat(newService().hasAnyPermissions("system:user:query")).isTrue();
        verify(permissionApi).hasAnyPermissions(1L, "system:user:query");
    }

    @Test
    void hasAnyPermissions_falseWhenNotLoggedIn() {
        utils = mockStatic(SecurityFrameworkUtils.class);
        utils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);

        assertThat(newService().hasAnyPermissions("system:user:query")).isFalse();
        verify(permissionApi, never())
                .hasAnyPermissions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hasPermission_delegatesToAnyPermissions() {
        utils = mockStatic(SecurityFrameworkUtils.class);
        utils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(1L);
        when(permissionApi.hasAnyPermissions(1L, "system:user:query")).thenReturn(false);

        assertThat(newService().hasPermission("system:user:query")).isFalse();
    }

    @Test
    void hasAnyRoles_queriesApiAndPropagatesResult() {
        utils = mockStatic(SecurityFrameworkUtils.class);
        utils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(1L);
        when(permissionApi.hasAnyRoles(1L, "admin")).thenReturn(true);

        assertThat(newService().hasAnyRoles("admin")).isTrue();
        assertThat(newService().hasRole("admin")).isTrue();
    }

    @Test
    void hasAnyRoles_falseWhenNotLoggedIn() {
        utils = mockStatic(SecurityFrameworkUtils.class);
        utils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);

        assertThat(newService().hasAnyRoles("admin")).isFalse();
    }

    // ---------- helpers ----------

    private SecurityFrameworkServiceImpl newService() {
        return new SecurityFrameworkServiceImpl(permissionApi);
    }
}
