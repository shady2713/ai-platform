package com.basicframework.server.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/** 独立抓取链仅允许读取 Prometheus；抓取令牌不能进入管理端认证链，也不创建浏览器会话。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrometheusProperties.class)
public class PrometheusSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain prometheusSecurityFilterChain(HttpSecurity http, PrometheusProperties properties)
            throws Exception {
        byte[] expected = ("Bearer " + properties.getToken()).getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher(EndpointRequest.to("prometheus"))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        config -> config.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(config -> config.requestMatchers(HttpMethod.GET, "/**")
                        .access((authentication, context) -> {
                            String supplied = context.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                            return new AuthorizationDecision(properties.isEnabled()
                                    && supplied != null
                                    && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8)));
                        })
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
