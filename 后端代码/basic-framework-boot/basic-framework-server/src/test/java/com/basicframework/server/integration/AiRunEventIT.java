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
import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * O05 运行事件端到端（真实 MySQL，写入提交）：序号并发安全分配、按 seq 重放与窗口过期、
 * 取消写入终态事件并终止任务、越权与不存在同语义。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiRunEventIT.ResolverConfiguration.class)
class AiRunEventIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver runEventScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-run-event-app";

    private static final String ENDPOINT_NAME = "it-run-event-endpoint";

    private static final String USER_A = "it-run-event-user-a";

    private static final String USER_B = "it-run-event-user-b";

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

    private Long applicationId;

    private String applicationSecret;

    private Long serviceId;

    private ExecutorService workers;

    @AfterEach
    void cleanUp() throws InterruptedException {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (workers != null) {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            workers = null;
        }
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
                .setName("IT 事件应用")
                .setOrigins(List.of("https://run-event.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 事件应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "事件用户 A", "it-scope", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_B, "事件用户 B", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-run-event.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-run-event");
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

    private AiRunAcceptResultDTO acceptRun() {
        login(USER_A);
        Long conversationId = conversationService.create(
                new AiConversationCreateDTO().setConversationKey("conv_event").setServiceId(serviceId));
        return runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationId)
                .setIdempotencyKey("idem-0123456789abcdef")
                .setMessage("帮我查订单 A-1")
                .setDataLevel("L2_INTERNAL"));
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void eventsKeepSeqOrderAndReplayAdvancesBySeq() {
        preparePublishedService();
        Long runId = acceptRun().getRunId();

        eventService.append(runId, "QUEUED", null, null);
        eventService.append(runId, "RUNNING", "TEXT", "{\"text\":\"部分输出\"}");

        List<AiRunEventDTO> all = eventService.replay(runId, 0, 10);
        assertThat(all).extracting(AiRunEventDTO::getSeq).containsExactly(1, 2);
        assertThat(all).extracting(AiRunEventDTO::getStatus).containsExactly("QUEUED", "RUNNING");
        assertThat(all).allSatisfy(event -> {
            assertThat(event.getSchemaVersion()).isEqualTo("1.0");
            assertThat(event.getRunId()).startsWith("run_");
        });

        // 按 seq 推进：afterSeq=1 只补发第 2 条
        assertThat(eventService.replay(runId, 1, 10))
                .extracting(AiRunEventDTO::getSeq)
                .containsExactly(2);
        assertThat(jdbcTemplate.queryForObject("SELECT event_seq FROM ai_run WHERE id = ?", Integer.class, runId))
                .as("运行行上的序号与事件条数一致")
                .isEqualTo(2);
    }

    @Test
    void concurrentAppendsNeverReuseASeq() throws Exception {
        preparePublishedService();
        Long runId = acceptRun().getRunId();
        workers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AiRunEventDTO>> futures = new java.util.ArrayList<>();
        for (int index = 0; index < 4; index++) {
            futures.add(workers.submit((Callable<AiRunEventDTO>) () -> {
                login(USER_A);
                start.await(10, TimeUnit.SECONDS);
                return eventService.append(runId, "RUNNING", null, null);
            }));
        }
        start.countDown();

        List<Integer> seqs = new java.util.ArrayList<>();
        for (Future<AiRunEventDTO> future : futures) {
            seqs.add(future.get(30, TimeUnit.SECONDS).getSeq());
        }

        assertThat(seqs).as("并发追加不重复使用同一序号").doesNotHaveDuplicates().hasSize(4);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT seq) FROM ai_run_event WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(4);
    }

    @Test
    void replayOutsideTheRetainedWindowFailsAndSnapshotGuidesRecovery() {
        preparePublishedService();
        Long runId = acceptRun().getRunId();
        eventService.append(runId, "QUEUED", null, null);
        eventService.append(runId, "RUNNING", null, null);
        eventService.append(runId, "SUCCEEDED", null, null);

        // 模拟保留期清理：把最早两条事件删掉，客户端还停在 seq=1
        jdbcTemplate.update("DELETE FROM ai_run_event WHERE run_id = ? AND seq <= 2", runId);

        assertThatThrownBy(() -> eventService.replay(runId, 1, 10))
                .as("窗口过期必须明确报错，不用新 POST 偷偷重跑")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED));

        var snapshot = eventService.snapshot(runId);
        assertThat(snapshot.getStatus()).isEqualTo("ACCEPTED");
        assertThat(snapshot.getLatestSeq()).isEqualTo(3);
        assertThat(snapshot.getEarliestSeq()).isEqualTo(3);
    }

    @Test
    void cancelWritesTerminalEventAndTerminatesTheTask() {
        preparePublishedService();
        Long runId = acceptRun().getRunId();
        eventService.append(runId, "RUNNING", null, null);
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);

        eventService.cancel(runId, version);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run_task WHERE run_id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT last_error_code FROM ai_run_task WHERE run_id = ?", String.class, runId))
                .isEqualTo("CANCELLED");
        List<AiRunEventDTO> events = eventService.replay(runId, 0, 10);
        assertThat(events).extracting(AiRunEventDTO::getStatus).containsExactly("RUNNING", "CANCELLED");

        // 取消是幂等的显式动作：重复取消按终态冲突结束，不追加事件
        assertThatThrownBy(() -> eventService.cancel(runId, version + 1))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
        assertThat(eventService.replay(runId, 0, 10)).hasSize(2);
    }

    @Test
    void subscriptionsAndCancelsAreScopedToTheResolvedSubject() {
        preparePublishedService();
        Long runId = acceptRun().getRunId();
        eventService.append(runId, "RUNNING", null, null);

        login(USER_B);
        assertThatThrownBy(() -> eventService.replay(runId, 0, 10))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> eventService.snapshot(runId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> eventService.cancel(runId, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }
}
