package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
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
import com.basicframework.module.ai.service.usage.AiModelInvocationRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Q07 NFR-04 平台容量实测（真实 MySQL/Redis，固定响应的 Mock 模型）：20 并发 run 下测量
 * <b>平台受理</b>与<b>状态查询</b>的 P50/P95/P99，并把模型耗时单独固定、单独记录。
 *
 * <p>口径与 [02 产品需求 §6 NFR-04] 一致：Mock 模型固定响应时受理 P95 ≤ 500ms、状态查询 P95 ≤ 300ms；
 * 模型与数据源耗时单独记录。测量在真实数据库上完成，不做任何数字伪造：测出来是多少就写多少，
 * 同时记录机器配置与 JDK 版本（NFR 基线是 8 vCPU/16GB；硬件不符时结论标不可比）。
 *
 * <p>模型边界用 {@link MockitoBean} 替换为固定响应（NFR 明确允许"Mock 模型固定响应"），
 * 下游（受理、租约、会话、事件、终态写入、数据库）全部真实。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiPlatformCapacityIT.ResolverConfiguration.class)
class AiPlatformCapacityIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver capacityScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    /** NFR-04 的并发维度：20 个并发 run。 */
    private static final int CONCURRENCY = 20;

    /** 固定模型响应延迟（毫秒）：用于把"模型耗时"与"平台开销"分开记录。 */
    private static final long FIXED_MODEL_MILLIS = 50L;

    /** NFR-04：Mock 模型固定响应时，平台受理 P95 上限（毫秒）。 */
    private static final long ACCEPT_P95_LIMIT_MILLIS = 500L;

    /** NFR-04：状态查询 P95 上限（毫秒）。 */
    private static final long STATUS_P95_LIMIT_MILLIS = 300L;

    private static final String APP_CODE = "it-capacity-app";

    private static final String ENDPOINT_NAME = "it-capacity-endpoint";

    private static final String USER_A = "it-capacity-user";

    private static final List<String> RAW = Collections.synchronizedList(new ArrayList<>());

    private static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @MockitoBean
    private AiModelInvocationService invocationService;

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

    @AfterAll
    static void writeRawMeasurements() throws IOException {
        Path directory = Path.of("target", "q07-performance");
        Files.createDirectories(directory);
        Files.write(directory.resolve("nfr04-raw-measurements.txt"), new ArrayList<>(RAW), StandardCharsets.UTF_8);
    }

    /** 记录一行原始测量（标准输出 + 原始结果文件），供证据文档引用。 */
    private static void record(String line) {
        RAW.add(line);
        System.out.println("Q07-MEASURE " + line);
    }

    private static void recordHost() {
        record("host availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMiB=" + (Runtime.getRuntime().maxMemory() >> 20)
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " arch=" + System.getProperty("os.arch")
                + " java=" + System.getProperty("java.version"));
        record("fixture mysql=mysql:8.4.11 redis=redis:7.4.11 concurrency=" + CONCURRENCY + " fixedModelMillis="
                + FIXED_MODEL_MILLIS);
    }

    private void preparePublishedService() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 容量应用")
                .setOrigins(List.of("https://capacity.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 容量应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "容量用户", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-capacity.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-capacity");
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

    private Long conversationOf(String keySuffix) {
        return conversationService.create(new AiConversationCreateDTO()
                .setConversationKey("conv_q07-cap-" + keySuffix)
                .setServiceId(serviceId));
    }

    private AiRunAcceptResultDTO accept(String keySuffix) {
        return runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationOf(keySuffix))
                .setIdempotencyKey("idem-q07-cap-" + keySuffix)
                .setMessage("帮我查订单 A-1")
                .setDataLevel("L2_INTERNAL"));
    }

    /** 固定响应的模型边界：延迟固定、输出固定，用于把模型耗时与平台开销分开。 */
    private void stubFixedModelResponse() {
        given(invocationService.generate(anyLong(), any(ModelRequest.class), any(AiOutboundLevel.class)))
                .willAnswer(invocation -> {
                    Thread.sleep(FIXED_MODEL_MILLIS);
                    AiModelInvocationRecord meter = new AiModelInvocationRecord()
                            .setInvocationId("it-q07-capacity-" + INVOCATIONS.incrementAndGet())
                            .setCapability("TEXT")
                            .setStatus("SUCCEEDED")
                            .setLatencyMs(FIXED_MODEL_MILLIS);
                    return new AiModelInvocationResult<>(
                            meter, new ModelResponse("固定响应", ModelUsage.of(12, 4), List.of(), "gpt-4o-mini", "stop"));
                });
    }

    /** 采集 n 个并发样本（每轮一个屏障），返回每轮的操作耗时（毫秒）。 */
    private List<Long> measureConcurrent(int rounds, int concurrency, String keyPrefix, Consumer<String> operation)
            throws Exception {
        workers = Executors.newFixedThreadPool(concurrency);
        List<Long> samples = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            int roundIndex = round;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                String suffix = keyPrefix + "-r" + roundIndex + "-w" + index;
                futures.add(workers.submit((Callable<Long>) () -> {
                    loginOnCurrentThread();
                    start.await(10, TimeUnit.SECONDS);
                    long began = System.nanoTime();
                    operation.accept(suffix);
                    return (System.nanoTime() - began) / 1_000_000L;
                }));
            }
            start.countDown();
            for (Future<Long> future : futures) {
                samples.add(future.get(120, TimeUnit.SECONDS));
            }
        }
        workers.shutdown();
        assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        workers = null;
        return samples;
    }

    /** 最近秩百分位（P95 等）。 */
    private static long percentile(List<Long> samples, double percentile) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0d * sorted.size());
        return sorted.get(Math.max(1, Math.min(sorted.size(), rank)) - 1);
    }

    private static String stats(String label, List<Long> samples) {
        return String.format(
                "%s samples=%d p50=%d p95=%d p99=%d max=%d",
                label,
                samples.size(),
                percentile(samples, 50),
                percentile(samples, 95),
                percentile(samples, 99),
                Collections.max(samples));
    }

    /** NFR-04：20 并发受理的平台受理 P95（Mock 模型固定响应；受理本身不调用模型）。 */
    @Test
    void twentyConcurrentAcceptsMeetTheReviewedAcceptLatencyTarget() throws Exception {
        preparePublishedService();
        recordHost();
        // 预热：连接池建立与语句缓存不计入样本（NFR 未规定预热，此处如实记录该口径）
        for (int index = 0; index < 5; index++) {
            loginOnCurrentThread();
            accept(String.format("warm-%02d", index));
        }

        List<Long> samples = measureConcurrent(2, CONCURRENCY, "accept", this::accept);
        record(stats("NFR-04 accept", samples));
        assertThat(percentile(samples, 95))
                .as("NFR-04：20 并发受理 P95 ≤ %dms（实测 %s）", ACCEPT_P95_LIMIT_MILLIS, stats("accept", samples))
                .isLessThanOrEqualTo(ACCEPT_P95_LIMIT_MILLIS);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run WHERE application_id = ? AND deleted = 0",
                        Integer.class,
                        applicationId))
                .as("并发受理全部落库（5 预热 + 40 样本）")
                .isEqualTo(45);
    }

    /** NFR-04：20 并发运行下的状态查询 P95（进度/结果引用查询，真实数据库）。 */
    @Test
    void twentyConcurrentStatusQueriesMeetTheReviewedLatencyTarget() throws Exception {
        preparePublishedService();
        recordHost();
        List<Long> runIds = new ArrayList<>();
        loginOnCurrentThread();
        for (int index = 0; index < CONCURRENCY; index++) {
            runIds.add(accept(String.format("status-%02d", index)).getRunId());
        }
        AtomicInteger cursor = new AtomicInteger();
        List<Long> samples = measureConcurrent(10, CONCURRENCY, "status", key -> {
            Long runId = runIds.get(Math.abs(cursor.getAndIncrement()) % runIds.size());
            taskService.progress(runId);
        });
        record(stats("NFR-04 statusQuery", samples));
        assertThat(percentile(samples, 95))
                .as("NFR-04：状态查询 P95 ≤ %dms（实测 %s）", STATUS_P95_LIMIT_MILLIS, stats("statusQuery", samples))
                .isLessThanOrEqualTo(STATUS_P95_LIMIT_MILLIS);
    }

    /** NFR-04：20 并发完整运行（固定 50ms 模型响应）全部成功，并分别记录端到端与平台开销。 */
    @Test
    void twentyConcurrentRunsWithFixedModelResponseSucceedAndReportPlatformOverhead() throws Exception {
        preparePublishedService();
        stubFixedModelResponse();
        recordHost();
        loginOnCurrentThread();
        List<Long> runIds = new ArrayList<>();
        for (int index = 0; index < CONCURRENCY; index++) {
            runIds.add(accept(String.format("exec-%02d", index)).getRunId());
        }
        // 领取在屏障前完成：每个样本对应"领取后执行"的耗时，并发维度仍是 20
        List<AiTaskLeaseDTO> leases = taskService.claim("worker-capacity", 50, 60).stream()
                .filter(lease -> runIds.contains(lease.getRunId()))
                .toList();
        assertThat(leases).hasSize(CONCURRENCY);

        workers = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (AiTaskLeaseDTO lease : leases) {
            futures.add(workers.submit((Callable<Long>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                long began = System.nanoTime();
                executionService.execute(lease, AiRunBudget.defaults());
                return (System.nanoTime() - began) / 1_000_000L;
            }));
        }
        start.countDown();
        List<Long> samples = new ArrayList<>();
        for (Future<Long> future : futures) {
            samples.add(future.get(120, TimeUnit.SECONDS));
        }
        workers.shutdown();
        assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        workers = null;

        for (Long runId : runIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId))
                    .isEqualTo(AiRunDO.STATUS_SUCCEEDED);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM ai_conversation_message WHERE source_run_id = ? AND role ="
                                    + " 'assistant'",
                            Integer.class,
                            runId))
                    .isEqualTo(1);
        }
        assertThat(taskService.countActiveLeases()).isZero();
        List<Long> platformOverhead = samples.stream()
                .map(sample -> Math.max(0L, sample - FIXED_MODEL_MILLIS))
                .toList();
        record(stats("NFR-04 fullRunEndToEnd", samples));
        record(stats("NFR-04 platformOverheadExcludingFixedModel", platformOverhead));
        assertThat(percentile(samples, 95))
                .as("20 并发完整运行在固定模型响应下应远低于预算上限（实测 %s）", stats("fullRun", samples))
                .isLessThan(AiRunBudget.DEFAULT_MAX_DURATION_MILLIS);
    }
}
