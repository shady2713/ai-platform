package com.basicframework.module.system.controller.admin.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleDataScopeReqVO;
import com.basicframework.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleMenuReqVO;
import com.basicframework.module.system.controller.admin.permission.vo.permission.PermissionAssignUserRoleReqVO;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.service.permission.PermissionService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PermissionControllerTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    private final PermissionController controller = new PermissionController(permissionService);

    @Test
    void assignmentCommands_bindPrivilegedMutationToCurrentLoginUser() {
        Long operatorUserId = 9001L;
        PermissionAssignRoleMenuReqVO roleMenuRequest = new PermissionAssignRoleMenuReqVO();
        roleMenuRequest.setRoleId(201L);
        roleMenuRequest.setMenuIds(Set.of(301L));
        PermissionAssignRoleDataScopeReqVO dataScopeRequest = new PermissionAssignRoleDataScopeReqVO();
        dataScopeRequest.setRoleId(202L);
        dataScopeRequest.setDataScope(DataScopeEnum.DEPT_CUSTOM.getScope());
        dataScopeRequest.setDataScopeDeptIds(Set.of(401L));
        PermissionAssignUserRoleReqVO userRoleRequest = new PermissionAssignUserRoleReqVO();
        userRoleRequest.setUserId(501L);
        userRoleRequest.setRoleIds(Set.of(203L));

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class)) {
            securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(operatorUserId);

            assertThat(controller.assignRoleMenu(roleMenuRequest).getData()).isTrue();
            assertThat(controller.assignRoleDataScope(dataScopeRequest).getData())
                    .isTrue();
            assertThat(controller.assignUserRole(userRoleRequest).getData()).isTrue();
        }

        verify(permissionService).assignRoleMenu(operatorUserId, 201L, Set.of(301L));
        verify(permissionService)
                .assignRoleDataScope(operatorUserId, 202L, DataScopeEnum.DEPT_CUSTOM.getScope(), Set.of(401L));
        verify(permissionService).assignUserRole(operatorUserId, 501L, Set.of(203L));
    }
}
