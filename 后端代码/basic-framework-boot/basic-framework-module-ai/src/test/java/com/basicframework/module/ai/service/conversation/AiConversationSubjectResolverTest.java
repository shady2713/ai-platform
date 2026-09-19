package com.basicframework.module.ai.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** O01 会话主体解析：身份只来自 MEMBER 会话的服务端信息，任何不可信输入一律按拒绝处理。 */
class AiConversationSubjectResolverTest {

    private final AiConversationSubjectResolver resolver = new AiConversationSubjectResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void login(UserTypeEnum userType, Map<String, String> info) {
        LoginUser loginUser =
                new LoginUser().setId(31L).setUserType(userType.getValue()).setInfo(info);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static Map<String, String> sessionInfo(String applicationId, String subjectType, String externalUserId) {
        Map<String, String> info = new HashMap<>();
        info.put(AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID, applicationId);
        info.put(AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE, subjectType);
        info.put(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID, externalUserId);
        return info;
    }

    @Test
    void resolvesMemberSubjectFromServerSideSession() {
        login(UserTypeEnum.MEMBER, sessionInfo("5", "USER", "u-1001"));

        assertThat(resolver.resolveCurrent()).hasValueSatisfying(subject -> {
            assertThat(subject.applicationId()).isEqualTo(5L);
            assertThat(subject.subjectType()).isEqualTo(AiSubjectType.USER);
            assertThat(subject.externalUserId()).isEqualTo("u-1001");
        });
    }

    @Test
    void rejectsMissingOrUntrustedSessions() {
        assertThat(resolver.resolveCurrent()).as("未登录").isEmpty();

        login(UserTypeEnum.ADMIN, sessionInfo("5", "USER", "u-1001"));
        assertThat(resolver.resolveCurrent()).as("管理端会话与 MEMBER 会话互斥").isEmpty();

        login(UserTypeEnum.MEMBER, new HashMap<>());
        assertThat(resolver.resolveCurrent()).as("缺少会话信息").isEmpty();

        login(UserTypeEnum.MEMBER, sessionInfo("not-a-number", "USER", "u-1001"));
        assertThat(resolver.resolveCurrent()).as("应用编号非法").isEmpty();

        login(UserTypeEnum.MEMBER, sessionInfo("5", "ROOT", "u-1001"));
        assertThat(resolver.resolveCurrent()).as("主体类型非法").isEmpty();

        login(UserTypeEnum.MEMBER, sessionInfo("5", "APP", null));
        assertThat(resolver.resolveCurrent()).as("APP 主体的外部用户标识归一为空串").hasValueSatisfying(subject -> assertThat(
                        subject.externalUserId())
                .isEmpty());
    }

    @Test
    void loginUserWithoutInfoIsRejected() {
        LoginUser loginUser = new LoginUser().setId(31L).setUserType(UserTypeEnum.MEMBER.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));

        assertThat(SecurityFrameworkUtils.getLoginUser()).isNotNull();
        assertThat(resolver.resolveCurrent()).isEmpty();
    }
}
