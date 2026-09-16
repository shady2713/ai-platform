package com.basicframework.framework.security.core.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthenticatedOnlyMethodSecurityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectInvocationWithoutAuthentication() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            ProtectedService service = context.getBean(ProtectedService.class);

            assertThatThrownBy(service::read).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }
    }

    @Test
    void shouldRejectAnonymousInvocation() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "test", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            assertThatThrownBy(context.getBean(ProtectedService.class)::read).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void shouldAllowAuthenticatedInvocation() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "user", "n/a", AuthorityUtils.NO_AUTHORITIES));

        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            assertThat(context.getBean(ProtectedService.class).read()).isEqualTo("allowed");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class TestConfiguration {

        @Bean
        ProtectedService protectedService() {
            return new ProtectedService();
        }
    }

    static class ProtectedService {

        @AuthenticatedOnly
        public String read() {
            return "allowed";
        }
    }
}
