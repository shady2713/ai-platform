package com.basicframework.framework.security.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.filter.TokenAuthenticationFilter;
import com.basicframework.framework.security.core.handler.AccessDeniedHandlerImpl;
import com.basicframework.framework.security.core.handler.AuthenticationEntryPointImpl;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.module.system.api.session.UserSessionCommonApi;
import jakarta.annotation.security.PermitAll;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityFilterChainWebTest.ProbeController.class)
@Import({BasicFrameworkWebSecurityConfigurerAdapter.class, SecurityFilterChainWebTest.SecurityTestConfiguration.class})
@ContextConfiguration(classes = SecurityFilterChainWebTest.ProbeController.class)
class SecurityFilterChainWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void permitAllAnnotation_allowsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/open"))
                .andExpect(status().isOk())
                .andExpect(content().string("open"));
    }

    @Test
    void ordinaryEndpoint_requiresAuthenticationByDefault() throws Exception {
        mockMvc.perform(get("/secured")).andExpect(status().isUnauthorized());
    }

    @Test
    void compiledCustomizer_canAddExplicitPublicRule() throws Exception {
        mockMvc.perform(get("/custom-open"))
                .andExpect(status().isOk())
                .andExpect(content().string("custom-open"));
    }

    @RestController
    static class ProbeController {

        @PermitAll
        @GetMapping("/open")
        String open() {
            return "open";
        }

        @GetMapping("/secured")
        String secured() {
            return "secured";
        }

        @GetMapping("/custom-open")
        String customOpen() {
            return "custom-open";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {

        @Bean
        AuthenticationEntryPoint authenticationEntryPoint() {
            return new AuthenticationEntryPointImpl();
        }

        @Bean
        AccessDeniedHandler accessDeniedHandler() {
            return new AccessDeniedHandlerImpl();
        }

        @Bean
        TokenAuthenticationFilter authenticationTokenFilter() {
            UserSessionCommonApi sessionApi = mock(UserSessionCommonApi.class);
            when(sessionApi.getSupportedUserType()).thenReturn(UserTypeEnum.ADMIN.getValue());
            return new TokenAuthenticationFilter(
                    new SecurityProperties(), mock(GlobalExceptionHandler.class), List.of(sessionApi));
        }

        @Bean
        AuthorizeRequestsCustomizer explicitPublicRule() {
            return new AuthorizeRequestsCustomizer(new WebProperties()) {
                @Override
                public void customize(
                        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                                registry) {
                    registry.requestMatchers("/custom-open").permitAll();
                }
            };
        }
    }
}
