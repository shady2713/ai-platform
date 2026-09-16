package com.basicframework.module.system.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AdminUserService} 接口契约测试，覆盖 default 方法
 *
 */
class AdminUserServiceTest {

    @Test
    void getUserMap_emptyIdsReturnsEmptyMapWithoutCallingMapper() {
        AdminUserService service = mock(AdminUserService.class, CALLS_REAL_METHODS);

        assertThat(service.getUserMap(Collections.emptyList())).isEmpty();
    }

    @Test
    void getUserMap_convertsListToIdMap() {
        AdminUserService service = mock(AdminUserService.class, CALLS_REAL_METHODS);
        AdminUserDO first = new AdminUserDO();
        first.setId(1L);
        AdminUserDO second = new AdminUserDO();
        second.setId(2L);
        when(service.getUserList(anyCollection())).thenReturn(List.of(first, second));

        Map<Long, AdminUserDO> userMap = service.getUserMap(List.of(1L, 2L));

        assertThat(userMap).containsOnlyKeys(1L, 2L);
        assertThat(userMap.get(1L)).isSameAs(first);
        assertThat(userMap.get(2L)).isSameAs(second);
    }
}
