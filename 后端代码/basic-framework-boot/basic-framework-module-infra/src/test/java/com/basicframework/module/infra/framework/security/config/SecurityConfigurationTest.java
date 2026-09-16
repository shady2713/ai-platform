package com.basicframework.module.infra.framework.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.config.AuthorizeRequestsCustomizer;
import com.basicframework.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizedUrl;

/**
 * {@link SecurityConfiguration} 单元测试
 *
 */
class SecurityConfigurationTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void authorizeRequestsCustomizer_permitAllsInfraReadEndpoints() {
        WebProperties webProperties = new WebProperties();
        webProperties.setAdminApi(new WebProperties.Api("/admin-api", "**.controller.admin.**"));
        webProperties.setAppApi(new WebProperties.Api("/app-api", "**.controller.app.**"));

        AuthorizeRequestsCustomizer customizer = configuration.authorizeRequestsCustomizer(webProperties);

        AuthorizationManagerRequestMatcherRegistry registry = mock(AuthorizationManagerRequestMatcherRegistry.class);
        AuthorizedUrl authorizedUrl = mock(AuthorizedUrl.class);
        when(registry.requestMatchers(any(String[].class))).thenReturn(authorizedUrl);
        when(authorizedUrl.permitAll()).thenReturn(registry);

        customizer.customize(registry);

        // Swagger 文档与 Actuator 健康检查端点无需认证
        verify(registry).requestMatchers("/v3/api-docs/**");
        verify(registry).requestMatchers("/webjars/**");
        verify(registry).requestMatchers("/swagger-ui.html");
        verify(registry).requestMatchers("/swagger-ui/**");
        verify(registry).requestMatchers("/actuator/health");
        verify(registry).requestMatchers("/actuator/health/**");
        verify(registry).requestMatchers("/actuator/info");
        // 文件读取使用 admin-api 前缀拼接
        verify(registry).requestMatchers("/admin-api/infra/file/*/get/**");
        verify(authorizedUrl, times(8)).permitAll();
        assertThat(customizer.getOrder()).isZero();
    }
}
