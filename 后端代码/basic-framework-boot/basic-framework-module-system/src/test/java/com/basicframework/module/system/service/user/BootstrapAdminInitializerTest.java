package com.basicframework.module.system.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.basicframework.module.system.config.BootstrapAdminProperties;
import com.basicframework.module.system.config.PasswordPolicyProperties;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.service.session.UserSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BootstrapAdminInitializerTest {
    private final AdminUserMapper mapper = mock(AdminUserMapper.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final BootstrapAdminProperties properties = new BootstrapAdminProperties();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(
            properties, mapper, new PasswordPolicy(new PasswordPolicyProperties()), encoder, sessions);

    @Test
    void missingOrAlreadyInitializedUserIsNeverOverwritten() {
        initializer.run(null);
        when(mapper.selectById(1L)).thenReturn(new AdminUserDO().setId(1L).setPassword("already-initialized"));
        properties.setPassword("violet river orbits quietly!");
        initializer.run(null);
        verify(mapper, never()).update(any(AdminUserDO.class), any(Wrapper.class));
        verifyNoInteractions(sessions);
    }

    @Test
    void pendingSeedStaysDisabledWithoutDeploymentPassword() {
        when(mapper.selectById(1L)).thenReturn(pendingUser());
        initializer.run(null);
        verify(mapper, never()).update(any(AdminUserDO.class), any(Wrapper.class));
        verify(sessions).removeSessionsByUser(1L, 2);
    }

    @Test
    void explicitPasswordIsValidatedHashedAndRequiresFirstLoginChange() {
        when(mapper.selectById(1L)).thenReturn(pendingUser());
        properties.setPassword("violet river orbits quietly!");
        initializer.run(null);
        ArgumentCaptor<AdminUserDO> update = ArgumentCaptor.forClass(AdminUserDO.class);
        verify(mapper).update(update.capture(), any(Wrapper.class));
        assertThat(encoder.matches(properties.getPassword(), update.getValue().getPassword()))
                .isTrue();
        assertThat(update.getValue().getStatus()).isZero();
        assertThat(update.getValue().getMustChangePassword()).isTrue();
        assertThat(properties.toString()).doesNotContain(properties.getPassword());
    }

    @Test
    void weakDeploymentPasswordCannotActivateSeed() {
        when(mapper.selectById(1L)).thenReturn(pendingUser());
        properties.setPassword("short");
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(RuntimeException.class);
        verify(mapper, never()).update(any(AdminUserDO.class), any(Wrapper.class));
    }

    private static AdminUserDO pendingUser() {
        return new AdminUserDO().setId(1L).setUsername("admin").setStatus(1).setPassword("!bootstrap-required");
    }
}
