package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.PASSWORD_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.mzt.logapi.context.LogRecordContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AdminUserServiceImpl#updateUserPassword(Long, Long, String)} 管理员重置密码链路的单元测试：
 * 超管目标特权校验、新哈希写入、must_change_password 置位与全会话撤销。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserPasswordResetTest {

    private AdminUserServiceImpl userService;

    @Mock
    private AdminUserMapper userMapper;

    @Mock
    private DeptService deptService;

    @Mock
    private ObjectProvider<PermissionService> permissionServiceProvider;

    @Mock
    private PermissionService permissionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PasswordPolicy passwordPolicy;

    @Mock
    private UserSessionRevocationPublisher sessionRevocationPublisher;

    @Mock
    private UserPostMapper userPostMapper;

    @Mock
    private AdminUserUniqueValidator userValidator;

    @Mock
    private UserImportHandler importHandler;

    @Captor
    private ArgumentCaptor<AdminUserDO> userCaptor;

    @BeforeEach
    void wireCollaborators() {
        // 重置链路经过真实的 AdminUserPasswordManager，mock 仅截断数据库与外部协作者
        AdminUserPasswordManager passwordManager =
                new AdminUserPasswordManager(userMapper, passwordEncoder, passwordPolicy, sessionRevocationPublisher);
        userService = new AdminUserServiceImpl(
                userMapper,
                deptService,
                permissionServiceProvider,
                sessionRevocationPublisher,
                userPostMapper,
                userValidator,
                passwordManager,
                passwordPolicy,
                importHandler);
    }

    @Test
    void updateUserPassword_adminReset_revokesAllSessions() {
        AdminUserDO user = new AdminUserDO().setId(1L).setUsername("admin").setNickname("管理员");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(userMapper.updateById(any(AdminUserDO.class))).thenReturn(1);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserPassword(2L, 1L, "new-password");

            verify(permissionService).validatePrivilegedUserMutation(2L, List.of(1L));
            verify(passwordPolicy).validate("new-password", "admin");
            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(AdminUserDO::getId, AdminUserDO::getPassword, AdminUserDO::getMustChangePassword)
                    .containsExactly(1L, "encoded-new", Boolean.TRUE);
            verify(sessionRevocationPublisher).revokeAdminSession(1L, PASSWORD_CHANGED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserPassword_adminReset_rejectsPrivilegedTargetForPlainOperator() {
        // 普通操作者重置超管密码等效于接管账号，直接被特权校验拒绝，不触碰密码字段
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(2L, List.of(1L));

        assertThatThrownBy(() -> userService.updateUserPassword(2L, 1L, "new-password"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verify(userMapper, never()).updateById(any(AdminUserDO.class));
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void adminReset_rejectsAccountDeletedAfterValidation() {
        when(userMapper.selectById(7L)).thenReturn(new AdminUserDO().setId(7L).setUsername("operator"));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(userMapper.updateById(any(AdminUserDO.class))).thenReturn(0);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        assertThatThrownBy(() -> userService.updateUserPassword(8L, 7L, "new-password"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(USER_NOT_EXISTS.getCode()));
        verifyNoInteractions(sessionRevocationPublisher);
    }
}
