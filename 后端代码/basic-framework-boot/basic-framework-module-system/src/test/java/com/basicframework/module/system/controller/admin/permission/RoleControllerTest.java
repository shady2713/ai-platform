package com.basicframework.module.system.controller.admin.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.permission.vo.role.RolePageReqVO;
import com.basicframework.module.system.controller.admin.permission.vo.role.RoleRespVO;
import com.basicframework.module.system.controller.admin.permission.vo.role.RoleSaveReqVO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.service.permission.RoleService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

class RoleControllerTest {

    private final RoleService roleService = mock(RoleService.class);
    private final RoleController controller = new RoleController(roleService);

    @Test
    void privilegedMutationsMapRequestsAndDelegateExactTargets() {
        RoleSaveReqVO request = saveRequest();
        when(roleService.createRole(any(RoleDO.class), isNull())).thenReturn(9L);

        assertThat(controller.createRole(request).getData()).isEqualTo(9L);
        assertThat(controller.updateRole(request).getData()).isTrue();
        assertThat(controller.deleteRole(7L).getData()).isTrue();
        assertThat(controller.deleteRoleList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<RoleDO> roleCaptor = ArgumentCaptor.forClass(RoleDO.class);
        verify(roleService).createRole(roleCaptor.capture(), isNull());
        assertThat(roleCaptor.getValue())
                .extracting(RoleDO::getId, RoleDO::getName, RoleDO::getCode, RoleDO::getSort)
                .containsExactly(7L, "审计员", "AUDITOR", 20);
        verify(roleService).updateRole(any(RoleDO.class));
        verify(roleService).deleteRole(7L);
        verify(roleService).deleteRoleList(List.of(7L, 8L));
    }

    @Test
    void getAndPageReturnMappedDataAndPreserveEveryFilter() {
        RoleDO role = role(7L, 20);
        RolePageReqVO request = pageRequest();
        when(roleService.getRole(7L)).thenReturn(role);
        when(roleService.getRolePage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(role), 1L));

        RoleRespVO detail = controller.getRole(7L).getData();
        PageResult<RoleRespVO> page = controller.getRolePage(request).getData();

        assertThat(detail.getCode()).isEqualTo("AUDITOR");
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(RoleRespVO::getId).containsExactly(7L);
        verify(roleService)
                .getRolePage(
                        same(request),
                        same(request.getName()),
                        same(request.getCode()),
                        same(request.getStatus()),
                        same(request.getCreateTime()));
    }

    @Test
    void simpleListSortsAServiceOwnedMutableCopyByDisplayOrder() {
        List<RoleDO> roles = new ArrayList<>(List.of(role(8L, 30), role(7L, 10)));
        when(roleService.getRoleList()).thenReturn(roles);

        List<RoleRespVO> result = controller.getSimpleRoleList().getData();

        assertThat(result).extracting(RoleRespVO::getId).containsExactly(7L, 8L);
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        RolePageReqVO request = pageRequest();
        RoleDO role = role(7L, 20);
        List<RoleRespVO> expectedRows = BeanUtils.toBean(List.of(role), RoleRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(roleService.getRolePage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(role), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "角色数据.xls", "数据", RoleRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
        verify(roleService)
                .getRolePage(
                        same(request),
                        same(request.getName()),
                        same(request.getCode()),
                        same(request.getStatus()),
                        same(request.getCreateTime()));
    }

    private static RoleSaveReqVO saveRequest() {
        RoleSaveReqVO request = new RoleSaveReqVO();
        request.setId(7L);
        request.setName("审计员");
        request.setCode("AUDITOR");
        request.setSort(20);
        request.setStatus(0);
        request.setRemark("只读审计");
        return request;
    }

    private static RoleDO role(Long id, Integer sort) {
        return new RoleDO()
                .setId(id)
                .setName("审计员")
                .setCode("AUDITOR")
                .setSort(sort)
                .setStatus(0);
    }

    private static RolePageReqVO pageRequest() {
        RolePageReqVO request = new RolePageReqVO();
        request.setName("审计");
        request.setCode("AUDITOR");
        request.setStatus(0);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }
}
