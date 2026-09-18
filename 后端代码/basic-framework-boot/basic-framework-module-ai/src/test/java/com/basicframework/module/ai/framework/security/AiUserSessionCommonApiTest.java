package com.basicframework.module.ai.framework.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.auth.dto.AiTicketContextDTO;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A05 MEMBER 会话 Provider：票据换会话、只声明 MEMBER、外部主体身份走附加信息、撤销/过期即认证失败。
 */
class AiUserSessionCommonApiTest {

    private final AiTicketService ticketService = mock(AiTicketService.class);

    private final AiUserSessionCommonApi provider = new AiUserSessionCommonApi(ticketService);

    private static AiTicketContextDTO ticketContext() {
        return new AiTicketContextDTO()
                .setTicketId(21L)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setOrganizationIds(Set.of(10L))
                .setResourceKeys(Set.of("report-1"))
                .setScopeFingerprint("f".repeat(64))
                .setAuthzRevision(2L)
                .setExpiresTime(LocalDateTime.now().plusMinutes(5));
    }

    @Test
    void declaresMemberUserTypeOnly() {
        assertThat(provider.getSupportedUserType()).isEqualTo(UserTypeEnum.MEMBER.getValue());
        assertThat(provider.getSupportedUserType())
                .as("AI 会话不得占用 ADMIN 用户类型（否则与 module-system Provider 冲突）")
                .isNotEqualTo(UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void buildsSessionFromVerifiedTicket() {
        when(ticketService.verify("ticket-token")).thenReturn(ticketContext());

        UserSessionCheckRespDTO session = provider.checkAccessToken("ticket-token");

        assertThat(session.getUserType()).isEqualTo(UserTypeEnum.MEMBER.getValue());
        assertThat(session.getUserId()).isEqualTo(21L);
        assertThat(session.getAccessExpiresTime()).isAfter(LocalDateTime.now());
        assertThat(session.getUserInfo())
                .containsEntry(AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID, "5")
                .containsEntry(AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE, "USER")
                .containsEntry(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID, "alice")
                .containsEntry(AiUserSessionCommonApi.INFO_KEY_SCOPE_FINGERPRINT, "f".repeat(64));
    }

    @Test
    void invalidOrRevokedTicketFailsAuthentication() {
        when(ticketService.verify("stale-token"))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_TICKET_INVALID));

        // 框架按 ServiceException 处理为"未认证"，不会降级为其他用户类型
        assertThatThrownBy(() -> provider.checkAccessToken("stale-token"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_TICKET_INVALID.getCode());
    }
}
