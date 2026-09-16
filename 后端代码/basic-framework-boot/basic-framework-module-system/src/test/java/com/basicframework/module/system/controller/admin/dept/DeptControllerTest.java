package com.basicframework.module.system.controller.admin.dept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.controller.admin.dept.vo.dept.DeptListReqVO;
import com.basicframework.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.service.dept.DeptService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeptControllerTest {

    private final DeptService deptService = mock(DeptService.class);
    private final DeptController controller = new DeptController(deptService);

    @Test
    void mutationsMapRequestsAndDelegateExactTargets() {
        DeptSaveReqVO request = saveRequest();
        when(deptService.createDept(any(DeptDO.class))).thenReturn(9L);

        assertThat(controller.createDept(request).getData()).isEqualTo(9L);
        assertThat(controller.updateDept(request).getData()).isTrue();
        assertThat(controller.deleteDept(7L).getData()).isTrue();
        assertThat(controller.deleteDeptList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<DeptDO> captor = ArgumentCaptor.forClass(DeptDO.class);
        verify(deptService).createDept(captor.capture());
        assertThat(captor.getValue())
                .extracting(DeptDO::getId, DeptDO::getName, DeptDO::getParentId, DeptDO::getStatus)
                .containsExactly(7L, "研发部", 1L, CommonStatusEnum.ENABLE.getStatus());
        verify(deptService).updateDept(any(DeptDO.class));
        verify(deptService).deleteDept(7L);
        verify(deptService).deleteDeptList(List.of(7L, 8L));
    }

    @Test
    void listPreservesFiltersAndMapsPublishedFields() {
        DeptListReqVO request = new DeptListReqVO();
        request.setName("研发");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        DeptDO dept = dept();
        when(deptService.getDeptList("研发", CommonStatusEnum.ENABLE.getStatus())).thenReturn(List.of(dept));

        assertThat(controller.getDeptList(request).getData())
                .singleElement()
                .extracting("id", "name")
                .containsExactly(7L, "研发部");
        verify(deptService).getDeptList("研发", CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void simpleListIncludesOnlyEnabledDepartmentsAndGetMapsTheRequestedDepartment() {
        DeptDO dept = dept();
        when(deptService.getDeptList(null, CommonStatusEnum.ENABLE.getStatus())).thenReturn(List.of(dept));
        when(deptService.getDept(7L)).thenReturn(dept);

        assertThat(controller.getSimpleDeptList().getData())
                .singleElement()
                .extracting("id", "name")
                .containsExactly(7L, "研发部");
        assertThat(controller.getDept(7L).getData().getName()).isEqualTo("研发部");
        verify(deptService).getDeptList(null, CommonStatusEnum.ENABLE.getStatus());
        verify(deptService).getDept(7L);
    }

    private static DeptSaveReqVO saveRequest() {
        DeptSaveReqVO request = new DeptSaveReqVO();
        request.setId(7L);
        request.setName("研发部");
        request.setParentId(1L);
        request.setSort(10);
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static DeptDO dept() {
        return new DeptDO()
                .setId(7L)
                .setName("研发部")
                .setParentId(1L)
                .setSort(10)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
