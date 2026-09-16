package com.basicframework.framework.security.core.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.system.api.session.UserSessionCommonApi;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import jakarta.servlet.FilterChain;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class TokenAuthenticationFilterTest {

    private final GlobalExceptionHandler globalExceptionHandler = mock(GlobalExceptionHandler.class);

    @BeforeEach
    void setUp() {
        new WebFrameworkUtils(new WebProperties());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_routesAppTokenToMemberProvider() throws Exception {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        UserSessionCommonApi memberApi = sessionApi(UserTypeEnum.MEMBER);
        when(memberApi.checkAccessToken("member-token"))
                .thenReturn(new UserSessionCheckRespDTO()
                        .setUserId(9L)
                        .setUserType(UserTypeEnum.MEMBER.getValue())
                        .setAccessExpiresTime(LocalDateTime.now().plusMinutes(10)));
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(
                new SecurityProperties(), globalExceptionHandler, List.of(adminApi, memberApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/app-api/infra/file/upload");
        request.addHeader("Authorization", "Bearer member-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityFrameworkUtils.getLoginUserId()).isEqualTo(9L);
        assertThat(WebFrameworkUtils.getLoginUserType(request)).isEqualTo(UserTypeEnum.MEMBER.getValue());
        verify(memberApi).checkAccessToken("member-token");
        verify(adminApi, never()).checkAccessToken("member-token");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_withoutTokenContinuesWithoutSessionLookup() throws Exception {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        TokenAuthenticationFilter filter =
                new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of(adminApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/system/user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(adminApi, never()).checkAccessToken(org.mockito.ArgumentMatchers.anyString());
        verify(chain).doFilter(request, response);
        assertThat(SecurityFrameworkUtils.getLoginUserId()).isNull();
    }

    @Test
    void doFilterInternal_withRejectedTokenLeavesRequestAnonymous() throws Exception {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        when(adminApi.checkAccessToken("expired-token")).thenThrow(new ServiceException(401, "expired"));
        TokenAuthenticationFilter filter =
                new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of(adminApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/system/user");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityFrameworkUtils.getLoginUserId()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_withUnknownTokenLeavesRequestAnonymous() throws Exception {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        when(adminApi.checkAccessToken("unknown-token")).thenReturn(null);
        TokenAuthenticationFilter filter =
                new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of(adminApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/system/user");
        request.addHeader("Authorization", "Bearer unknown-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityFrameworkUtils.getLoginUserId()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_withMismatchedSessionTypeReturnsForbidden() throws Exception {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        when(adminApi.checkAccessToken("wrong-type"))
                .thenReturn(new UserSessionCheckRespDTO().setUserId(9L).setUserType(UserTypeEnum.MEMBER.getValue()));
        when(globalExceptionHandler.allExceptionHandler(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ResponseEntity.status(HttpStatus.FORBIDDEN).body(CommonResult.error(403, "forbidden")));
        TokenAuthenticationFilter filter =
                new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of(adminApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/system/user");
        request.addHeader("Authorization", "Bearer wrong-type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doFilterInternal_neverConvertsJvmErrorsIntoHttpResponses() {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        AssertionError fatal = new AssertionError("fatal");
        when(adminApi.checkAccessToken("fatal-token")).thenThrow(fatal);
        TokenAuthenticationFilter filter =
                new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of(adminApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/system/user");
        request.addHeader("Authorization", "Bearer fatal-token");

        assertThatThrownBy(
                        () -> filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class)))
                .isSameAs(fatal);
        verify(globalExceptionHandler, never())
                .allExceptionHandler(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Exception.class));
    }

    @Test
    void doFilterInternal_withoutApiPrefixUsesOnlyConfiguredProvider() throws Exception {
        UserSessionCommonApi adminApi = sessionApi(UserTypeEnum.ADMIN);
        when(adminApi.checkAccessToken("internal-token"))
                .thenReturn(new UserSessionCheckRespDTO().setUserId(11L).setUserType(UserTypeEnum.ADMIN.getValue()));
        TokenAuthenticationFilter filter =
                new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of(adminApi));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/internal/health");
        request.addHeader("Authorization", "Bearer internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityFrameworkUtils.getLoginUserId()).isEqualTo(11L);
        verify(chain).doFilter(request, response);
    }

    @Test
    void constructor_rejectsDuplicateUserTypeProviders() {
        UserSessionCommonApi firstApi = sessionApi(UserTypeEnum.ADMIN);
        UserSessionCommonApi secondApi = sessionApi(UserTypeEnum.ADMIN);

        assertThatThrownBy(() -> new TokenAuthenticationFilter(
                        new SecurityProperties(), globalExceptionHandler, List.of(firstApi, secondApi)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("用户类型 2 存在重复会话校验器");
    }

    @Test
    void constructor_rejectsMissingOrUnknownProvidersAtStartup() {
        assertThatThrownBy(() -> new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("至少需要一个会话校验器");

        assertThatThrownBy(() ->
                        new TokenAuthenticationFilter(new SecurityProperties(), globalExceptionHandler, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("至少需要一个会话校验器");

        UserSessionCommonApi unknownApi = mock(UserSessionCommonApi.class);
        when(unknownApi.getSupportedUserType()).thenReturn(99);
        assertThatThrownBy(() -> new TokenAuthenticationFilter(
                        new SecurityProperties(), globalExceptionHandler, List.of(unknownApi)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("会话校验器声明了未知用户类型 99");

        UserSessionCommonApi missingTypeApi = mock(UserSessionCommonApi.class);
        when(missingTypeApi.getSupportedUserType()).thenReturn(null);
        assertThatThrownBy(() -> new TokenAuthenticationFilter(
                        new SecurityProperties(), globalExceptionHandler, List.of(missingTypeApi)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("会话校验器必须声明用户类型");
    }

    private static UserSessionCommonApi sessionApi(UserTypeEnum userType) {
        UserSessionCommonApi api = mock(UserSessionCommonApi.class);
        when(api.getSupportedUserType()).thenReturn(userType.getValue());
        return api;
    }
}
