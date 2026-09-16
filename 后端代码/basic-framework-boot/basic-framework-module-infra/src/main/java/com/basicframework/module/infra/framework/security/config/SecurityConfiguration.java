package com.basicframework.module.infra.framework.security.config;

import com.basicframework.framework.security.config.AuthorizeRequestsCustomizer;
import com.basicframework.framework.web.config.WebProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Infra 模块的 Security 配置
 */
@Configuration(proxyBeanMethods = false, value = "infraSecurityConfiguration")
public class SecurityConfiguration {

    @Bean("infraAuthorizeRequestsCustomizer")
    public AuthorizeRequestsCustomizer authorizeRequestsCustomizer(WebProperties webProperties) {
        return new AuthorizeRequestsCustomizer(webProperties) {

            @Override
            public void customize(
                    AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
                // Swagger 接口文档
                registry.requestMatchers("/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/webjars/**")
                        .permitAll()
                        .requestMatchers("/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/swagger-ui/**")
                        .permitAll();
                // Spring Boot Actuator - 仅允许 health 和 info 端点无认证访问
                registry.requestMatchers("/actuator/health")
                        .permitAll()
                        .requestMatchers("/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/actuator/info")
                        .permitAll();
                // Druid 监控 - 由 Druid 自身的 login-username/login-password 保护，无需 Spring Security 额外放行
                // 注意：Druid StatViewServlet 自带认证，此处不再 permitAll
                // 文件读取
                registry.requestMatchers(buildAdminApi("/infra/file/*/get/**")).permitAll();
            }
        };
    }
}
