package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiExecutionContextFactory;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.AiRevocationService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A06 撤销链路（AT-010，真实 MySQL）：撤销应用/主体后，新请求、换票、判定、执行上下文重建全部被拒绝。
 */
@Import(AiRevocationIT.ResolverConfiguration.class)
class AiRevocationIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-revocation-app";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiExecutionContextFactory executionContextFactory;

    @Autowired
    private AiRevocationService revocationService;

    @Autowired
    private AiTicketService ticketService;

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver revocationScopeResolver() {
            return request -> Optional.of(
                    new SubjectScope(Set.of(10L), Set.of("report-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
    }

    private AiApplicationCredentialIssueDTO createEnabledApplication() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 撤销应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        return issue;
    }

    @Test
    void revokingApplicationInvalidatesTicketsGrantsAndNewRequests() {
        AiApplicationCredentialIssueDTO application = createEnabledApplication();
        Long applicationId = application.getApplication().getId();
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        Long grantId = grantService.createGrant(applicationId, "USER", "alice", "REPORT", "report-1", Set.of("READ"));
        String ticket = ticketService
                .issue(APP_CODE, application.getSecret(), AiSubjectType.USER, "alice", List.of("report-1"))
                .getToken();

        // 撤销前：票据、判定、执行上下文都可用
        assertThat(ticketService.verify(ticket)).isNotNull();
        assertThat(authorizationService
                        .authorize(
                                applicationId,
                                "USER",
                                "alice",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.READ,
                                List.of("report-1"))
                        .isAllowed())
                .isTrue();
        assertThat(executionContextFactory
                        .rebuild(applicationId, AiSubjectType.USER, "alice", List.of("report-1"))
                        .allowsResource("report-1"))
                .isTrue();

        // 撤销应用
        revocationService.revokeApplication(applicationId, 1);

        // 旧票据立即不可认证
        assertThatThrownBy(() -> ticketService.verify(ticket)).isInstanceOf(ServiceException.class);
        // 新换票被拒绝（应用已停用）
        assertThatThrownBy(() -> ticketService.issue(
                        APP_CODE, application.getSecret(), AiSubjectType.USER, "alice", List.of("report-1")))
                .isInstanceOf(ServiceException.class);
        // 判定拒绝，且旧指纹不可复用
        assertThat(authorizationService
                        .authorize(
                                applicationId,
                                "USER",
                                "alice",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.READ,
                                List.of())
                        .isAllowed())
                .isFalse();
        // 执行上下文重建失败 → 后续受限步骤必须停止
        assertThatThrownBy(() -> executionContextFactory.rebuild(
                        applicationId, AiSubjectType.USER, "alice", List.of("report-1")))
                .isInstanceOf(ServiceException.class);
        // 授权记录已被撤销（状态可查）
        assertThat(grantService.getGrant(grantId).getStatus())
                .isEqualTo(com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO.STATUS_REVOKED);
    }

    @Test
    void revokingSubjectStopsOnlyThatSubject() {
        AiApplicationCredentialIssueDTO application = createEnabledApplication();
        Long applicationId = application.getApplication().getId();
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "bob", "Bob", "crm-auth", 1L);
        grantService.createGrant(applicationId, "USER", "alice", "REPORT", "report-1", Set.of("READ"));
        grantService.createGrant(applicationId, "USER", "bob", "REPORT", "report-1", Set.of("READ"));
        String aliceTicket = ticketService
                .issue(APP_CODE, application.getSecret(), AiSubjectType.USER, "alice", List.of("report-1"))
                .getToken();
        String bobTicket = ticketService
                .issue(APP_CODE, application.getSecret(), AiSubjectType.USER, "bob", List.of("report-1"))
                .getToken();

        revocationService.revokeSubject(applicationId, AiSubjectType.USER, "alice", 0);

        // alice：票据失效、判定拒绝、上下文重建失败
        assertThatThrownBy(() -> ticketService.verify(aliceTicket)).isInstanceOf(ServiceException.class);
        assertThat(authorizationService
                        .authorize(
                                applicationId,
                                "USER",
                                "alice",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.READ,
                                List.of())
                        .isAllowed())
                .isFalse();
        assertThatThrownBy(() -> executionContextFactory.rebuild(
                        applicationId, AiSubjectType.USER, "alice", List.of("report-1")))
                .isInstanceOf(ServiceException.class);

        // bob：不受影响
        assertThat(ticketService.verify(bobTicket)).isNotNull();
        assertThat(authorizationService
                        .authorize(
                                applicationId,
                                "USER",
                                "bob",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.READ,
                                List.of())
                        .isAllowed())
                .isTrue();
    }

    @Test
    void cleanupJobRemovesOnlyInvalidTicketsAndIsIdempotent() {
        AiApplicationCredentialIssueDTO application = createEnabledApplication();
        Long applicationId = application.getApplication().getId();
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        String ticket = ticketService
                .issue(APP_CODE, application.getSecret(), AiSubjectType.USER, "alice", List.of("report-1"))
                .getToken();

        // 未失效的票据不会被清理
        assertThat(ticketService.cleanInvalidTickets(100, 5, java.time.Duration.ZERO))
                .isZero();
        assertThat(ticketService.verify(ticket)).isNotNull();

        // 撤销后（保留期为 0）清理一次即删除；再次执行为幂等空操作
        ticketService.revokeTickets(applicationId, AiSubjectType.USER, "alice");
        assertThat(ticketService.cleanInvalidTickets(100, 5, java.time.Duration.ZERO))
                .isEqualTo(1);
        assertThat(ticketService.cleanInvalidTickets(100, 5, java.time.Duration.ZERO))
                .isZero();
        assertThatThrownBy(() -> ticketService.verify(ticket))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_TICKET_INVALID.getCode());
    }
}
