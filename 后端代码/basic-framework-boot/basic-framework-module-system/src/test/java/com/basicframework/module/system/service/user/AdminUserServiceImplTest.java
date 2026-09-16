package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.PASSWORD_CHANGED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_DISABLED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_INFO_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserQuery;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.mzt.logapi.context.LogRecordContext;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
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
 * {@link AdminUserServiceImpl#importUserList} 的单元测试
 *
 * 覆盖用户导入契约：新用户为禁用状态 + SecureRandom 随机密码，
 * 不再读取 system.user.init-password 共享配置，由管理员重置密码并启用后激活。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    // 协作者持真实实现并共享下方 mock，便于对 service 契约做端到端断言
    private AdminUserUniqueValidator userValidator;

    private AdminUserPasswordManager passwordManager;

    private UserImportHandler importHandler;

    private AdminUserServiceImpl userService;

    @Mock
    private AdminUserMapper userMapper;

    @Mock
    private DeptService deptService;

    @Mock
    private PostService postService;

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
    private Validator validator;

    @Captor
    private ArgumentCaptor<AdminUserDO> userCaptor;

    @Captor
    private ArgumentCaptor<String> rawPasswordCaptor;

    @BeforeEach
    void wireCollaborators() {
        userValidator = new AdminUserUniqueValidator(userMapper, deptService, postService);
        passwordManager =
                new AdminUserPasswordManager(userMapper, passwordEncoder, passwordPolicy, sessionRevocationPublisher);
        importHandler = new UserImportHandler(
                userMapper,
                userValidator,
                deptService,
                passwordEncoder,
                validator,
                sessionRevocationPublisher,
                permissionServiceProvider);
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
    void getUserByMobile_normalizesInputBeforeLookup() {
        AdminUserDO user = new AdminUserDO().setId(1L).setMobile("13812345678");
        when(userMapper.selectByMobile("13812345678")).thenReturn(user);

        AdminUserDO result = userService.getUserByMobile(" 13812345678 ");

        assertThat(result).isSameAs(user);
        verify(userMapper).selectByMobile("13812345678");
    }

    @Test
    void createUser_normalizesIdentityFieldsAndRunsPasswordPolicy() {
        AdminUserDO user = new AdminUserDO()
                .setId(7L)
                .setUsername("  Alice_01  ")
                .setNickname("  Alice😀  ")
                .setMobile(" 13812345678 ")
                .setEmail("Alice@Example.COM ")
                .setPassword("violet river orbits quietly!")
                .setPostIds(Set.of(11L, 12L));
        when(passwordEncoder.encode("violet river orbits quietly!")).thenReturn("encoded-password");
        LogRecordContext.putEmptySpan();

        try {
            userService.createUser(user);

            verify(passwordPolicy).validate("violet river orbits quietly!", "alice_01");
            verify(userMapper).insert(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(
                            AdminUserDO::getUsername,
                            AdminUserDO::getNickname,
                            AdminUserDO::getMobile,
                            AdminUserDO::getEmail,
                            AdminUserDO::getPassword,
                            AdminUserDO::getMustChangePassword)
                    .containsExactly(
                            "alice_01",
                            "Alice😀",
                            "13812345678",
                            "Alice@example.com",
                            "encoded-password",
                            Boolean.TRUE);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UserPostDO>> posts = ArgumentCaptor.forClass(List.class);
            verify(userPostMapper).insertBatch(posts.capture());
            assertThat(posts.getValue()).extracting(UserPostDO::getUserId).containsOnly(7L);
            assertThat(posts.getValue()).extracting(UserPostDO::getPostId).containsExactlyInAnyOrder(11L, 12L);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void deleteUser_clearsDepartmentLeaderBeforeDeletingUser() {
        AdminUserDO user = new AdminUserDO().setId(1L).setUsername("admin");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        LogRecordContext.putEmptySpan();

        try {
            userService.deleteUser(2L, 1L);

            verify(deptService).processUserDeleted(1L);
            verify(userMapper).deleteById(1L);
            verify(permissionService).validatePrivilegedUserMutation(2L, List.of(1L));
            verify(permissionService).processUserDeleted(1L);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void deleteUser_rejectsSelfDeletion() {
        AdminUserDO user = new AdminUserDO().setId(1L).setUsername("admin");
        when(userMapper.selectById(1L)).thenReturn(user);

        assertThatThrownBy(() -> userService.deleteUser(1L, 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能删除或禁用当前登录账号");
        verify(userMapper, never()).deleteById(1L);
        verifyNoInteractions(permissionService);
    }

    @Test
    void deleteUserList_rejectsListContainingOperator() {
        when(userMapper.selectByIds(List.of(1L, 2L)))
                .thenReturn(List.of(new AdminUserDO().setId(1L), new AdminUserDO().setId(2L)));

        assertThatThrownBy(() -> userService.deleteUserList(1L, List.of(1L, 2L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能删除或禁用当前登录账号");
        verify(userMapper, never()).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void updateUserStatus_disableRejectsSelfOperation() {
        AdminUserDO user = new AdminUserDO().setId(1L).setNickname("管理员");
        when(userMapper.selectById(1L)).thenReturn(user);

        assertThatThrownBy(() -> userService.updateUserStatus(1L, 1L, CommonStatusEnum.DISABLE.getStatus()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能删除或禁用当前登录账号");
        verify(userMapper, never()).updateById(any(AdminUserDO.class));
        verify(sessionRevocationPublisher, never()).revokeAdminSession(any(), any());
    }

    @Test
    void deleteUserList_rejectsUnknownUserIds() {
        when(userMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(new AdminUserDO().setId(1L)));

        assertThatThrownBy(() -> userService.deleteUserList(3L, List.of(1L, 2L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("用户不存在");
        verify(userMapper, never()).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void updateUserStatus_enableDelegatesPrivilegedCheck() {
        AdminUserDO user = new AdminUserDO().setId(1L).setNickname("管理员");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserStatus(2L, 1L, CommonStatusEnum.ENABLE.getStatus());

            verify(permissionService).validatePrivilegedUserMutation(2L, List.of(1L));
            verify(userMapper).updateById(any(AdminUserDO.class));
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void deleteUserList_clearsEveryDepartmentLeaderReference() {
        when(userMapper.selectByIds(List.of(1L, 2L)))
                .thenReturn(List.of(new AdminUserDO().setId(1L), new AdminUserDO().setId(2L)));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        userService.deleteUserList(3L, List.of(1L, 2L));

        verify(deptService).processUserDeleted(1L);
        verify(deptService).processUserDeleted(2L);
        verify(userMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void deleteUserList_allowsDeletingDisabledUsers() {
        // 删除是数据清理路径，只校验存在性，不施加 validateUserList 的启用状态约束
        when(userMapper.selectByIds(List.of(1L)))
                .thenReturn(List.of(new AdminUserDO().setId(1L).setStatus(CommonStatusEnum.DISABLE.getStatus())));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        userService.deleteUserList(3L, List.of(1L));

        verify(userMapper).deleteByIds(List.of(1L));
    }

    @Test
    void updateUserStatus_disabled_revokesSessionAndBuildsAuditContext() {
        AdminUserDO user = new AdminUserDO().setId(1L).setNickname("管理员");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserStatus(2L, 1L, CommonStatusEnum.DISABLE.getStatus());

            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(AdminUserDO::getId, AdminUserDO::getStatus)
                    .containsExactly(1L, CommonStatusEnum.DISABLE.getStatus());
            verify(sessionRevocationPublisher).revokeAdminSession(1L, USER_DISABLED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
            assertThat(LogRecordContext.getVariable("statusName")).isEqualTo(CommonStatusEnum.DISABLE.getName());
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserProfile_nicknameChanged_revokesSessionsWithStaleIdentitySnapshot() {
        AdminUserDO oldUser = new AdminUserDO().setId(1L).setNickname("旧昵称").setDeptId(2L);
        AdminUserDO update = new AdminUserDO().setNickname("新昵称");
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(oldUser);

        when(userMapper.updateUserProfile(update)).thenReturn(1);
        userService.updateUserProfile(1L, update);

        verify(userMapper).updateUserProfile(update);
        verify(sessionRevocationPublisher).revokeAdminSession(1L, USER_INFO_CHANGED);
    }

    @Test
    void editingMissingOrInaccessibleUserStopsBeforeWritingOrRevoking() {
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        assertThatThrownBy(() -> userService.updateUserProfile(1L, new AdminUserDO().setNickname("新昵称")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() ->
                        userService.updateUser(9L, new AdminUserDO().setId(1L).setNickname("新昵称")))
                .isInstanceOf(ServiceException.class);
        verify(userMapper, never()).updateManagedUser(any());
        verify(userMapper, never()).updateUserProfile(any());
        verifyNoInteractions(sessionRevocationPublisher, userPostMapper);
    }

    @Test
    void updateUser_departmentCleared_revokesSessionsWithStaleDataScope() {
        AdminUserDO oldUser = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setDeptId(2L);
        AdminUserDO update = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setDeptId(null)
                .setPostIds(Set.of());
        when(userMapper.selectById(1L)).thenReturn(oldUser);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(oldUser);
        LogRecordContext.putEmptySpan();

        try {
            when(userMapper.updateManagedUser(update)).thenReturn(1);
            when(permissionServiceProvider.getObject()).thenReturn(permissionService);
            userService.updateUser(9L, update);

            verify(sessionRevocationPublisher).revokeAdminSession(1L, USER_INFO_CHANGED);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserPassword_selfChange_revokesAllSessions() {
        AdminUserDO user = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setStatus(0)
                .setPassword("encoded-old");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(userMapper.completePasswordChange(1L, "encoded-old", "encoded-new", false))
                .thenReturn(1);
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserPassword(1L, "old-password", "new-password");

            verify(passwordPolicy).validate("new-password", "admin");
            verify(userMapper).completePasswordChange(1L, "encoded-old", "encoded-new", false);
            verify(sessionRevocationPublisher).revokeAdminSession(1L, PASSWORD_CHANGED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void completePasswordChange_clearsMustChangeFlagAndRevokesAllSessions() {
        AdminUserDO user = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setMustChangePassword(Boolean.TRUE)
                .setStatus(0)
                .setPassword("old-hash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(userMapper.completePasswordChange(1L, "old-hash", "encoded-new", false))
                .thenReturn(1);
        LogRecordContext.putEmptySpan();

        try {
            userService.completePasswordChange(1L, "new-password");

            verify(passwordPolicy).validate("new-password", "admin");
            verify(userMapper).completePasswordChange(1L, "old-hash", "encoded-new", false);
            verify(sessionRevocationPublisher).revokeAdminSession(1L, PASSWORD_CHANGED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void completeExpiredPasswordChange_rejectsConcurrentCredentialChange() {
        when(userMapper.selectById(1L))
                .thenReturn(new AdminUserDO()
                        .setId(1L)
                        .setUsername("admin")
                        .setStatus(0)
                        .setPassword("changed-hash"));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        assertThatThrownBy(() -> userService.completeExpiredPasswordChange(1L, "original-hash", "new-password"))
                .isInstanceOf(ServiceException.class);
        verify(userMapper).completePasswordChange(1L, "original-hash", "encoded-new", true);
        org.mockito.Mockito.verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void completeExpiredPasswordChange_rejectsDisabledAccount() {
        when(userMapper.selectById(1L))
                .thenReturn(new AdminUserDO().setId(1L).setUsername("admin").setStatus(1));
        assertThatThrownBy(() -> userService.completeExpiredPasswordChange(1L, "hash", "new-password"))
                .isInstanceOf(ServiceException.class);
        org.mockito.Mockito.verifyNoInteractions(passwordEncoder, sessionRevocationPublisher);
    }

    @Test
    void completeExpiredPasswordChange_usesAuthenticatedHashAndRequiresExpiredFlag() {
        AdminUserDO user = new AdminUserDO().setId(1L).setUsername("admin").setStatus(0);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(userMapper.completePasswordChange(1L, "original-hash", "encoded-new", true))
                .thenReturn(1);
        LogRecordContext.putEmptySpan();
        try {
            userService.completeExpiredPasswordChange(1L, "original-hash", "new-password");
            verify(sessionRevocationPublisher).revokeAdminSession(1L, PASSWORD_CHANGED);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void upgradePasswordEncodingIfNeeded_currentStrengthDoesNotWrite() {
        when(passwordEncoder.upgradeEncoding("current-hash")).thenReturn(false);

        assertThat(userService.upgradePasswordEncodingIfNeeded(1L, "verified-password", "current-hash"))
                .isEqualTo("current-hash");

        verify(passwordEncoder).upgradeEncoding("current-hash");
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void upgradePasswordEncodingIfNeeded_oldStrengthUsesCompareAndSetWithoutRevokingSessions() {
        when(passwordEncoder.upgradeEncoding("old-hash")).thenReturn(true);
        when(passwordEncoder.encode("verified-password")).thenReturn("upgraded-hash");

        when(userMapper.updatePasswordIfUnchanged(1L, "old-hash", "upgraded-hash"))
                .thenReturn(1);
        assertThat(userService.upgradePasswordEncodingIfNeeded(1L, "verified-password", "old-hash"))
                .isEqualTo("upgraded-hash");

        verify(userMapper).updatePasswordIfUnchanged(1L, "old-hash", "upgraded-hash");
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void updateUser_statusFieldIgnored_notPersistedAndNoRevocation() {
        AdminUserDO oldUser = new AdminUserDO()
                .setId(7L)
                .setUsername("operator")
                .setNickname("同名")
                .setStatus(0);
        when(userMapper.selectById(7L)).thenReturn(oldUser);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(oldUser);
        LogRecordContext.putEmptySpan();
        try {
            when(userMapper.updateManagedUser(any())).thenReturn(1);
            when(permissionServiceProvider.getObject()).thenReturn(permissionService);
            userService.updateUser(
                    9L,
                    new AdminUserDO()
                            .setId(7L)
                            .setUsername("operator")
                            .setNickname("同名")
                            .setStatus(1));
            verify(userMapper).updateManagedUser(argThat(user -> user.getStatus() == null));
            verifyNoInteractions(sessionRevocationPublisher);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUser_mobileChanged_revokesTargetSessions() {
        AdminUserDO oldUser = new AdminUserDO()
                .setId(7L)
                .setUsername("operator")
                .setNickname("同名")
                .setMobile("13800000000")
                .setStatus(0);
        when(userMapper.selectById(7L)).thenReturn(oldUser);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(oldUser);
        LogRecordContext.putEmptySpan();
        try {
            when(userMapper.updateManagedUser(any())).thenReturn(1);
            when(permissionServiceProvider.getObject()).thenReturn(permissionService);
            userService.updateUser(
                    9L,
                    new AdminUserDO()
                            .setId(7L)
                            .setUsername("operator")
                            .setNickname("同名")
                            .setMobile("13900000000"));
            verify(sessionRevocationPublisher).revokeAdminSession(7L, USER_INFO_CHANGED);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUser_emailChanged_revokesTargetSessions() {
        AdminUserDO oldUser = new AdminUserDO()
                .setId(7L)
                .setUsername("operator")
                .setNickname("同名")
                .setEmail("old@example.com")
                .setStatus(0);
        when(userMapper.selectById(7L)).thenReturn(oldUser);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(oldUser);
        LogRecordContext.putEmptySpan();
        try {
            when(userMapper.updateManagedUser(any())).thenReturn(1);
            when(permissionServiceProvider.getObject()).thenReturn(permissionService);
            userService.updateUser(
                    9L,
                    new AdminUserDO()
                            .setId(7L)
                            .setUsername("operator")
                            .setNickname("同名")
                            .setEmail("new@example.com"));
            verify(sessionRevocationPublisher).revokeAdminSession(7L, USER_INFO_CHANGED);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserPassword_concurrentAdminResetCannotBeOverwritten() {
        AdminUserDO user =
                new AdminUserDO().setId(7L).setUsername("operator").setStatus(0).setPassword("old-hash");
        when(userMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(userMapper.completePasswordChange(7L, "old-hash", "new-hash", false))
                .thenReturn(0);
        assertThatThrownBy(() -> userService.updateUserPassword(7L, "old-password", "new-password"))
                .isInstanceOf(ServiceException.class);
        verify(userMapper, never()).updateById(any(AdminUserDO.class));
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void upgradePasswordEncodingIfNeeded_rejectsLostCredentialRace() {
        when(passwordEncoder.upgradeEncoding("old-hash")).thenReturn(true);
        when(passwordEncoder.encode("verified-password")).thenReturn("upgraded-hash");
        assertThatThrownBy(() -> userService.upgradePasswordEncodingIfNeeded(7L, "verified-password", "old-hash"))
                .isInstanceOf(ServiceException.class);
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void updateUser_rejectsPrivilegedTargetForPlainOperator() {
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(eq(9L), argThat(ids -> ids.contains(1L)));

        assertThatThrownBy(() ->
                        userService.updateUser(9L, new AdminUserDO().setId(1L).setNickname("越权改写")))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verify(userMapper, never()).updateManagedUser(any());
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void deleteUser_rejectsPrivilegedTargetForPlainOperator() {
        when(userMapper.selectById(1L)).thenReturn(new AdminUserDO().setId(1L).setUsername("admin"));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(9L, List.of(1L));

        assertThatThrownBy(() -> userService.deleteUser(9L, 1L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verify(userMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void deleteUserList_rejectsPrivilegedTargetForPlainOperator() {
        when(userMapper.selectByIds(List.of(1L, 2L)))
                .thenReturn(List.of(new AdminUserDO().setId(1L), new AdminUserDO().setId(2L)));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(9L, List.of(1L, 2L));

        assertThatThrownBy(() -> userService.deleteUserList(9L, List.of(1L, 2L)))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verify(userMapper, never()).deleteByIds(any());
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void updateUserStatus_disableRejectsPrivilegedTargetForPlainOperator() {
        when(userMapper.selectById(1L)).thenReturn(new AdminUserDO().setId(1L).setNickname("管理员"));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(9L, List.of(1L));

        assertThatThrownBy(() -> userService.updateUserStatus(9L, 1L, CommonStatusEnum.DISABLE.getStatus()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode()));
        verify(userMapper, never()).updateById(any(AdminUserDO.class));
        verifyNoInteractions(sessionRevocationPublisher);
    }

    // ========== 用户分页：部门条件使用当前子树 ==========

    @Test
    void getUserPage_withDeptId_usesCurrentChildDeptIdsIncludingSelf() {
        when(deptService.getChildDeptIdList(10L)).thenReturn(Set.of(11L, 12L));
        when(userMapper.selectPage(any(PageParam.class), any(AdminUserQuery.class)))
                .thenReturn(PageResult.empty());

        userService.getUserPage(new PageParam(), new AdminUserQuery(null, null, null, null, null, null), 10L, null);

        verify(deptService).getChildDeptIdList(10L);
        verify(deptService, never()).getChildDeptList(any(Long.class));
        ArgumentCaptor<AdminUserQuery> queryCaptor = ArgumentCaptor.forClass(AdminUserQuery.class);
        verify(userMapper).selectPage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getDeptIds()).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void getUserPage_withoutDeptId_skipsDeptLookup() {
        when(userMapper.selectPage(any(PageParam.class), any(AdminUserQuery.class)))
                .thenReturn(PageResult.empty());

        userService.getUserPage(new PageParam(), new AdminUserQuery(null, null, null, null, null, null), null, null);

        verify(deptService, never()).getChildDeptIdList(any());
        ArgumentCaptor<AdminUserQuery> queryCaptor = ArgumentCaptor.forClass(AdminUserQuery.class);
        verify(userMapper).selectPage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getDeptIds()).isEmpty();
    }
}
