package com.basicframework.module.ai.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.dataobject.token.AiAccessTicketDO;
import com.basicframework.module.ai.dal.mysql.token.AiAccessTicketMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.auth.dto.AiTicketContextDTO;
import com.basicframework.module.ai.service.auth.dto.AiTicketIssueDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A04 换票与票据：必须出示客户端凭据与可信主体断言、只存摘要、范围裁剪、短期有效、
 * 过期/撤销/应用停用/主体撤销全部收敛为同一 401 语义。
 */
class AiTicketServiceImplTest {

    private AiApplicationService applicationService;

    private AiSubjectService subjectService;

    private AiAccessTicketMapper ticketMapper;

    private AiTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        applicationService = mock(AiApplicationService.class);
        subjectService = mock(AiSubjectService.class);
        ticketMapper = mock(AiAccessTicketMapper.class);
        service = new AiTicketServiceImpl(applicationService, subjectService, ticketMapper, Duration.ofMinutes(10));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, int code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }

    private void givenValidApplicationAndScope(Set<String> subjectResources) {
        when(applicationService.authenticate("crm-portal", "aiapp_secret"))
                .thenReturn(
                        new AiApplicationDO().setId(5L).setAppCode("crm-portal").setEnabled(true));
        when(subjectService.resolveScope(eq(5L), eq(AiSubjectType.USER), eq("alice"), any()))
                .thenReturn(new AiSubjectScopeDTO()
                        .setApplicationId(5L)
                        .setSubjectType("USER")
                        .setExternalUserId("alice")
                        .setDenied(false)
                        .setOrganizationIds(Set.of(10L))
                        .setResourceKeys(subjectResources)
                        .setScopeSource("crm-auth")
                        .setScopeVersion(3L));
        when(ticketMapper.insert(any(AiAccessTicketDO.class))).thenAnswer(invocation -> {
            ((AiAccessTicketDO) invocation.getArgument(0)).setId(21L);
            return 1;
        });
    }

    @Test
    void issuesShortLivedTicketStoringOnlyDigestAndTrimmedScope() {
        givenValidApplicationAndScope(Set.of("report-1", "report-2"));

        AiTicketIssueDTO issue = service.issue(
                "crm-portal", "aiapp_secret", AiSubjectType.USER, "alice", List.of("report-1", "report-out-of-scope"));

        assertThat(issue.getToken()).isNotBlank();
        assertThat(issue.getExpiresTime()).isAfter(LocalDateTime.now());
        assertThat(issue.getResourceKeys()).as("越界对象被裁掉").containsExactly("report-1");
        assertThat(issue.getOrganizationIds()).containsExactly(10L);
        assertThat(issue.getScopeFingerprint()).hasSize(64);

        ArgumentCaptor<AiAccessTicketDO> captor = ArgumentCaptor.forClass(AiAccessTicketDO.class);
        verify(ticketMapper).insert(captor.capture());
        AiAccessTicketDO stored = captor.getValue();
        assertThat(stored.getTokenDigest()).hasSize(64).isNotEqualTo(issue.getToken());
        assertThat(stored.getTokenDigest()).doesNotContain(issue.getToken());
        assertThat(stored.getStatus()).isEqualTo(AiAccessTicketDO.STATUS_ACTIVE);
        assertThat(stored.getScopeSnapshot()).contains("report-1").doesNotContain("report-out-of-scope");
    }

    @Test
    void rejectsWhenClientCredentialMissingOrScopeDeniedOrAllResourcesTrimmed() {
        // 凭据无效：换票链路直接失败（浏览器没有 appSecret 就换不到票据）
        when(applicationService.authenticate("crm-portal", "bad"))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID));
        assertCode(
                () -> service.issue("crm-portal", "bad", AiSubjectType.USER, "alice", List.of()),
                AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
        verify(ticketMapper, never()).insert(any(AiAccessTicketDO.class));

        // 范围 DENY：拒绝签发
        givenValidApplicationAndScope(Set.of("report-1"));
        when(subjectService.resolveScope(eq(5L), eq(AiSubjectType.USER), eq("alice"), any()))
                .thenReturn(new AiSubjectScopeDTO().setDenied(true).setDenyReason("EMPTY_SCOPE"));
        assertCode(
                () -> service.issue("crm-portal", "aiapp_secret", AiSubjectType.USER, "alice", List.of()),
                AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());

        // 请求的对象全部越界：裁剪后为空且无组织范围 → 拒绝签发，不产生空票据
        when(subjectService.resolveScope(eq(5L), eq(AiSubjectType.USER), eq("alice"), any()))
                .thenReturn(new AiSubjectScopeDTO()
                        .setApplicationId(5L)
                        .setExternalUserId("alice")
                        .setDenied(false)
                        .setOrganizationIds(Set.of())
                        .setResourceKeys(Set.of("report-1"))
                        .setScopeVersion(1L));
        assertCode(
                () -> service.issue("crm-portal", "aiapp_secret", AiSubjectType.USER, "alice", List.of("report-other")),
                AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
    }

    @Test
    void verifyRejectsGarbageExpiredRevokedAndDisabledSubjects() {
        String token = "some-token";
        // 摘要查不到
        when(ticketMapper.selectByDigest(any())).thenReturn(null);
        assertCode(() -> service.verify(token), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());
        assertCode(() -> service.verify(null), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());
        assertCode(() -> service.verify(" "), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());

        // 已撤销 / 已过期
        when(ticketMapper.selectByDigest(any()))
                .thenReturn(ticket(
                        AiAccessTicketDO.STATUS_REVOKED, LocalDateTime.now().plusMinutes(5)))
                .thenReturn(ticket(
                        AiAccessTicketDO.STATUS_ACTIVE, LocalDateTime.now().minusSeconds(1)));
        assertCode(() -> service.verify(token), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());
        assertCode(() -> service.verify(token), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());

        // 未过期但应用被停用 / 主体被撤销：校验时重新读取状态（无缓存）
        when(ticketMapper.selectByDigest(any()))
                .thenReturn(ticket(
                        AiAccessTicketDO.STATUS_ACTIVE, LocalDateTime.now().plusMinutes(5)));
        when(applicationService.getApplication(5L))
                .thenReturn(new AiApplicationDO().setId(5L).setEnabled(false));
        assertCode(() -> service.verify(token), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());

        when(applicationService.getApplication(5L))
                .thenReturn(new AiApplicationDO().setId(5L).setEnabled(true));
        when(subjectService.findActiveSubject(5L, AiSubjectType.USER, "alice")).thenReturn(Optional.empty());
        assertCode(() -> service.verify(token), AiErrorCodeConstants.AI_TICKET_INVALID.getCode());
    }

    @Test
    void verifyReturnsServerSideContextWithTrimmedScope() {
        when(ticketMapper.selectByDigest(any()))
                .thenReturn(ticket(
                        AiAccessTicketDO.STATUS_ACTIVE, LocalDateTime.now().plusMinutes(5)));
        when(applicationService.getApplication(5L))
                .thenReturn(new AiApplicationDO().setId(5L).setEnabled(true));
        when(subjectService.findActiveSubject(5L, AiSubjectType.USER, "alice"))
                .thenReturn(Optional.of(new AiSubjectDO().setId(7L)));

        AiTicketContextDTO context = service.verify("token");

        assertThat(context.getTicketId()).isEqualTo(21L);
        assertThat(context.getExternalUserId()).isEqualTo("alice");
        assertThat(context.getOrganizationIds()).containsExactly(10L);
        assertThat(context.getResourceKeys()).containsExactly("report-1");
        assertThat(context.getScopeFingerprint()).hasSize(64);
    }

    @Test
    void revokeTicketsMarksActiveTicketsAsRevoked() {
        when(ticketMapper.selectActive(5L, "USER", "alice"))
                .thenReturn(List.of(ticket(
                        AiAccessTicketDO.STATUS_ACTIVE, LocalDateTime.now().plusMinutes(5))));
        when(ticketMapper.updateWithVersion(any(AiAccessTicketDO.class), any())).thenReturn(1);

        service.revokeTickets(5L, AiSubjectType.USER, "alice");

        ArgumentCaptor<AiAccessTicketDO> captor = ArgumentCaptor.forClass(AiAccessTicketDO.class);
        verify(ticketMapper).updateWithVersion(captor.capture(), any());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiAccessTicketDO.STATUS_REVOKED);
    }

    private static AiAccessTicketDO ticket(String status, LocalDateTime expiresTime) {
        return new AiAccessTicketDO()
                .setId(21L)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setTokenDigest("digest")
                .setScopeSnapshot("{\"organizationIds\":[10],\"resourceKeys\":[\"report-1\"],\"scopeVersion\":3}")
                .setScopeFingerprint("f".repeat(64))
                .setAuthzRevision(1L)
                .setExpiresTime(expiresTime)
                .setStatus(status)
                .setVersion(0);
    }
}
