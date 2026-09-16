package com.basicframework.framework.security.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class SecurityHandlerTest {

    @BeforeEach
    void setUp() {
        new WebFrameworkUtils(new WebProperties());
    }

    @Test
    void authenticationEntryPoint_writes401WithAuthenticateHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/admin-api/system/user/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthenticationEntryPointImpl()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(GlobalExceptionHandler.WWW_AUTHENTICATE_VALUE);
        assertThat(response.getContentAsString())
                .contains(String.valueOf(GlobalErrorCodeConstants.UNAUTHORIZED.getCode()));
    }

    @Test
    void accessDeniedHandler_writes403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/admin-api/system/user/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AccessDeniedHandlerImpl().handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString())
                .contains(String.valueOf(GlobalErrorCodeConstants.FORBIDDEN.getCode()));
    }
}
