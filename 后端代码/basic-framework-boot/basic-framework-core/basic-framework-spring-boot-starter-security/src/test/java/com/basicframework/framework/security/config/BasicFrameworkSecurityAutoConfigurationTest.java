package com.basicframework.framework.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.operatelog.config.BasicFrameworkOperateLogConfiguration;
import com.basicframework.framework.security.core.context.TransmittableThreadLocalSecurityContextHolderStrategy;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.module.system.api.logger.OperateLogCommonApi;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import com.basicframework.module.system.api.session.UserSessionCommonApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

class BasicFrameworkSecurityAutoConfigurationTest {

    private final SecurityProperties properties = new SecurityProperties();
    private final BasicFrameworkSecurityAutoConfiguration configuration =
            new BasicFrameworkSecurityAutoConfiguration(properties);

    @Test
    void exposesAuthenticationAuthorizationAndCredentialCapabilities() {
        PasswordEncoder passwordEncoder = configuration.passwordEncoder();

        assertThat(configuration.authenticationEntryPoint()).isNotNull();
        assertThat(configuration.accessDeniedHandler()).isNotNull();
        assertThat(passwordEncoder.matches(
                        "correct horse battery staple", passwordEncoder.encode("correct horse battery staple")))
                .isTrue();
        assertThat(configuration.credentialCipher()).isNotNull();
        assertThat(configuration.currentUserProvider()).isNotNull();
    }

    @Test
    void exposesTokenAndPermissionCapabilities() {
        UserSessionCommonApi sessionApi = mock(UserSessionCommonApi.class);
        when(sessionApi.getSupportedUserType()).thenReturn(UserTypeEnum.ADMIN.getValue());

        assertThat(configuration.authenticationTokenFilter(mock(GlobalExceptionHandler.class), List.of(sessionApi)))
                .isNotNull();
        assertThat(configuration.securityFrameworkService(mock(PermissionCommonApi.class)))
                .isNotNull();
    }

    @Test
    void securityContextFactoryTargetsTransmittableStrategy() {
        MethodInvokingFactoryBean factory = configuration.securityContextHolderMethodInvokingFactoryBean();

        assertThat(factory.getTargetClass()).isEqualTo(SecurityContextHolder.class);
        assertThat(factory.getTargetMethod()).isEqualTo("setStrategyName");
        assertThat(factory.getArguments())
                .containsExactly(TransmittableThreadLocalSecurityContextHolderStrategy.class.getName());
    }

    @Test
    void operateLogConfigurationExposesSdkAdapter() {
        assertThat(new BasicFrameworkOperateLogConfiguration().iLogRecordServiceImpl(mock(OperateLogCommonApi.class)))
                .isNotNull();
    }
}
