package com.basicframework.module.system.service.permission;

import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.enums.permission.RoleTypeEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 角色 Service 单元测试
 *
 * 覆盖角色的创建/更新/删除主链路与业务校验：
 * 角色标识保留字（super_admin）、名称/标识唯一、角色存在性与内置角色保护、
 * 启用状态下禁止直接禁用被引用的角色。
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    private static final Long ROLE_ID = 1L;
    private static final String ROLE_NAME = "测试角色";
    private static final String ROLE_CODE = "TEST_ROLE";

    @InjectMocks
    private RoleServiceImpl roleService;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private DeptService deptService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<PermissionService> permissionServiceProvider;

    @Mock
    private UserSessionRevocationPublisher sessionRevocationPublisher;

    private RoleDO role;

    @BeforeEach
    void setUp() {
        role = role(ROLE_ID, ROLE_NAME, ROLE_CODE, CommonStatusEnum.ENABLE.getStatus());
        role.setType(RoleTypeEnum.CUSTOM.getType());
    }

    // ---------- createRole ----------

    @Test
    void createRole_superAdminCode_throws() {
        role.setCode(RoleCodeEnum.SUPER_ADMIN.getCode());

        assertServiceException(
                ErrorCodeConstants.ROLE_ADMIN_CODE_ERROR.getCode(), () -> roleService.createRole(role, null));
        verify(roleMapper, never()).insert(any(RoleDO.class));
    }

    @Test
    void createRole_nameDuplicate_throws() {
        when(roleMapper.selectByName(role.getName()))
                .thenReturn(role(99L, role.getName(), "ANOTHER", CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.ROLE_NAME_DUPLICATE.getCode(), () -> roleService.createRole(role, null));
        verify(roleMapper, never()).insert(any(RoleDO.class));
    }

    @Test
    void createRole_codeDuplicate_throws() {
        when(roleMapper.selectByName(role.getName())).thenReturn(null);
        when(roleMapper.selectByCode(role.getCode()))
                .thenReturn(role(99L, "其他角色", role.getCode(), CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.ROLE_CODE_DUPLICATE.getCode(), () -> roleService.createRole(role, null));
        verify(roleMapper, never()).insert(any(RoleDO.class));
    }

    @Test
    void createRole_ignoresCallerDataScopeAndDefaultsToSelf() {
        when(roleMapper.selectByName(role.getName())).thenReturn(null);
        when(roleMapper.selectByCode(role.getCode())).thenReturn(null);
        role.setDataScope(DataScopeEnum.ALL.getScope());

        Long id = roleService.createRole(role, null);

        assertThat(id).isEqualTo(role.getId());
        assertThat(role.getType()).isEqualTo(RoleTypeEnum.CUSTOM.getType());
        assertThat(role.getStatus()).isEqualTo(CommonStatusEnum.ENABLE.getStatus());
        assertThat(role.getDataScope()).isEqualTo(DataScopeEnum.SELF.getScope());
        verify(roleMapper).insert(role);
    }

    // ---------- updateRole ----------

    @Test
    void updateRole_notExists_throws() {
        when(roleMapper.selectById(role.getId())).thenReturn(null);

        assertServiceException(ErrorCodeConstants.ROLE_NOT_EXISTS.getCode(), () -> roleService.updateRole(role));
        verify(roleMapper, never()).updateById(any(RoleDO.class));
    }

    @Test
    void updateRole_systemTypeRole_throws() {
        RoleDO systemRole = role(role.getId(), role.getName(), role.getCode(), CommonStatusEnum.ENABLE.getStatus());
        systemRole.setType(RoleTypeEnum.SYSTEM.getType());
        when(roleMapper.selectById(role.getId())).thenReturn(systemRole);

        assertServiceException(
                ErrorCodeConstants.ROLE_CAN_NOT_UPDATE_SYSTEM_TYPE_ROLE.getCode(), () -> roleService.updateRole(role));
        verify(roleMapper, never()).updateById(any(RoleDO.class));
    }

    @Test
    void updateRole_nameTakenByOther_throws() {
        when(roleMapper.selectById(role.getId())).thenReturn(role);
        when(roleMapper.selectByName(role.getName()))
                .thenReturn(role(99L, role.getName(), "ANOTHER", CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(ErrorCodeConstants.ROLE_NAME_DUPLICATE.getCode(), () -> roleService.updateRole(role));
        verify(roleMapper, never()).updateById(any(RoleDO.class));
    }

    @Test
    void updateRole_codeTakenByOther_throws() {
        when(roleMapper.selectById(role.getId())).thenReturn(role);
        when(roleMapper.selectByName(role.getName())).thenReturn(null);
        when(roleMapper.selectByCode(role.getCode()))
                .thenReturn(role(99L, "其他角色", role.getCode(), CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(ErrorCodeConstants.ROLE_CODE_DUPLICATE.getCode(), () -> roleService.updateRole(role));
        verify(roleMapper, never()).updateById(any(RoleDO.class));
    }

    @Test
    void updateRole_disableWhileReferenced_throws() {
        RoleDO disabledRole = role(role.getId(), role.getName(), role.getCode(), CommonStatusEnum.DISABLE.getStatus());
        when(roleMapper.selectById(role.getId())).thenReturn(role);
        when(roleMapper.selectByName(role.getName())).thenReturn(null);
        when(roleMapper.selectByCode(role.getCode())).thenReturn(null);
        when(userRoleMapper.selectListByRoleIds(List.of(role.getId()))).thenReturn(List.of(new UserRoleDO()));

        assertServiceException(
                ErrorCodeConstants.ROLE_IS_REFERENCED.getCode(), () -> roleService.updateRole(disabledRole));
        verify(roleMapper, never()).updateById(any(RoleDO.class));
    }

    @Test
    void updateRole_disableWithoutReferenced_succeeds() {
        RoleDO disabledRole = role(role.getId(), role.getName(), role.getCode(), CommonStatusEnum.DISABLE.getStatus());
        when(roleMapper.selectById(role.getId())).thenReturn(role);
        when(roleMapper.selectByName(role.getName())).thenReturn(role);
        when(roleMapper.selectByCode(role.getCode())).thenReturn(role);
        when(userRoleMapper.selectListByRoleIds(List.of(role.getId()))).thenReturn(List.of());

        roleService.updateRole(disabledRole);

        verify(roleMapper).updateById(disabledRole);
    }

    @Test
    void updateRole_codeEmpty_skipsCodeCheck_succeeds() {
        RoleDO updateObj = role(role.getId(), role.getName(), "", CommonStatusEnum.ENABLE.getStatus());
        when(roleMapper.selectById(role.getId())).thenReturn(role);
        when(roleMapper.selectByName(role.getName())).thenReturn(role);

        roleService.updateRole(updateObj);

        verify(roleMapper).updateById(updateObj);
        verify(roleMapper, never()).selectByCode(any());
    }

    // ---------- updateRoleDataScope ----------

    @Test
    void updateRoleDataScope_notExists_throws() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(null);

        assertServiceException(
                ErrorCodeConstants.ROLE_NOT_EXISTS.getCode(),
                () -> roleService.updateRoleDataScope(ROLE_ID, DataScopeEnum.ALL.getScope(), null));
    }

    @Test
    void updateRoleDataScope_success() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);

        roleService.updateRoleDataScope(ROLE_ID, DataScopeEnum.DEPT_ONLY.getScope(), Set.of(7L));

        org.mockito.ArgumentCaptor<RoleDO> roleCaptor = org.mockito.ArgumentCaptor.forClass(RoleDO.class);
        verify(roleMapper).updateById(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getDataScopeDeptIds()).isEmpty();
        verify(deptService, never()).validateDeptListForReferenceWrite(any());
    }

    @Test
    void updateRoleDataScope_customScope_validatesAndSortsDepartments() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);
        Set<Long> deptIds = Set.of(8L, 7L);

        roleService.updateRoleDataScope(ROLE_ID, DataScopeEnum.DEPT_CUSTOM.getScope(), deptIds);

        verify(deptService).validateDeptListForReferenceWrite(deptIds);
        org.mockito.ArgumentCaptor<RoleDO> roleCaptor = org.mockito.ArgumentCaptor.forClass(RoleDO.class);
        verify(roleMapper).updateById(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getDataScopeDeptIds()).containsExactly(7L, 8L);
    }

    @Test
    void updateRoleDataScope_unknownScope_failsClosed() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);

        assertServiceException(
                ErrorCodeConstants.ROLE_DATA_SCOPE_INVALID.getCode(),
                () -> roleService.updateRoleDataScope(ROLE_ID, 999, Set.of(7L)));

        verify(roleMapper, never()).updateById(any(RoleDO.class));
    }

    // ---------- deleteRole ----------

    @Test
    void deleteRole_notExists_throws() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(null);

        assertServiceException(ErrorCodeConstants.ROLE_NOT_EXISTS.getCode(), () -> roleService.deleteRole(ROLE_ID));
    }

    @Test
    void deleteRole_systemTypeRole_throws() {
        RoleDO systemRole = role(ROLE_ID, role.getName(), role.getCode(), CommonStatusEnum.ENABLE.getStatus());
        systemRole.setType(RoleTypeEnum.SYSTEM.getType());
        when(roleMapper.selectById(ROLE_ID)).thenReturn(systemRole);

        assertServiceException(
                ErrorCodeConstants.ROLE_CAN_NOT_UPDATE_SYSTEM_TYPE_ROLE.getCode(),
                () -> roleService.deleteRole(ROLE_ID));
        verify(roleMapper, never()).deleteById(ROLE_ID);
    }

    @Test
    void deleteRole_success() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        roleService.deleteRole(ROLE_ID);

        verify(roleMapper).deleteById(ROLE_ID);
        verify(permissionService).processRoleDeleted(ROLE_ID);
    }

    // ---------- deleteRoleList ----------

    @Test
    void deleteRoleList_notExists_throws() {
        List<Long> ids = List.of(1L, 2L);
        when(roleMapper.selectById(1L)).thenReturn(null);

        assertServiceException(ErrorCodeConstants.ROLE_NOT_EXISTS.getCode(), () -> roleService.deleteRoleList(ids));
        verify(roleMapper, never()).deleteByIds(ids);
    }

    @Test
    void deleteRoleList_batchDeletes() {
        List<Long> ids = List.of(1L, 2L);
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "角色1", "CODE_1", CommonStatusEnum.ENABLE.getStatus()));
        when(roleMapper.selectById(2L)).thenReturn(role(2L, "角色2", "CODE_2", CommonStatusEnum.ENABLE.getStatus()));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        roleService.deleteRoleList(ids);

        verify(roleMapper).deleteByIds(ids);
        verify(permissionService).processRoleDeleted(1L);
        verify(permissionService).processRoleDeleted(2L);
    }

    // ---------- validateRoleList ----------

    @Test
    void validateRoleList_empty_doesNothing() {
        assertThatCode(() -> roleService.validateRoleList(List.of())).doesNotThrowAnyException();
    }

    @Test
    void validateRoleList_roleNotExists_throws() {
        List<Long> ids = List.of(1L, 2L);
        RoleDO exist = role(1L, "角色1", "CODE_1", CommonStatusEnum.ENABLE.getStatus());
        when(roleMapper.selectByIds(ids)).thenReturn(List.of(exist));

        assertServiceException(ErrorCodeConstants.ROLE_NOT_EXISTS.getCode(), () -> roleService.validateRoleList(ids));
    }

    @Test
    void validateRoleList_roleDisabled_throws() {
        List<Long> ids = List.of(1L);
        RoleDO disabled = role(1L, "角色1", "CODE_1", CommonStatusEnum.DISABLE.getStatus());
        when(roleMapper.selectByIds(ids)).thenReturn(List.of(disabled));

        assertServiceException(ErrorCodeConstants.ROLE_IS_DISABLE.getCode(), () -> roleService.validateRoleList(ids));
    }

    @Test
    void validateRoleList_allValid_passes() {
        List<Long> ids = List.of(1L, 2L);
        RoleDO a = role(1L, "角色1", "CODE_1", CommonStatusEnum.ENABLE.getStatus());
        RoleDO b = role(2L, "角色2", "CODE_2", CommonStatusEnum.ENABLE.getStatus());
        when(roleMapper.selectByIds(ids)).thenReturn(List.of(a, b));

        assertThatCode(() -> roleService.validateRoleList(ids)).doesNotThrowAnyException();
    }

    // ---------- getters ----------

    @Test
    void getRole_returnsFromMapper() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);

        assertThat(roleService.getRole(ROLE_ID)).isSameAs(role);
    }

    @Test
    void getRoleListByStatus_delegates() {
        List<RoleDO> roles = List.of(role);
        when(roleMapper.selectListByStatus(List.of(CommonStatusEnum.ENABLE.getStatus())))
                .thenReturn(roles);

        assertThat(roleService.getRoleListByStatus(List.of(CommonStatusEnum.ENABLE.getStatus())))
                .isSameAs(roles);
    }

    @Test
    void getRoleList_delegates() {
        List<RoleDO> roles = List.of(role);
        when(roleMapper.selectList()).thenReturn(roles);

        assertThat(roleService.getRoleList()).isSameAs(roles);
    }

    @Test
    void getRoleList_emptyIds_returnsEmpty() {
        assertThat(roleService.getRoleList(List.of())).isEmpty();
    }

    @Test
    void getRoleList_byIds_delegates() {
        List<Long> ids = List.of(1L, 2L);
        List<RoleDO> roles = List.of(role);
        when(roleMapper.selectByIds(ids)).thenReturn(roles);

        assertThat(roleService.getRoleList(ids)).isSameAs(roles);
    }

    // ---------- helpers ----------

    private RoleDO role(Long id, String name, String code, Integer status) {
        RoleDO role = new RoleDO();
        role.setId(id);
        role.setName(name);
        role.setCode(code);
        role.setStatus(status);
        return role;
    }
}
