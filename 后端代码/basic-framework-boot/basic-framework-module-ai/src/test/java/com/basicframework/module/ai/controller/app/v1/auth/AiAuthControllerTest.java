package com.basicframework.module.ai.controller.app.v1.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.controller.app.v1.auth.vo.AiTicketReqVO;
import com.basicframework.module.ai.controller.app.v1.auth.vo.AiTicketRespVO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.auth.AiTicketAttemptThrottle;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.auth.dto.AiTicketIssueDTO;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** A04 换票接口契约：凭据 + 断言齐备才签发、失败计数触发 429、响应只含票据与裁剪范围。 */
class AiAuthControllerTest {

    private final AiTicketService ticketService = mock(AiTicketService.class);

    private final AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(2, Duration.ofMinutes(1));

    private final AiAuthController controller = new AiAuthController(ticketService, throttle);

    private static AiTicketReqVO request() {
        return new AiTicketReqVO()
                .setAppCode("crm-portal")
                .setAppSecret("aiapp_secret")
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceKeys(List.of("report-1"));
    }

    @Test
    void issuesTicketWithTrimmedScope() {
        when(ticketService.issue(any(), any(), any(), any(), any()))
                .thenReturn(new AiTicketIssueDTO()
                        .setApplicationId(5L)
                        .setSubjectType("USER")
                        .setExternalUserId("alice")
                        .setToken("ticket-token")
                        .setExpiresTime(LocalDateTime.now().plusMinutes(10))
                        .setOrganizationIds(Set.of(10L))
                        .setResourceKeys(Set.of("report-1"))
                        .setScopeFingerprint("f".repeat(64)));

        AiTicketRespVO respVO = controller.issueTicket(request()).getData();

        assertThat(respVO.getToken()).isEqualTo("ticket-token");
        assertThat(respVO.getResourceKeys()).containsExactly("report-1");
        assertThat(respVO.getOrganizationIds()).containsExactly(10L);
        verify(ticketService).issue("crm-portal", "aiapp_secret", AiSubjectType.USER, "alice", List.of("report-1"));
    }

    @Test
    void repeatedFailuresAreThrottledWithQuotaSemantics() {
        when(ticketService.issue(any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID));

        assertThatThrownBy(() -> controller.issueTicket(request())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> controller.issueTicket(request())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> controller.issueTicket(request()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_QUOTA_EXCEEDED.getCode());
    }

    @Test
    void responseModelCarriesNoSecretFields() {
        Set<String> fieldNames = Arrays.stream(AiTicketRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fieldNames)
                .containsExactlyInAnyOrder(
                        "token", "expiresTime", "organizationIds", "resourceKeys", "scopeFingerprint");
        assertThat(fieldNames).doesNotContain("appSecret", "secret", "tokenDigest");
    }

    @Test
    void successfulExchangeResetsFailureCounter() {
        when(ticketService.issue(any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID))
                .thenReturn(new AiTicketIssueDTO().setToken("t").setExpiresTime(LocalDateTime.now()));

        assertThatThrownBy(() -> controller.issueTicket(request())).isInstanceOf(ServiceException.class);
        assertThat(controller.issueTicket(request()).getData().getToken()).isEqualTo("t");
        assertThat(throttle.trackedKeys()).isZero();
        verify(ticketService, never()).verify(any());
    }
}
