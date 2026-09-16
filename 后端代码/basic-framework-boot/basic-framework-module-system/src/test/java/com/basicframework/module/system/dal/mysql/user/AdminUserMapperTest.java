package com.basicframework.module.system.dal.mysql.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminUserMapperTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), AdminUserMapper.class.getName()),
                AdminUserDO.class);
    }

    @Test
    void managedUpdateExcludesCredentialsAndWritesExplicitNullableFields() {
        AdminUserMapper mapper = mock(AdminUserMapper.class, CALLS_REAL_METHODS);
        doReturn(1).when(mapper).update(any(AdminUserDO.class), any(LambdaUpdateWrapper.class));
        AdminUserDO input = new AdminUserDO()
                .setId(7L)
                .setUsername("operator")
                .setNickname("管理员")
                .setPostIds(Set.of())
                .setPassword("untrusted-hash")
                .setMustChangePassword(true);

        assertThat(mapper.updateManagedUser(input)).isEqualTo(1);

        ArgumentCaptor<AdminUserDO> write = ArgumentCaptor.forClass(AdminUserDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AdminUserDO>> condition = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(write.capture(), condition.capture());
        assertThat(write.getValue().getPassword()).isNull();
        assertThat(write.getValue().getMustChangePassword()).isNull();
        assertThat(write.getValue().getPostIds()).isEmpty();
        assertThat(condition.getValue().getSqlSet()).contains("dept_id", "email", "mobile", "remark");
        assertThat(condition.getValue().getParamNameValuePairs().values()).containsNull();
    }

    @Test
    void profileUpdateCannotChangeAccountPrivilegeOrCredentials() {
        AdminUserMapper mapper = mock(AdminUserMapper.class, CALLS_REAL_METHODS);
        doReturn(1).when(mapper).update(any(AdminUserDO.class), any(LambdaUpdateWrapper.class));
        AdminUserDO input = new AdminUserDO()
                .setId(7L)
                .setNickname("本人")
                .setUsername("replacement")
                .setDeptId(1L)
                .setPostIds(Set.of(3L))
                .setStatus(0)
                .setPassword("untrusted-hash")
                .setMustChangePassword(false);

        assertThat(mapper.updateUserProfile(input)).isEqualTo(1);

        ArgumentCaptor<AdminUserDO> write = ArgumentCaptor.forClass(AdminUserDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AdminUserDO>> condition = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(write.capture(), condition.capture());
        assertThat(write.getValue().getNickname()).isEqualTo("本人");
        assertThat(write.getValue().getUsername()).isNull();
        assertThat(write.getValue().getDeptId()).isNull();
        assertThat(write.getValue().getPostIds()).isNull();
        assertThat(write.getValue().getStatus()).isNull();
        assertThat(write.getValue().getPassword()).isNull();
        assertThat(write.getValue().getMustChangePassword()).isNull();
        assertThat(condition.getValue().getSqlSet())
                .contains("email", "mobile")
                .doesNotContain("dept_id", "password", "status");
    }

    @Test
    void updatePasswordIfUnchanged_matchesUserAndExpectedHash() {
        AdminUserMapper mapper = mock(AdminUserMapper.class, CALLS_REAL_METHODS);
        doReturn(1).when(mapper).update(any(AdminUserDO.class), any(LambdaUpdateWrapper.class));

        int updated = mapper.updatePasswordIfUnchanged(7L, "expected-hash", "upgraded-hash");

        ArgumentCaptor<AdminUserDO> updateCaptor = ArgumentCaptor.forClass(AdminUserDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AdminUserDO>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        assertThat(updated).isEqualTo(1);
        assertThat(updateCaptor.getValue().getPassword()).isEqualTo("upgraded-hash");
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("id", "password");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values()).contains(7L, "expected-hash");
    }

    @Test
    void expiredPasswordUpdateRequiresEnabledStatusAndUnconsumedFlag() {
        AdminUserMapper mapper = mock(AdminUserMapper.class, CALLS_REAL_METHODS);
        doReturn(0).when(mapper).update(any(AdminUserDO.class), any(LambdaUpdateWrapper.class));
        assertThat(mapper.completePasswordChange(7L, "original-hash", "next-hash", true))
                .isZero();
        ArgumentCaptor<AdminUserDO> update = ArgumentCaptor.forClass(AdminUserDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AdminUserDO>> condition = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(update.capture(), condition.capture());
        assertThat(condition.getValue().getSqlSegment()).contains("id", "password", "status", "must_change_password");
        assertThat(condition.getValue().getParamNameValuePairs().values()).contains(7L, "original-hash", 0, true);
        assertThat(update.getValue().getPassword()).isEqualTo("next-hash");
        assertThat(update.getValue().getMustChangePassword()).isFalse();
    }
}
