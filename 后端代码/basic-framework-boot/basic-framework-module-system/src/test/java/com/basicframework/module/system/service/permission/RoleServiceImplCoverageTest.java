package com.basicframework.module.system.service.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 角色 Service 补充契约测试：与 {@link RoleServiceImplTest} 同包分文件，覆盖其未覆盖的空集合短路、
 * 数据范围空部门集合与受影响会话撤销等分支。角色读取与超级管理员判定使用当前数据库结果。
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplCoverageTest {

    private static final Long ROLE_ID = 1L;

    @InjectMocks
    private RoleServiceImpl roleService;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private DeptService deptService;

    @Mock
    private ObjectProvider<PermissionService> permissionServiceProvider;

    @Mock
    private UserSessionRevocationPublisher sessionRevocationPublisher;

    private RoleDO role;

    @BeforeEach
    void setUp() {
        role = new RoleDO();
        role.setId(ROLE_ID);
        role.setName("测试角色");
        role.setCode("TEST_ROLE");
        role.setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void getRoleList_emptyIds_returnsEmptyList() {
        assertThat(roleService.getRoleList(List.of())).isEmpty();

        verify(roleMapper, never()).selectById(any());
    }

    @Test
    void hasAnySuperAdmin_emptyIds_returnsFalse() {
        assertThat(roleService.hasAnySuperAdmin(List.of())).isFalse();
    }

    @Test
    void hasAnySuperAdmin_readsCurrentRoleCodesInOneBatch() {
        when(roleMapper.selectByIds(Set.of(ROLE_ID)))
                .thenReturn(List.of(new RoleDO().setId(ROLE_ID).setCode("super_admin")), List.of(role));
        assertThat(roleService.hasAnySuperAdmin(Set.of(ROLE_ID))).isTrue();
        assertThat(roleService.hasAnySuperAdmin(Set.of(ROLE_ID))).isFalse();
        verify(roleMapper, never()).selectById(any());
    }

    @Test
    void getRole_delegatesToMapper() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);

        assertThat(roleService.getRole(ROLE_ID)).isSameAs(role);
    }

    @Test
    void getRolePage_delegatesToMapper() {
        PageParam pageParam = new PageParam();
        PageResult<RoleDO> pageResult = new PageResult<>(List.of(role), 1L);
        LocalDateTime[] createTime = new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()};
        when(roleMapper.selectPage(pageParam, null, null, null, createTime)).thenReturn(pageResult);

        assertThat(roleService.getRolePage(pageParam, null, null, null, createTime))
                .isSameAs(pageResult);
    }

    @Test
    void updateRoleDataScope_customScopeWithoutDeptIds_validatesEmptyCollection() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);

        roleService.updateRoleDataScope(ROLE_ID, DataScopeEnum.DEPT_CUSTOM.getScope(), null);

        verify(deptService).validateDeptListForReferenceWrite(Set.of());
        ArgumentCaptor<RoleDO> roleCaptor = ArgumentCaptor.forClass(RoleDO.class);
        verify(roleMapper).updateById(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getDataScopeDeptIds()).isEmpty();
    }

    @Test
    void updateRoleDataScope_revokesAffectedUserSessions() {
        when(roleMapper.selectById(ROLE_ID)).thenReturn(role);
        when(userRoleMapper.selectListByRoleIds(Set.of(ROLE_ID))).thenReturn(List.of(new UserRoleDO().setUserId(7L)));

        roleService.updateRoleDataScope(ROLE_ID, DataScopeEnum.DEPT_ONLY.getScope(), Set.of(9L));

        verify(sessionRevocationPublisher)
                .revokeAdminSessions(Set.of(7L), UserSessionRevocationReasonEnum.ROLE_PERMISSION_CHANGED);
    }
}
