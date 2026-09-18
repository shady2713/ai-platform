package com.basicframework.module.ai.framework.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A05 scope 表达式：判定委托 A03、失败一律拒绝（无上下文/未知资源类型/判定异常都不放行）。
 */
class AiScopeSecurityServiceTest {

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiScopeSecurityService scopeService = new AiScopeSecurityService(authorizationService);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loginAsAiSubject(Map<String, String> info) {
        LoginUser loginUser = new LoginUser().setId(21L).setInfo(info);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static Map<String, String> subjectInfo() {
        return Map.of(
                AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID, "5",
                AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE, "USER",
                AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID, "alice");
    }

    @Test
    void delegatesDecisionToAuthorizationService() {
        loginAsAiSubject(subjectInfo());
        when(authorizationService.authorize(
                        eq(5L),
                        eq("USER"),
                        eq("alice"),
                        eq(AiResourceType.REPORT),
                        eq("report-1"),
                        eq(AiAction.READ),
                        any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));

        assertThat(scopeService.hasScope("REPORT", "report-1", "READ")).isTrue();
        verify(authorizationService)
                .authorize(
                        5L,
                        "USER",
                        "alice",
                        AiResourceType.REPORT,
                        "report-1",
                        AiAction.READ,
                        java.util.List.of("report-1"));

        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("GRANT_NOT_FOUND"));
        assertThat(scopeService.hasScope("REPORT", "report-1", "READ")).isFalse();
    }

    @Test
    void failsClosedWithoutLoginUserOrAiContext() {
        // 未登录
        assertThat(scopeService.hasScope("REPORT", "report-1", "READ")).isFalse();

        // 登录但没有 AI 会话信息（例如管理端用户）
        loginAsAiSubject(Map.of("nickname", "admin"));
        assertThat(scopeService.hasScope("REPORT", "report-1", "READ")).isFalse();

        // 应用编号非法
        loginAsAiSubject(Map.of(
                AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID, "not-a-number",
                AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE, "USER"));
        assertThat(scopeService.hasScope("REPORT", "report-1", "READ")).isFalse();
    }

    @Test
    void rejectsUnknownResourceTypeActionOrBlankKey() {
        loginAsAiSubject(subjectInfo());

        assertThat(scopeService.hasScope("UNKNOWN", "report-1", "READ")).isFalse();
        assertThat(scopeService.hasScope("REPORT", "report-1", "DELETE")).isFalse();
        assertThat(scopeService.hasScope("REPORT", "  ", "READ")).isFalse();
        assertThat(scopeService.hasScope("REPORT", null, "READ")).isFalse();
        assertThat(scopeService.hasAnyScope("REPORT", "report-1")).isFalse();
    }

    @Test
    void authorizationFailureIsDeniedNotPropagated() {
        loginAsAiSubject(subjectInfo());
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new com.basicframework.framework.common.exception.ServiceException(
                        com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND));

        assertThat(scopeService.hasScope("REPORT", "report-1", "READ")).isFalse();
    }

    @Test
    void hasAnyScopeAllowsWhenOneActionMatches() {
        loginAsAiSubject(subjectInfo());
        when(authorizationService.authorize(any(), any(), any(), any(), any(), eq(AiAction.READ), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
        when(authorizationService.authorize(any(), any(), any(), any(), any(), eq(AiAction.EXPORT), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));

        assertThat(scopeService.hasAnyScope("REPORT", "report-1", "EXPORT", "READ"))
                .isTrue();
        assertThat(AiScopeSecurityService.supportedActions()).containsExactly("READ", "EXECUTE", "EXPORT");
    }
}
