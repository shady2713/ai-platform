package com.basicframework.module.system.service.dept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link DeptService} 接口契约测试，覆盖 default 方法
 *
 */
class DeptServiceTest {

    @Test
    void getDeptMap_convertsListToIdMap() {
        DeptService service = mock(DeptService.class, CALLS_REAL_METHODS);
        DeptDO first = new DeptDO();
        first.setId(1L);
        DeptDO second = new DeptDO();
        second.setId(2L);
        when(service.getDeptList(anyCollection())).thenReturn(List.of(first, second));

        Map<Long, DeptDO> deptMap = service.getDeptMap(List.of(1L, 2L));

        assertThat(deptMap).containsOnlyKeys(1L, 2L);
        assertThat(deptMap.get(1L)).isSameAs(first);
        assertThat(deptMap.get(2L)).isSameAs(second);
        verify(service).getDeptList(anyCollection());
    }

    @Test
    void getChildDeptList_singleIdDelegatesToCollectionVariant() {
        DeptService service = mock(DeptService.class, CALLS_REAL_METHODS);
        DeptDO child = new DeptDO();
        child.setId(3L);
        when(service.getChildDeptList(anyCollection())).thenReturn(List.of(child));

        assertThat(service.getChildDeptList(1L)).containsExactly(child);
    }
}
