package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.event.AiRunEventService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
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
import com.basicframework.module.ai.service.task.dto.AiRetentionCleanupResultDTO;
import com.basicframework.module.ai.service.task.dto.AiRunProgressDTO;
import java.time.Duration;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * O06 任务查询、人工重试与保留期清理端到端（真实 MySQL，写入提交）：
 * 进度与结果引用、UNKNOWN 不可重试、清理不删仍被引用的数据。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiTaskQueryRetryCleanupIT.ResolverConfiguration.class)
class AiTaskQueryRetryCleanupIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver taskQueryScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-task-query-app";

    private static final String ENDPOINT_NAME = "it-task-query-endpoint";

    private static final String USER_A = "it-task-query-user-a";

    private static final String USER_B = "it-task-query-user-b";

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
    private AiRunEventService eventService;

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
                    "DELETE FROM ai_run_event WHERE run_id IN (SELECT id FROM ai_run WHERE application_id = ?)", appId);
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
                .setName("IT 任务查询应用")
                .setOrigins(List.of("https://task-query.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 任务查询应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "查询用户 A", "it-scope", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_B, "查询用户 B", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-task-query.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-task-query");
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

    private void login(String externalUserId) {
        String token = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of("report-1"))
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

    private AiRunAcceptResultDTO acceptRun(String conversationKey) {
        return acceptRun(conversationKey, "idem-" + conversationKey + "-0000");
    }

    /** 每次受理用独立幂等键：同一主体下同键不同正文会被幂等判定拒绝（O02 语义）。 */
    private AiRunAcceptResultDTO acceptRun(String conversationKey, String idempotencyKey) {
        login(USER_A);
        Long conversationId = conversationService.create(new AiConversationCreateDTO()
                .setConversationKey(conversationKey)
                .setServiceId(serviceId));
        return runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationId)
                .setIdempotencyKey(idempotencyKey)
                .setMessage("帮我查订单 A-1")
                .setDataLevel("L2_INTERNAL"));
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void progressReportsStatusSeqAndRetryabilityAndIsSubjectScoped() {
        preparePublishedService();
        Long runId = acceptRun("conv_query").getRunId();
        eventService.append(runId, "QUEUED", null, null);

        AiRunProgressDTO progress = taskService.progress(runId);
        assertThat(progress.getStatus()).isEqualTo("ACCEPTED");
        assertThat(progress.getLatestSeq()).isEqualTo(1);
        assertThat(progress.getTaskStatus()).isEqualTo("QUEUED");
        assertThat(progress.isRetryable()).as("排队中的任务不需要人工重试").isFalse();

        login(USER_B);
        assertThatThrownBy(() -> taskService.progress(runId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThat(taskService
                        .pageProgress(new com.basicframework.framework.common.pojo.PageParam())
                        .getList())
                .isEmpty();
    }

    @Test
    void failedTaskCanBeRetriedButUnknownCannot() {
        preparePublishedService();
        Long runId = acceptRun("conv_retry").getRunId();
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM ai_run_task WHERE run_id = ?", Long.class, runId);
        // 模拟一次失败的执行：运行与任务都进入 FAILED
        jdbcTemplate.update("UPDATE ai_run SET status = 'FAILED' WHERE id = ?", runId);
        jdbcTemplate.update(
                "UPDATE ai_run_task SET status = 'FAILED', attempt_count = 3, last_error_code = '502' WHERE id = ?",
                taskId);

        AiRunProgressDTO progress = taskService.progress(runId);
        assertThat(progress.isRetryable()).isTrue();
        assertThat(progress.getLastErrorCode()).isEqualTo("502");

        Integer runVersion =
                jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);
        taskService.retry(runId, runVersion);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId))
                .isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run_task WHERE id = ?", String.class, taskId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM ai_run_task WHERE id = ?", Integer.class, taskId))
                .as("人工重试重置自动重试预算")
                .isZero();

        // UNKNOWN（结果未知）任务：拒绝普通重试
        jdbcTemplate.update("UPDATE ai_run SET status = 'FAILED' WHERE id = ?", runId);
        jdbcTemplate.update("UPDATE ai_run_task SET status = 'UNKNOWN' WHERE id = ?", taskId);
        Integer versionAfterRetry =
                jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);
        assertThat(taskService.progress(runId).isRetryable()).isFalse();
        assertThatThrownBy(() -> taskService.retry(runId, versionAfterRetry))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run_task WHERE id = ?", String.class, taskId))
                .as("被拒绝的重试不改变任务状态")
                .isEqualTo("UNKNOWN");
    }

    @Test
    void cleanupPurgesExpiredTerminalDataButKeepsStillReferencedRows() {
        preparePublishedService();
        Long terminalRunId = acceptRun("conv_cleanup_terminal").getRunId();
        Long liveRunId = acceptRun("conv_cleanup_live").getRunId();
        eventService.append(terminalRunId, "SUCCEEDED", null, null);
        eventService.append(liveRunId, "RUNNING", null, null);

        // 终态运行：会话关闭（删除先关闭访问）+ 时间戳推到保留期之外
        Long terminalConversationId = jdbcTemplate.queryForObject(
                "SELECT conversation_id FROM ai_run WHERE id = ?", Long.class, terminalRunId);
        jdbcTemplate.update("UPDATE ai_run SET status = 'SUCCEEDED' WHERE id = ?", terminalRunId);
        jdbcTemplate.update("UPDATE ai_run_task SET status = 'SUCCEEDED' WHERE run_id = ?", terminalRunId);
        jdbcTemplate.update(
                "UPDATE ai_conversation SET status = 'DELETED', update_time = DATE_SUB(NOW(), INTERVAL 60 DAY)"
                        + " WHERE id = ?",
                terminalConversationId);
        jdbcTemplate.update(
                "UPDATE ai_conversation_message SET update_time = DATE_SUB(NOW(), INTERVAL 60 DAY)"
                        + " WHERE conversation_id = ?",
                terminalConversationId);
        jdbcTemplate.update(
                "UPDATE ai_run_event SET create_time = DATE_SUB(NOW(), INTERVAL 60 DAY) WHERE run_id IN (?, ?)",
                terminalRunId,
                liveRunId);
        jdbcTemplate.update(
                "UPDATE ai_run_task SET update_time = DATE_SUB(NOW(), INTERVAL 60 DAY) WHERE run_id = ?",
                terminalRunId);
        jdbcTemplate.update(
                "UPDATE ai_run SET update_time = DATE_SUB(NOW(), INTERVAL 60 DAY) WHERE id = ?", terminalRunId);
        // 非终态运行：把它指向同一个会话，验证"仍被引用则不删"
        jdbcTemplate.update(
                "UPDATE ai_run SET conversation_id = ?, update_time = DATE_SUB(NOW(), INTERVAL 60 DAY)"
                        + " WHERE id = ?",
                terminalConversationId,
                liveRunId);

        AiRetentionCleanupResultDTO result = taskService.cleanup(Duration.ofDays(30), 200, 10);

        assertThat(result.total()).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run WHERE id = ?", Integer.class, terminalRunId))
                .as("幂等记录仍引用该运行（保留期内）：运行行不得删除")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation WHERE id = ?", Integer.class, terminalConversationId))
                .as("会话仍被非终态运行引用：不得删除")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_event WHERE run_id = ?", Integer.class, liveRunId))
                .as("非终态运行的事件不得删除")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_run WHERE id = ?", Integer.class, liveRunId))
                .as("非终态运行不得删除")
                .isEqualTo(1);

        // 幂等记录也超过保留期后：运行行可以清理（幂等键的保留期到此结束，重放同一键会产生新运行）
        jdbcTemplate.update(
                "UPDATE ai_run_idempotency SET update_time = DATE_SUB(NOW(), INTERVAL 60 DAY) WHERE run_id = ?",
                terminalRunId);
        AiRetentionCleanupResultDTO third = taskService.cleanup(Duration.ofDays(30), 200, 10);
        assertThat(third.getIdempotency()).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run WHERE id = ?", Integer.class, terminalRunId))
                .as("引用全部解除后运行行才被清理")
                .isZero();

        // 解除引用后：会话与消息可以被清理
        jdbcTemplate.update("UPDATE ai_run SET conversation_id = NULL WHERE id = ?", liveRunId);
        AiRetentionCleanupResultDTO second = taskService.cleanup(Duration.ofDays(30), 200, 10);
        assertThat(second.getConversations()).as("解除引用后会话被清理").isPositive();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ?",
                        Integer.class,
                        terminalConversationId))
                .isZero();
    }
}
