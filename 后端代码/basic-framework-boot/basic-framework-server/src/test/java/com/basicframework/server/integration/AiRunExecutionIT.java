package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.run.AiRunExecutionService;
import com.basicframework.module.ai.service.run.AiRunService;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * O04 文本运行执行端到端（真实 MySQL）：受理 → 领取 → 执行。
 *
 * <p>本环境未装配模型客户端、端点地址也不可达，因此执行必须以**稳定的模型调用失败**结束，
 * 并把运行写成 FAILED（绝不返回假成功）；重复执行不会覆盖已写入的终态。
 */
@Import(AiRunExecutionIT.ResolverConfiguration.class)
class AiRunExecutionIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver runExecutionScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-run-exec-app";

    private static final String ENDPOINT_NAME = "it-run-exec-endpoint";

    private static final String USER_A = "it-run-exec-user";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private AiServiceService serviceService;

    @Autowired
    private AiServiceReleaseService releaseService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiRunService runService;

    @Autowired
    private AiRunExecutionService executionService;

    @Autowired
    private AiTaskService taskService;

    private Long applicationId;

    private String applicationSecret;

    private Long serviceId;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update(
                    "DELETE FROM ai_run_task WHERE run_id IN (SELECT id FROM ai_run WHERE application_id = ?)", appId);
            jdbcTemplate.update("DELETE FROM ai_run_idempotency WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_run WHERE application_id = ?", appId);
            jdbcTemplate.update(
                    "DELETE FROM ai_conversation_message WHERE conversation_id IN"
                            + " (SELECT id FROM ai_conversation WHERE application_id = ?)",
                    appId);
            jdbcTemplate.update("DELETE FROM ai_conversation WHERE application_id = ?", appId);
            List<Long> serviceIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_service WHERE app_id = ?", Long.class, appId);
            for (Long id : serviceIds) {
                List<Long> releaseIds = jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, id);
                for (Long releaseId : releaseIds) {
                    jdbcTemplate.update("DELETE FROM ai_service_release_evaluation WHERE release_id = ?", releaseId);
                }
                jdbcTemplate.update("DELETE FROM ai_service_resource WHERE service_id = ?", id);
                jdbcTemplate.update("DELETE FROM ai_service_release WHERE service_id = ?", id);
                jdbcTemplate.update("DELETE FROM ai_service WHERE id = ?", id);
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

    private void preparePublishedService() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 运行执行应用")
                .setOrigins(List.of("https://run-exec.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 运行执行应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "执行用户", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-run-exec.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-run-exec");
        Long endpointId = endpointService.createEndpoint(endpointSave);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, latency_ms, creator, updater) VALUES (?, ?, 1, 'TEXT', 'SUPPORTED', NULL, 5,"
                        + " 'it', 'it')",
                endpointId,
                revision.getRevision());

        serviceId = serviceService.createDraft(new AiServiceSaveDTO()
                .setAppId(applicationId)
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
    }

    private void login() {
        String token = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, USER_A, List.of("report-1"))
                .getToken();
        var context = ticketService.verify(token);
        LoginUser loginUser = new LoginUser()
                .setId(context.getTicketId())
                .setUserType(com.basicframework.framework.common.enums.UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(context.getApplicationId()),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        context.getSubjectType(),
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        context.getExternalUserId()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    /** 受理一个带会话的运行并领取其首任务。 */
    private AiTaskLeaseDTO acceptAndClaim() {
        login();
        Long conversationId = conversationService.create(
                new AiConversationCreateDTO().setConversationKey("conv_exec").setServiceId(serviceId));
        AiRunAcceptResultDTO accepted = runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationId)
                .setIdempotencyKey("idem-0123456789abcdef")
                .setMessage("帮我查订单 A-1")
                .setDataLevel("L2_INTERNAL"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT data_level FROM ai_run WHERE id = ?", String.class, accepted.getRunId()))
                .as("受理时声明的数据分级随运行持久化，执行阶段的外发策略按它判定")
                .isEqualTo("L2_INTERNAL");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ? AND role = 'user'",
                        Integer.class,
                        conversationId))
                .as("受理把用户消息与幂等记录、运行、首任务同事务落库")
                .isEqualTo(1);
        return taskService.claim("worker-a", 10, 60).get(0);
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void unreachableUpstreamEndsWithStableErrorAndFailedRunWithoutFakeSuccess() {
        preparePublishedService();
        AiTaskLeaseDTO lease = acceptAndClaim();

        assertThatThrownBy(() -> executionService.execute(lease, AiRunBudget.defaults()))
                .as("上游不可用/未启用时以稳定错误结束，不返回假成功")
                .satisfies(exception -> assertThat(exception)
                        .isInstanceOf(ServiceException.class)
                        .extracting(error -> ((ServiceException) error).getCode())
                        .isIn(
                                AiErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode(),
                                AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED.getCode(),
                                AiErrorCodeConstants.AI_QUOTA_EXCEEDED.getCode()));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_run WHERE id = ?", String.class, lease.getRunId()))
                .as("失败必须落库为 FAILED")
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation_message WHERE role = 'assistant'", Integer.class))
                .as("失败不写入助手消息（不伪造输出）")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_run_task WHERE id = ?", String.class, lease.getTaskId()))
                .isEqualTo("FAILED");
    }

    @Test
    void lateExecutionCannotOverwriteTheTerminalState() {
        preparePublishedService();
        AiTaskLeaseDTO lease = acceptAndClaim();
        assertThatThrownBy(() -> executionService.execute(lease, AiRunBudget.defaults()))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_run WHERE id = ?", String.class, lease.getRunId()))
                .isEqualTo("FAILED");

        // 迟到的重复执行：运行已是终态，直接拒绝且不改写状态
        assertThatThrownBy(() -> executionService.execute(lease, AiRunBudget.defaults()))
                .as("终态不会被晚到的回调覆盖")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_run WHERE id = ?", String.class, lease.getRunId()))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM ai_run WHERE id = ?", Integer.class, lease.getRunId()))
                .as("终态写入只推进一次乐观锁版本")
                .isEqualTo(1);
    }

    @Test
    void identityIsRebuiltBeforeExecutionSoRevokedSubjectsCannotRun() {
        preparePublishedService();
        AiTaskLeaseDTO lease = acceptAndClaim();

        // 主体撤销后：执行在身份重建阶段就失败，不发出模型调用
        subjectService.disableSubject(applicationId, AiSubjectType.USER, USER_A);
        assertThatThrownBy(() -> executionService.execute(lease, AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_run WHERE id = ?", String.class, lease.getRunId()))
                .as("身份不可用时运行不得停留在 ACCEPTED")
                .isNotEqualTo("SUCCEEDED");
    }
}
