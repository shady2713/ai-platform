package com.basicframework.module.system.service.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link PermissionService} 接口契约测试，覆盖 default 方法
 *
 */
class PermissionServiceTest {

    @Test
    void getRoleMenuListByRoleId_singleIdDelegatesToCollectionVariant() {
        PermissionService service = mock(PermissionService.class, CALLS_REAL_METHODS);
        when(service.getRoleMenuListByRoleId(anyCollection())).thenReturn(Set.of(100L, 200L));

        assertThat(service.getRoleMenuListByRoleId(1L)).containsExactlyInAnyOrder(100L, 200L);
    }
}
