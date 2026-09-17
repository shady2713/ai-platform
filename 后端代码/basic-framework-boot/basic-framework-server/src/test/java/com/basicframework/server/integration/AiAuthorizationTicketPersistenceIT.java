package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
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
import com.basicframework.module.ai.service.auth.dto.AiTicketContextDTO;
import com.basicframework.module.ai.service.auth.dto.AiTicketIssueDTO;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
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
 * A03/A04 端到端（真实 MySQL）：授权目录读写、统一判定、换票与校验、撤销立即生效。
 *
 * <p>链路：应用与凭据（A01）→ 主体范围（A02）→ 授权目录与判定（A03）→ 票据（A04）。
 */
@Import(AiAuthorizationTicketPersistenceIT.ResolverConfiguration.class)
class AiAuthorizationTicketPersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-authz-app";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiTicketService ticketService;

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver authorizationScopeResolver() {
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
                .setName("IT 授权应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        return issue;
    }

    @Test
    void authorizesIssuesAndRevokesAcrossTheWholeChain() {
        AiApplicationCredentialIssueDTO application = createEnabledApplication();
        Long applicationId = application.getApplication().getId();
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        Long grantId = grantService.createGrant(
                applicationId, "USER", "alice", "REPORT", "report-1", Set.of("READ", "EXECUTE"));

        // 授权目录读取路径（Mapper 默认方法）：详情、按主体查询、分页
        assertThat(grantService.getGrant(grantId).getActions()).isEqualTo("EXECUTE,READ");
        assertThat(grantService
                        .getGrantPage(new PageParam(), applicationId, "USER", "alice", "REPORT")
                        .getList())
                .hasSize(1);

        // 统一判定：命中、跨类型不串权、未授权动作拒绝、同 ID 不同类型拒绝
        AiAuthorizationDecisionDTO allowed = authorizationService.authorize(
                applicationId, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of("report-1"));
        assertThat(allowed.isAllowed()).isTrue();
        assertThat(allowed.getGrantedActions()).containsExactlyInAnyOrder("READ", "EXECUTE");
        assertThat(authorizationService
                        .authorize(
                                applicationId,
                                "USER",
                                "alice",
                                AiResourceType.FILE,
                                "report-1",
                                AiAction.READ,
                                List.of())
                        .isAllowed())
                .as("同 ID 不同类型不串权")
                .isFalse();
        assertThat(authorizationService
                        .authorize(
                                applicationId,
                                "USER",
                                "alice",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.EXPORT,
                                List.of())
                        .isAllowed())
                .as("未列入白名单的动作拒绝")
                .isFalse();

        // 换票 → 校验（使用一次性明文秘密）
        AiTicketIssueDTO ticket = ticketService.issue(
                APP_CODE, application.getSecret(), AiSubjectType.USER, "alice", List.of("report-1"));
        assertThat(ticket.getToken()).isNotBlank();
        assertThat(ticket.getResourceKeys()).containsExactly("report-1");
        AiTicketContextDTO context = ticketService.verify(ticket.getToken());
        assertThat(context.getExternalUserId()).isEqualTo("alice");
        assertThat(context.getOrganizationIds()).containsExactly(10L);
        assertThat(context.getResourceKeys()).containsExactly("report-1");

        // 撤销票据：立即失效
        ticketService.revokeTickets(applicationId, AiSubjectType.USER, "alice");
        assertThatThrownBy(() -> ticketService.verify(ticket.getToken()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_TICKET_INVALID.getCode());

        // 撤销授权：判定立即拒绝，且旧范围指纹失效（不得读取旧聚合）
        grantService.revokeGrant(grantId, 0);
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
        assertThat(authorizationService
                        .reauthorizeHistorical(
                                applicationId,
                                "USER",
                                "alice",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.READ,
                                allowed.getScopeFingerprint())
                        .isAllowed())
                .isFalse();
    }

    @Test
    void ticketExchangeRequiresClientCredential() {
        createEnabledApplication();

        assertThatThrownBy(() -> ticketService.issue(
                        APP_CODE, "aiapp_wrong-secret", AiSubjectType.USER, "alice", List.of("report-1")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID.getCode());
    }
}
