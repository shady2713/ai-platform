package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
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
import java.util.ArrayList;
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
 * O02 并发受理端到端（真实 MySQL，写入提交）：10 个并发请求使用同一幂等键与同一请求正文，
 * 只允许产生一个运行、一条幂等记录与一个首任务。
 *
 * <p>本类关闭测试事务（{@link Propagation#NOT_SUPPORTED}）：并发受理必须看到彼此**已提交**的写入，
 * 否则唯一键兜底与"冲突后回读"都无法被真实验证。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiRunAcceptanceConcurrencyIT.ResolverConfiguration.class)
class AiRunAcceptanceConcurrencyIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver concurrentRunScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-run-concurrent-app";

    private static final String ENDPOINT_NAME = "it-run-concurrent-endpoint";

    private static final String USER_A = "it-run-concurrent-user";

    private static final String IDEMPOTENCY_KEY = "idem-0123456789abcdef";

    private static final int WORKERS = 10;

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
    private AiRunService runService;

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
                    "DELETE FROM ai_run_task WHERE run_id IN (SELECT id FROM ai_run WHERE application_id = ?)", appId);
            jdbcTemplate.update("DELETE FROM ai_run_idempotency WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_run WHERE application_id = ?", appId);
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
                .setName("IT 并发运行应用")
                .setOrigins(List.of("https://run-concurrent.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 并发运行应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "并发用户", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-run-concurrent.example.com/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-run-concurrent");
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

    /** 每个工作线程都有自己的 MEMBER 会话：身份由服务端会话解析，线程之间不共享上下文。 */
    private void loginOnCurrentThread() {
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

    private static AiRunAcceptDTO acceptDTO() {
        return new AiRunAcceptDTO()
                .setServiceId(9L)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .setMessage("帮我查订单 A-1")
                .setAttachmentKeys(List.of("file-a"))
                .setBusinessContext("{\"page\":\"order\"}")
                .setDataLevel("L2_INTERNAL");
    }

    @Test
    void tenConcurrentAcceptsWithTheSameKeyProduceExactlyOneRun() throws Exception {
        preparePublishedService();
        workers = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AiRunAcceptResultDTO>> futures = new ArrayList<>();
        for (int index = 0; index < WORKERS; index++) {
            futures.add(workers.submit((Callable<AiRunAcceptResultDTO>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                return runService.accept(acceptDTO().setServiceId(serviceId));
            }));
        }
        start.countDown();

        List<Long> runIds = new ArrayList<>();
        for (Future<AiRunAcceptResultDTO> future : futures) {
            runIds.add(future.get(60, TimeUnit.SECONDS).getRunId());
        }

        assertThat(runIds).as("10 个并发请求只产生一个运行").containsOnly(runIds.get(0));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run WHERE application_id = ? AND deleted = 0",
                        Integer.class,
                        applicationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_idempotency WHERE application_id = ? AND deleted = 0",
                        Integer.class,
                        applicationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_task WHERE run_id = ? AND deleted = 0",
                        Integer.class,
                        runIds.get(0)))
                .isEqualTo(1);
    }
}
