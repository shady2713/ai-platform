package com.basicframework.framework.security.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserProviderImplTest {

    private final CurrentUserProviderImpl provider = new CurrentUserProviderImpl();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesIdentityAndRequestScopedContextToCurrentLoginUser() {
        LoginUser loginUser = new LoginUser().setId(7L).setUserType(2);
        SecurityFrameworkUtils.setLoginUser(loginUser, new MockHttpServletRequest());

        provider.setContext("traceId", "trace-1");

        assertThat(provider.getLoginUserId()).isEqualTo(7L);
        assertThat(provider.getLoginUserType()).isEqualTo(2);
        assertThat(provider.getContext("traceId", String.class)).isEqualTo("trace-1");
    }

    @Test
    void returnsNullAndIgnoresContextWriteWhenAnonymous() {
        provider.setContext("traceId", "trace-1");

        assertThat(provider.getLoginUserId()).isNull();
        assertThat(provider.getLoginUserType()).isNull();
        assertThat(provider.getContext("traceId", String.class)).isNull();
        assertThat(SecurityFrameworkUtils.getLoginUserNickname()).isNull();
        assertThat(SecurityFrameworkUtils.getLoginUserDeptId()).isNull();
    }
}
