package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_IMPORT_LIST_IS_EMPTY;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_MOBILE_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_INFO_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
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
 * 覆盖用户导入契约：新用户为禁用状态 + SecureRandom 随机密码（忽略 Excel 状态列），
 * 更新分支不重置密码、不写状态列且需通过超管目标校验，按行收集失败。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserImportServiceTest {

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

    // ========== 新增导入：禁用状态 + 随机密码 ==========

    @Test
    void importUserList_newUsers_statusDisabled() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        UserImportResultDTO respDTO =
                userService.importUserList(9L, List.of(buildImportUser("zhangsan"), buildImportUser("lisi")), false);

        assertThat(respDTO.getCreateUsernames()).containsExactly("zhangsan", "lisi");
        assertThat(respDTO.getFailureUsernames()).isEmpty();
        verify(userMapper, times(2)).insert(userCaptor.capture());
        // 导入的新用户必须为禁用状态，等待管理员重置密码并启用
        assertThat(userCaptor.getAllValues())
                .allSatisfy(user -> assertThat(user.getStatus()).isEqualTo(CommonStatusEnum.DISABLE.getStatus()));
    }

    @Test
    void importUserList_normalizesUsernameBeforeLookupAndStorage() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");

        UserImportResultDTO result = userService.importUserList(9L, List.of(buildImportUser("  User_123  ")), false);

        assertThat(result.getCreateUsernames()).containsExactly("user_123");
        verify(userMapper).selectByUsername("user_123");
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("user_123");
    }

    @Test
    void importUserList_newUsers_randomPasswordPerUser() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        userService.importUserList(9L, List.of(buildImportUser("zhangsan"), buildImportUser("lisi")), false);

        // 每个用户独立的 32 位小写十六进制随机密码（128 bit），不存在共享初始密码
        verify(passwordEncoder, times(2)).encode(rawPasswordCaptor.capture());
        List<String> rawPasswords = rawPasswordCaptor.getAllValues();
        assertThat(rawPasswords).allSatisfy(raw -> assertThat(raw).matches("^[0-9a-f]{32}$"));
        assertThat(rawPasswords.get(0)).isNotEqualTo(rawPasswords.get(1));
    }

    @Test
    void importUserList_excelStatusEnabled_stillDisabled() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        // Excel 中填写"开启"状态，不允许绕过"导入即禁用"的安全约束
        UserImportDTO importUser = buildImportUser("zhangsan");
        importUser.setStatus(CommonStatusEnum.ENABLE.getStatus());

        UserImportResultDTO respDTO = userService.importUserList(9L, List.of(importUser), false);

        assertThat(respDTO.getCreateUsernames()).containsExactly("zhangsan");
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(CommonStatusEnum.DISABLE.getStatus());
    }

    // ========== 共享初始密码配置已删除 ==========

    @Test
    void importUserList_noInitPasswordConfig_succeeds() {
        // 不准备任何配置：改造前此处会抛 USER_IMPORT_INIT_PASSWORD，改造后必须导入成功
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        UserImportResultDTO respDTO = userService.importUserList(9L, List.of(buildImportUser("zhangsan")), false);

        assertThat(respDTO.getCreateUsernames()).containsExactly("zhangsan");
        assertThat(respDTO.getFailureUsernames()).isEmpty();
        // 负向断言：类上不存在 ConfigApi 依赖与 USER_INIT_PASSWORD_KEY 常量（ConfigApi 已随本切片删除）
        List<Field> fields = Arrays.asList(AdminUserServiceImpl.class.getDeclaredFields());
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.getType().getSimpleName()).isNotEqualTo("ConfigApi");
            assertThat(field.getName()).isNotEqualTo("USER_INIT_PASSWORD_KEY");
        });
        List<Field> staticFields = fields.stream()
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .toList();
        assertThat(staticFields).allSatisfy(field -> assertThat(field.getName()).doesNotContain("PASSWORD_KEY"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importUserList_validationFailure_returnsDeclaredConstraintMessage() {
        UserImportDTO importUser = buildImportUser(" ");
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("用户账号不能为空");
        when(validator.validate(any(), any(Class[].class))).thenReturn(Set.of(violation));

        UserImportResultDTO result = userService.importUserList(9L, List.of(importUser), false);

        assertThat(result.getFailureUsernames()).containsEntry("第 1 行", "用户账号不能为空");
        verify(userMapper, never()).insert(any(AdminUserDO.class));
    }

    @Test
    void importUserList_businessFailure_returnsExplicitPublicMessage() {
        UserImportDTO importUser = buildImportUser("zhangsan").setMobile("13812345678");
        when(userMapper.selectByMobile("13812345678"))
                .thenReturn(new AdminUserDO().setId(99L).setMobile("13812345678"));

        UserImportResultDTO result = userService.importUserList(9L, List.of(importUser), false);

        assertThat(result.getFailureUsernames()).containsEntry("zhangsan", USER_MOBILE_EXISTS.getMsg());
        verify(userMapper, never()).insert(any(AdminUserDO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstConstraintViolationMessage_blankDeclaredMessage_usesStableFallback() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn(" ");

        String message = UserImportHandler.firstConstraintViolationMessage(
                new jakarta.validation.ConstraintViolationException(Set.of(violation)));

        assertThat(message).isEqualTo("用户信息校验失败");
    }

    // ========== 更新已存在用户：不重置密码，未传状态时保留原值 ==========

    @Test
    void importUserList_existingUserUpdate_passwordUntouched() {
        AdminUserDO existUser = new AdminUserDO()
                .setId(100L)
                .setUsername("zhangsan")
                .setPassword("old-encoded-password")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(userMapper.selectByUsername("zhangsan")).thenReturn(existUser);
        when(userMapper.selectById(100L)).thenReturn(existUser);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        when(userMapper.updateById(any(AdminUserDO.class))).thenReturn(1);

        UserImportResultDTO respDTO = userService.importUserList(9L, List.of(buildImportUser("zhangsan")), true);

        assertThat(respDTO.getUpdateUsernames()).containsExactly("zhangsan");
        verify(userMapper).updateById(userCaptor.capture());
        // 更新路径不得重置密码；状态为 null，不参与 updateById 更新，已有账号的启用状态保留
        assertThat(userCaptor.getValue().getPassword()).isNull();
        assertThat(userCaptor.getValue().getStatus()).isNull();
        verify(passwordEncoder, never()).encode(anyString());
    }

    // ========== 参数校验 ==========

    @Test
    void importUserList_emptyList_throws() {
        assertThatThrownBy(() -> userService.importUserList(9L, List.of(), false))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(USER_IMPORT_LIST_IS_EMPTY.getCode()));
    }

    @Test
    void importUserList_keepsOwnContactsAndRevokesSessionsForImportedDisable() {
        AdminUserDO user = new AdminUserDO()
                .setId(7L)
                .setUsername("operator")
                .setMobile("13812345678")
                .setEmail("operator@example.com")
                .setStatus(0);
        when(userMapper.selectByUsername("operator")).thenReturn(user);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(userMapper.selectByMobile(user.getMobile())).thenReturn(user);
        when(userMapper.selectByEmail(user.getEmail())).thenReturn(user);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        when(userMapper.updateById(any(AdminUserDO.class))).thenReturn(1);
        UserImportDTO row = buildImportUser("operator")
                .setMobile(user.getMobile())
                .setEmail(user.getEmail())
                .setStatus(1);
        UserImportResultDTO result = userService.importUserList(9L, List.of(row), true);
        assertThat(result.getFailureUsernames()).isEmpty();
        assertThat(result.getUpdateUsernames()).containsExactly("operator");
        verify(userMapper).updateById(userCaptor.capture());
        // Excel 携带的账号状态列被忽略：状态只能经 update-status 专用链路变更，禁用账号保持禁用
        assertThat(userCaptor.getValue().getStatus()).isNull();
        assertThat(userCaptor.getValue().getPassword()).isNull();
        assertThat(userCaptor.getValue().getMustChangePassword()).isNull();
        verify(sessionRevocationPublisher).revokeAdminSession(7L, USER_INFO_CHANGED);
    }

    @Test
    void importUserList_rejectsAccountDeletedAfterValidation() {
        AdminUserDO existing = new AdminUserDO().setId(7L).setUsername("operator");
        when(userMapper.selectByUsername("operator")).thenReturn(existing);
        when(userMapper.selectById(7L)).thenReturn(existing);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        when(userMapper.updateById(any(AdminUserDO.class))).thenReturn(0);

        assertThatThrownBy(() -> userService.importUserList(9L, List.of(buildImportUser("operator")), true))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(USER_NOT_EXISTS.getCode()));
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void importUserList_updatePrivilegedTargetRow_failsThatRowWithoutWriting() {
        AdminUserDO existUser = new AdminUserDO().setId(100L).setUsername("zhangsan");
        when(userMapper.selectByUsername("zhangsan")).thenReturn(existUser);
        when(userMapper.selectById(100L)).thenReturn(existUser);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        doThrow(exception(ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN))
                .when(permissionService)
                .validatePrivilegedUserMutation(eq(9L), argThat(ids -> ids.contains(100L)));

        UserImportResultDTO result = userService.importUserList(9L, List.of(buildImportUser("zhangsan")), true);

        assertThat(result.getUpdateUsernames()).isEmpty();
        assertThat(result.getFailureUsernames()).containsOnlyKeys("zhangsan");
        verify(userMapper, never()).updateById(any(AdminUserDO.class));
        verifyNoInteractions(sessionRevocationPublisher);
    }

    private UserImportDTO buildImportUser(String username) {
        return new UserImportDTO().setUsername(username).setNickname("昵称" + username);
    }
}
