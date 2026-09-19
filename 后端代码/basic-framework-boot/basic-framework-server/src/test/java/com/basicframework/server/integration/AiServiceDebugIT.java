package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.debug.AiServiceDebugService;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugRunDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
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
 * S04 服务调试端到端（真实 MySQL）：显式测试主体与资源 ACL、上下文预算与注册字段校验
 * 都在**模型调用之前**生效；授权放行后上游不可达时以稳定错误结束，不返回假成功。
 */
@Import(AiServiceDebugIT.ResolverConfiguration.class)
class AiServiceDebugIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver debugScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-debug-app";

    private static final String ENDPOINT_NAME = "it-debug-endpoint";

    private static final String EXTERNAL_USER = "it-debug-user";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiServiceService serviceService;

    @Autowired
    private AiServiceReleaseService releaseService;

    @Autowired
    private AiServiceDebugService debugService;

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            List<Long> serviceIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_service WHERE app_id = ?", Long.class, appId);
            for (Long serviceId : serviceIds) {
                List<Long> releaseIds = jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
                for (Long releaseId : releaseIds) {
                    jdbcTemplate.update("DELETE FROM ai_service_release_evaluation WHERE release_id = ?", releaseId);
                }
                jdbcTemplate.update("DELETE FROM ai_service_resource WHERE service_id = ?", serviceId);
                jdbcTemplate.update("DELETE FROM ai_service_release WHERE service_id = ?", serviceId);
                jdbcTemplate.update("DELETE FROM ai_service WHERE id = ?", serviceId);
            }
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, ENDPOINT_NAME);
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private Long createApplication() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 调试应用")
                .setOrigins(List.of("https://debug.example.com")));
        Long appId = issue.getApplication().getId();
        applicationService.updateStatus(appId, 0, true);
        subjectService.syncSubject(appId, AiSubjectType.APP, null, "IT 调试应用", "it-owner", 1L);
        return appId;
    }

    private Long createProbedEndpoint() {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName(ENDPOINT_NAME);
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-debug.invalid/v1");
        saveDTO.setModelId("gpt-4o-mini");
        saveDTO.setCapabilities(List.of("TEXT"));
        saveDTO.setCredential("sk-it-debug");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, latency_ms, creator, updater) VALUES (?, ?, 1, 'TEXT', 'SUPPORTED', NULL, 5,"
                        + " 'it', 'it')",
                endpointId,
                revision.getRevision());
        return endpointId;
    }

    /** 建好已发布服务，并把测试用户登记为有 READ 授权的主体。 */
    private Long publishReadyServiceWithTestUser(Long appId, Long endpointId) {
        grantService.createGrant(appId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        subjectService.syncSubject(appId, AiSubjectType.USER, EXTERNAL_USER, "IT 调试用户", "it-scope", 1L);
        // 调试按测试主体的当前授权判定：用户主体必须有同名资源的授权才可能通过
        grantService.createGrant(appId, "USER", EXTERNAL_USER, "REPORT", "report-1", Set.of("READ"));
        Long serviceId = serviceService.createDraft(new AiServiceSaveDTO()
                .setAppId(appId)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(endpointId)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER")
                .setEvalThreshold(0));
        serviceService.bindResource(new AiServiceResourceSaveDTO()
                .setServiceId(serviceId)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(List.of("READ")));
        var service = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, service.getVersion());
        var ready = serviceService.getService(serviceId);
        Long releaseId = releaseService.createCandidate(serviceId, ready.getVersion());
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(releaseId)
                .setScore(90)
                .setCaseCount(5));
        releaseService.publish(releaseId, 0);
        return serviceId;
    }

    private static AiServiceDebugRunDTO debugRequest(Long serviceId) {
        return new AiServiceDebugRunDTO()
                .setServiceId(serviceId)
                .setTestSubjectType("USER")
                .setTestSubjectId(EXTERNAL_USER)
                .setUserMessage("帮我查一下订单 A-1")
                .setDataLevel("L2_INTERNAL");
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void debugDeniesWhenTheTestSubjectHasNoGrant() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = publishReadyServiceWithTestUser(appId, endpointId);

        // 撤销测试用户的授权：调试必须与真实运行一样被拒绝，且不发模型调用
        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND subject_type = 'USER' AND"
                        + " resource_key = 'report-1'",
                Long.class,
                appId);
        grantService.revokeGrant(grantId, grantService.getGrant(grantId).getVersion());

        assertThatThrownBy(() -> debugService.debugRun(debugRequest(serviceId)))
                .as("调试不能越权：测试主体失权后调试同样拒绝")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
    }

    @Test
    void debugRejectsUnregisteredContextAndOverBudgetInputBeforeCallingTheModel() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = publishReadyServiceWithTestUser(appId, endpointId);

        assertThatThrownBy(
                        () -> debugService.debugRun(debugRequest(serviceId).setBusinessContext("{\"secret\":\"x\"}")))
                .as("业务上下文只接受已注册字段")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONTEXT_SCHEMA_INVALID));

        assertThatThrownBy(() -> debugService.debugRun(debugRequest(serviceId)
                        .setUserMessage("x".repeat(4_000))
                        .setMaxTokens(10)))
                .as("强制分区容不下时给出可解释失败")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONTEXT_BUDGET_EXCEEDED));
    }

    @Test
    void debugPassesAclThenFailsStablyWhenTheUpstreamIsUnreachable() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = publishReadyServiceWithTestUser(appId, endpointId);

        // 授权放行后走到真实模型调用：本环境未装配模型客户端工厂（AI 能力未启用）、端点地址也不可达，
        // 两种情况下平台都必须以稳定的"模型调用失败/配额"错误结束，绝不返回假成功
        assertThatThrownBy(() -> debugService.debugRun(debugRequest(serviceId).setTimeoutMillis(2_000)))
                .as("上游不可达时返回稳定错误码而不是伪造成功")
                .satisfies(exception -> assertThat(exception)
                        .isInstanceOf(ServiceException.class)
                        .extracting(error -> ((ServiceException) error).getCode())
                        .isIn(
                                AiErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode(),
                                AiErrorCodeConstants.AI_QUOTA_EXCEEDED.getCode()));
    }

    @Test
    void debugRequiresAnExplicitTestSubject() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = publishReadyServiceWithTestUser(appId, endpointId);

        assertThatThrownBy(() -> debugService.debugRun(debugRequest(serviceId).setTestSubjectId(null)))
                .as("不接受隐式当前用户作为调试主体")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> debugService.debugRun(debugRequest(serviceId).setTestSubjectType(null)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }
}
