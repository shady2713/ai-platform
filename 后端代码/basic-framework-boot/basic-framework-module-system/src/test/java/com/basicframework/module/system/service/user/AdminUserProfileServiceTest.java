package com.basicframework.module.system.service.user;

import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_INFO_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.mzt.logapi.context.LogRecordContext;
import jakarta.validation.Validator;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 验证资料编辑的当前读、岗位差量与失败写入边界。 */
@ExtendWith(MockitoExtension.class)
class AdminUserProfileServiceTest {

    @Mock
    private AdminUserMapper userMapper;

    @Mock
    private UserPostMapper userPostMapper;

    @Mock
    private DeptService deptService;

    @Mock
    private PostService postService;

    @Mock
    private ObjectProvider<PermissionService> permissionProvider;

    @Mock
    private PermissionService permissionService;

    @Mock
    private UserSessionRevocationPublisher revocations;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PasswordPolicy passwordPolicy;

    @Mock
    private Validator validator;

    private AdminUserServiceImpl userService;

    @BeforeEach
    void wireRealCollaborators() {
        AdminUserUniqueValidator uniqueValidator = new AdminUserUniqueValidator(userMapper, deptService, postService);
        userService = new AdminUserServiceImpl(
                userMapper,
                deptService,
                permissionProvider,
                revocations,
                userPostMapper,
                uniqueValidator,
                new AdminUserPasswordManager(userMapper, passwordEncoder, passwordPolicy, revocations),
                passwordPolicy,
                new UserImportHandler(
                        userMapper,
                        uniqueValidator,
                        deptService,
                        passwordEncoder,
                        validator,
                        revocations,
                        permissionProvider));
    }

    @Test
    void managedEditUsesLockedIdentityAndCurrentPostRelations() {
        AdminUserDO stale = new AdminUserDO().setId(7L).setNickname("原昵称").setDeptId(1L);
        AdminUserDO current = new AdminUserDO().setId(7L).setNickname("当前昵称").setDeptId(2L);
        AdminUserDO edit = new AdminUserDO()
                .setId(7L)
                .setNickname("原昵称")
                .setDeptId(1L)
                .setPostIds(Set.of(12L, 13L))
                .setAvatar("https://example.com/avatar.png");
        when(userMapper.selectById(7L)).thenReturn(stale);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(current);
        when(userMapper.updateManagedUser(edit)).thenReturn(1);
        when(userPostMapper.selectListByUserIdForUpdate(7L))
                .thenReturn(List.of(
                        new UserPostDO().setUserId(7L).setPostId(11L),
                        new UserPostDO().setUserId(7L).setPostId(12L)));
        LogRecordContext.putEmptySpan();
        try {
            when(permissionProvider.getObject()).thenReturn(permissionService);
            userService.updateUser(9L, edit);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UserPostDO>> inserted = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> removed = ArgumentCaptor.forClass(Collection.class);
            InOrder order = inOrder(userMapper, userPostMapper, revocations);
            order.verify(userMapper).selectByIdForUpdate(7L);
            order.verify(userMapper).updateManagedUser(edit);
            order.verify(userPostMapper).selectListByUserIdForUpdate(7L);
            order.verify(userPostMapper).insertBatch(inserted.capture());
            order.verify(userPostMapper)
                    .deleteByUserIdAndPostId(org.mockito.ArgumentMatchers.eq(7L), removed.capture());
            order.verify(revocations).revokeAdminSession(7L, USER_INFO_CHANGED);
            assertThat(inserted.getValue()).singleElement().satisfies(post -> {
                assertThat(post.getUserId()).isEqualTo(7L);
                assertThat(post.getPostId()).isEqualTo(13L);
            });
            assertThat(removed.getValue()).containsExactly(11L);
            verify(userPostMapper, never()).selectListByUserId(7L);
        } finally {
            LogRecordContext.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingWriteCannotProducePostChangesOrSessionRevocation(boolean managed) {
        AdminUserDO current = new AdminUserDO().setId(7L).setNickname("原昵称");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(current);
        if (managed) {
            when(userMapper.selectById(7L)).thenReturn(current);
            when(permissionProvider.getObject()).thenReturn(permissionService);
        }
        AdminUserDO edit = new AdminUserDO().setId(7L).setNickname("新昵称").setPostIds(Set.of(11L));

        assertThatThrownBy(() -> {
                    if (managed) userService.updateUser(9L, edit);
                    else userService.updateUserProfile(7L, edit);
                })
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(USER_NOT_EXISTS.getCode()));
        verifyNoInteractions(userPostMapper, revocations);
    }

    @Test
    void contactUnbindingPreservesSessionWhenIdentityFieldsAreOmitted() {
        AdminUserDO current = new AdminUserDO()
                .setId(7L)
                .setNickname("原昵称")
                .setMobile("13900000001")
                .setEmail("owner@example.com");
        AdminUserDO edit = new AdminUserDO().setEmail(" ").setMobile(" ");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(current);
        when(userMapper.updateUserProfile(edit)).thenReturn(1);

        userService.updateUserProfile(7L, edit);

        ArgumentCaptor<AdminUserDO> written = ArgumentCaptor.forClass(AdminUserDO.class);
        verify(userMapper).updateUserProfile(written.capture());
        assertThat(written.getValue().getEmail()).isNull();
        assertThat(written.getValue().getMobile()).isNull();
        assertThat(written.getValue().getNickname()).isNull();
        verifyNoInteractions(userPostMapper, revocations);
    }
}
