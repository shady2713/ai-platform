package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.job.AiTaskRecoveryJob;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.event.AiRunEventService;
import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
import com.basicframework.module.ai.service.event.dto.AiRunEventSnapshotDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.run.AiRunExecutionService;
import com.basicframework.module.ai.service.run.AiRunService;
import com.basicframework.module.ai.service.run.AiRunTerminalWriter;
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
 * Q07 运行韧性验收（真实 MySQL，写入提交）：
 *
 * <ul>
 *   <li><b>AT-016</b> 取消与晚到完成并发：终态唯一、无重复结果，含取消风暴；</li>
 *   <li><b>AT-017</b> 慢消费者：单次拉取有界、断流后按 seq 重连续读、窗口过期转快照查询，
 *       积压期间服务可用（有界性断言，不做伪造压测数字）；</li>
 *   <li><b>AT-018</b> 进程重启与任务租约：用"租约过期 + 恢复 Job + 新 worker 领取"模拟进程重启，
 *       可重试任务被重新领取并完成，业务唯一性（一运行一任务、一幂等记录、一助手结果）成立。</li>
 * </ul>
 *
 * <p>本类关闭测试事务：并发路径必须看到彼此**已提交**的写入，否则乐观锁与租约栅栏无法被真实验证。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiRunResilienceAcceptanceIT.ResolverConfiguration.class)
class AiRunResilienceAcceptanceIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver resilienceScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-run-resilience-app";

    private static final String ENDPOINT_NAME = "it-run-resilience-endpoint";

    private static final String USER_A = "it-run-resilience-user";

    private static final String DATA_LEVEL = "L2_INTERNAL";

    /** 取消与完成竞争同一终态的轮数（两种顺序都应出现，但断言对任意交错都成立）。 */
    private static final int RACE_ROUNDS = 12;

    /** 取消风暴的并发数。 */
    private static final int CANCEL_STORM_WORKERS = 8;

    /** 慢消费者用例的事件规模：必须大于服务端单批上限（200）才谈得上"有界"。 */
    private static final int BACKLOG_EVENTS = 450;

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
    private AiRunTerminalWriter terminalWriter;

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

    private void preparePublishedService() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 运行韧性应用")
                .setOrigins(List.of("https://run-resilience.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 运行韧性应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "韧性用户", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-run-resilience.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-run-resilience");
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

    /** 受理一个带会话的运行（会话让"助手结果"有落点，便于断言"无重复结果"）。 */
    private Long acceptRun(String keySuffix, String idempotencySuffix) {
        loginOnCurrentThread();
        Long conversationId = conversationService.create(new AiConversationCreateDTO()
                .setConversationKey("conv_q07-" + keySuffix)
                .setServiceId(serviceId));
        return accept(keySuffix, idempotencySuffix, conversationId).getRunId();
    }

    private AiRunAcceptResultDTO accept(String keySuffix, String idempotencySuffix, Long conversationId) {
        return runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationId)
                .setIdempotencyKey("idem-q07-" + idempotencySuffix)
                .setMessage("帮我查订单 A-1")
                .setDataLevel(DATA_LEVEL));
    }

    private AiTaskLeaseDTO claimTaskOf(Long runId, String owner) {
        List<AiTaskLeaseDTO> claimed = taskService.claim(owner, 10, 60);
        return claimed.stream()
                .filter(lease -> runId.equals(lease.getRunId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("任务不可领取：" + runId));
    }

    private String runStatus(Long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId);
    }

    private int runVersion(Long runId) {
        return jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);
    }

    private String taskStatus(Long taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ai_run_task WHERE id = ?", String.class, taskId);
    }

    private int assistantMessageCount(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_message WHERE source_run_id = ? AND role = 'assistant'",
                Integer.class,
                runId);
    }

    private void expireLease(Long taskId) {
        jdbcTemplate.update(
                "UPDATE ai_run_task SET lease_expires_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE id = ?", taskId);
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    /** 并发终止竞争：取消与晚到完成必须恰好一个生效，终态唯一且不产生第二份结果。 */
    @Test
    void at016CancelAndLateCompletionRaceKeepsOneTerminalOutcome() throws Exception {
        preparePublishedService();
        workers = Executors.newFixedThreadPool(2);
        int cancelWins = 0;
        int completionWins = 0;
        for (int round = 0; round < RACE_ROUNDS; round++) {
            int currentRound = round;
            Long runId = acceptRun("race-" + round, String.format("%016x", round));
            AiTaskLeaseDTO lease = claimTaskOf(runId, "worker-race");
            AiRunDO run = runService.getRun(runId);
            assertThat(run.getVersion()).as("受理后的运行尚未进入终态").isZero();

            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> cancel = workers.submit((Callable<Boolean>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                try {
                    eventService.cancel(runId, run.getVersion());
                    return true;
                } catch (ServiceException conflict) {
                    assertThat(conflict.getCode()).isEqualTo(AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL.getCode());
                    return false;
                }
            });
            Future<Boolean> completion = workers.submit((Callable<Boolean>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                return terminalWriter.finish(lease, run, AiRunDO.STATUS_SUCCEEDED, null, 1, "late-" + currentRound);
            });
            start.countDown();

            boolean cancelled = cancel.get(30, TimeUnit.SECONDS);
            boolean completed = completion.get(30, TimeUnit.SECONDS);
            cancelWins += cancelled ? 1 : 0;
            completionWins += completed ? 1 : 0;

            assertThat(cancelled ^ completed).as("第 %s 轮：取消与晚到完成恰好一个生效", round).isTrue();
            String status = runStatus(runId);
            assertThat(status).isEqualTo(cancelled ? AiRunDO.STATUS_CANCELLED : AiRunDO.STATUS_SUCCEEDED);
            assertThat(runVersion(runId)).as("终态只推进一次乐观锁版本").isEqualTo(1);
            assertThat(assistantMessageCount(runId))
                    .as("第 %s 轮：完成生效时恰好一份助手结果，取消生效时不写结果", round)
                    .isEqualTo(completed ? 1 : 0);
            String taskStatus = taskStatus(lease.getTaskId());
            assertThat(taskStatus).isEqualTo(completed ? AiRunTaskDO.STATUS_SUCCEEDED : AiRunTaskDO.STATUS_FAILED);
            List<String> eventStatuses = eventService.replay(runId, 0, 50).stream()
                    .map(AiRunEventDTO::getStatus)
                    .toList();
            assertThat(eventStatuses)
                    .as("取消生效时恰好一条 CANCELLED 终态事件；完成生效时不写取消事件")
                    .isEqualTo(cancelled ? List.of(AiRunDO.STATUS_CANCELLED) : List.of());
        }
        System.out.printf(
                "Q07-MEASURE AT-016 race rounds=%d cancelWins=%d completionWins=%d%n",
                RACE_ROUNDS, cancelWins, completionWins);
    }

    /** 确定性顺序一：取消先生效，晚到的完成不得写状态、不得写结果。 */
    @Test
    void at016LateCompletionAfterCancelWritesNothing() {
        preparePublishedService();
        Long runId = acceptRun("cancel-first", String.format("%016x", 100));
        AiTaskLeaseDTO lease = claimTaskOf(runId, "worker-cancel-first");
        AiRunDO run = runService.getRun(runId);

        eventService.cancel(runId, run.getVersion());
        assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_CANCELLED);

        assertThat(terminalWriter.finish(lease, run, AiRunDO.STATUS_SUCCEEDED, null, 1, "too-late"))
                .as("迟到完成命中乐观锁 0 行，不覆盖 CANCELLED")
                .isFalse();
        assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_CANCELLED);
        assertThat(assistantMessageCount(runId)).as("取消后不产生助手结果").isZero();
        assertThat(taskStatus(lease.getTaskId())).isEqualTo(AiRunTaskDO.STATUS_FAILED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT last_error_code FROM ai_run_task WHERE id = ?", String.class, lease.getTaskId()))
                .isEqualTo("CANCELLED");
    }

    /** 确定性顺序二：完成先生效，晚到的取消是稳定冲突，不追加终态事件。 */
    @Test
    void at016LateCancelAfterCompletionIsAStableConflict() {
        preparePublishedService();
        Long runId = acceptRun("complete-first", String.format("%016x", 101));
        AiTaskLeaseDTO lease = claimTaskOf(runId, "worker-complete-first");
        AiRunDO run = runService.getRun(runId);

        assertThat(terminalWriter.finish(lease, run, AiRunDO.STATUS_SUCCEEDED, null, 1, "done"))
                .isTrue();
        assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_SUCCEEDED);

        assertThatThrownBy(() -> eventService.cancel(runId, run.getVersion()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
        assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
        assertThat(assistantMessageCount(runId)).isEqualTo(1);
        assertThat(eventService.replay(runId, 0, 50)).as("终态不被第二次写入").isEmpty();
        assertThat(runVersion(runId)).isEqualTo(1);
    }

    /** 取消风暴：多路并发取消只有一路生效，只落一条终态事件，任务只被终止一次。 */
    @Test
    void at016CancelStormHasExactlyOneWinnerAndOneTerminalEvent() throws Exception {
        preparePublishedService();
        Long runId = acceptRun("cancel-storm", String.format("%016x", 102));
        AiTaskLeaseDTO lease = claimTaskOf(runId, "worker-storm");
        AiRunDO run = runService.getRun(runId);

        workers = Executors.newFixedThreadPool(CANCEL_STORM_WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < CANCEL_STORM_WORKERS; index++) {
            futures.add(workers.submit((Callable<Boolean>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                try {
                    eventService.cancel(runId, run.getVersion());
                    return true;
                } catch (ServiceException conflict) {
                    assertThat(conflict.getCode()).isEqualTo(AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL.getCode());
                    return false;
                }
            }));
        }
        start.countDown();

        int winners = 0;
        for (Future<Boolean> future : futures) {
            winners += future.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        assertThat(winners).as("取消风暴只有一路生效").isEqualTo(1);
        assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_CANCELLED);
        assertThat(runVersion(runId)).isEqualTo(1);
        assertThat(eventService.replay(runId, 0, 50))
                .extracting(AiRunEventDTO::getStatus)
                .as("只落一条终态事件（不重复追加）")
                .containsExactly(AiRunDO.STATUS_CANCELLED);
        assertThat(taskStatus(lease.getTaskId())).isEqualTo(AiRunTaskDO.STATUS_FAILED);
        assertThat(assistantMessageCount(runId)).isZero();
    }

    /** AT-017：单次拉取有界；断流后按 afterSeq 重连续读，既不丢事件也不重复。 */
    @Test
    void at017SlowConsumerPullsBoundedBatchesAndReconnectsWithoutLossOrDuplication() {
        preparePublishedService();
        Long runId = acceptRun("slow-consumer", String.format("%016x", 200));
        for (int seq = 1; seq <= BACKLOG_EVENTS; seq++) {
            eventService.append(runId, AiRunDO.STATUS_RUNNING, "TEXT", "{\"chunk\":" + seq + "}");
        }

        List<Integer> drained = new ArrayList<>();
        int position = 0;
        int pulls = 0;
        while (true) {
            // limit 远大于服务端单批上限：返回条数仍然是上限而不是"全部"
            List<AiRunEventDTO> batch = eventService.replay(runId, position, 10_000);
            pulls++;
            assertThat(batch.size()).as("单次拉取有界（不超过服务端批上限）").isLessThanOrEqualTo(200);
            if (batch.isEmpty()) {
                break;
            }
            for (AiRunEventDTO event : batch) {
                drained.add(event.getSeq());
                position = event.getSeq();
            }
        }

        assertThat(pulls).as("450 条事件至少需要 3 次有界拉取").isGreaterThanOrEqualTo(3);
        assertThat(drained).as("重连续读不丢事件、序号严格递增").hasSize(BACKLOG_EVENTS).isSorted();
        assertThat(drained).as("客户端按 seq 去重后无重复").doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_event WHERE run_id = ?", Integer.class, runId))
                .as("慢消费者不影响事件持久化")
                .isEqualTo(BACKLOG_EVENTS);
        System.out.printf("Q07-MEASURE AT-017 backlog=%d pulls=%d boundedBatch=200%n", BACKLOG_EVENTS, pulls);
    }

    /** AT-017：消费者落后到保留窗口之外时，服务端给稳定错误，并由快照查询给出恢复位置。 */
    @Test
    void at017ConsumerBehindRetentionWindowGetsStableErrorAndSnapshot() {
        preparePublishedService();
        Long runId = acceptRun("behind-window", String.format("%016x", 201));
        for (int seq = 1; seq <= BACKLOG_EVENTS; seq++) {
            eventService.append(runId, AiRunDO.STATUS_RUNNING, null, null);
        }
        // 模拟保留期清理：最早 300 条已被清理，消费者仍停在 seq=10
        jdbcTemplate.update("DELETE FROM ai_run_event WHERE run_id = ? AND seq <= 300", runId);

        assertThatThrownBy(() -> eventService.replay(runId, 10, 200))
                .as("落后于窗口必须明确报错，而不是悄悄从当前位置重放（否则会丢事件却像成功）")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED));

        AiRunEventSnapshotDTO snapshot = eventService.snapshot(runId);
        assertThat(snapshot.getLatestSeq()).as("快照给出最新序号作为新的续读起点").isEqualTo(BACKLOG_EVENTS);
        assertThat(snapshot.getEarliestSeq()).isEqualTo(301);
        // 转查询后可以从窗口内继续读：不要求重放全部历史
        List<AiRunEventDTO> resumed = eventService.replay(runId, snapshot.getEarliestSeq() - 1, 200);
        assertThat(resumed).extracting(AiRunEventDTO::getSeq).startsWith(301);
        assertThat(resumed).hasSize(150);
    }

    /** AT-017：慢消费者（停止拉取）不阻塞其它请求，也不改变积压事件本身。 */
    @Test
    void at017StalledConsumerDoesNotBlockOtherRequestsNorMutateTheBacklog() throws Exception {
        preparePublishedService();
        Long stalledRunId = acceptRun("stalled", String.format("%016x", 202));
        for (int seq = 1; seq <= 300; seq++) {
            eventService.append(stalledRunId, AiRunDO.STATUS_RUNNING, null, null);
        }
        // 消费者只拉一批就"卡住"：服务端不保留任何未确认缓冲，积压仍在库里
        assertThat(eventService.replay(stalledRunId, 0, 10_000)).hasSize(200);

        int probes = 20;
        workers = Executors.newFixedThreadPool(probes);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> probesFuture = new ArrayList<>();
        for (int index = 0; index < probes; index++) {
            Long targetRunId = index % 2 == 0
                    ? stalledRunId
                    : acceptRun("stalled-probe-" + index, String.format("%016x", 300 + index));
            probesFuture.add(workers.submit((Callable<Long>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                long began = System.nanoTime();
                taskService.progress(targetRunId);
                return (System.nanoTime() - began) / 1_000_000L;
            }));
        }
        start.countDown();

        long worst = 0;
        for (Future<Long> future : probesFuture) {
            worst = Math.max(worst, future.get(30, TimeUnit.SECONDS));
        }
        assertThat(worst).as("积压期间状态查询仍然可用（远低于超时）").isLessThan(3_000L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_event WHERE run_id = ?", Integer.class, stalledRunId))
                .as("卡住的消费者既不丢事件也不改写积压")
                .isEqualTo(300);
        System.out.printf("Q07-MEASURE AT-017 stalledBacklog=300 probes=%d worstProgressMs=%d%n", probes, worst);
    }

    /**
     * AT-018：进程重启（租约过期 + 恢复 Job + 新 worker 领取）后，可重试任务全部恢复并被完成；
     * 业务唯一性成立：一个幂等键只有一个运行、一个运行只有一个任务、一次完成只写一份助手结果。
     */
    @Test
    void at018RestartRecoversRetryableTasksAndKeepsBusinessUniqueness() {
        preparePublishedService();
        int runCount = 5;
        List<Long> runIds = new ArrayList<>();
        List<String> idempotencySuffixes = new ArrayList<>();
        List<Long> taskIds = new ArrayList<>();
        for (int index = 0; index < runCount; index++) {
            String idempotencySuffix = String.format("%016x", 400 + index);
            Long runId = acceptRun("restart-" + index, idempotencySuffix);
            runIds.add(runId);
            idempotencySuffixes.add(idempotencySuffix);
            AiTaskLeaseDTO lease = claimTaskOf(runId, "worker-crashed");
            taskIds.add(lease.getTaskId());
        }
        assertThat(taskService.countActiveLeases()).isEqualTo(runCount);

        // 进程中断：领取后既不心跳也不落库，租约逐个过期
        for (Long taskId : taskIds) {
            expireLease(taskId);
        }
        assertThat(taskService.countActiveLeases()).isZero();

        // 新进程启动后的恢复扫描：未达重试上限的任务回到待领取
        long recoveryStarted = System.nanoTime();
        AiTaskRecoveryJob recoveryJob = new AiTaskRecoveryJob(taskService, 0, 200);
        assertThat(recoveryJob.execute("")).contains(String.valueOf(runCount));
        long recoveryMillis = (System.nanoTime() - recoveryStarted) / 1_000_000L;
        for (Long taskId : taskIds) {
            assertThat(taskStatus(taskId)).isEqualTo(AiRunTaskDO.STATUS_QUEUED);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT lease_owner FROM ai_run_task WHERE id = ?", String.class, taskId))
                    .isNull();
        }

        // 新 worker 重新领取并完成（替代"重启后继续执行"）：一次领取拿到全部恢复任务
        List<AiTaskLeaseDTO> recoveredLeases = taskService.claim("worker-restarted", 50, 60).stream()
                .filter(lease -> runIds.contains(lease.getRunId()))
                .toList();
        assertThat(recoveredLeases).as("恢复后的任务全部可被新 worker 领取").hasSize(runCount);
        for (AiTaskLeaseDTO lease : recoveredLeases) {
            assertThat(lease.getAttempt()).as("恢复后是第 2 次尝试").isEqualTo(2);
            assertThat(lease.getEpoch()).isEqualTo(2);
            assertThat(terminalWriter.finish(
                            lease, runService.getRun(lease.getRunId()), AiRunDO.STATUS_SUCCEEDED, null, 1, "recovered"))
                    .isTrue();
        }
        for (Long runId : runIds) {
            assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
            assertThat(assistantMessageCount(runId)).as("恢复完成只写一份结果").isEqualTo(1);
        }

        // 业务唯一性：一运行一任务；重启后重放同一幂等键返回原运行，不产生第二个运行/任务
        for (Long runId : runIds) {
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM ai_run_task WHERE run_id = ?", Integer.class, runId))
                    .isEqualTo(1);
        }
        Long conversationId = jdbcTemplate.queryForObject(
                "SELECT conversation_id FROM ai_run WHERE id = ?", Long.class, runIds.get(0));
        AiRunAcceptResultDTO replayed = accept("restart-0", idempotencySuffixes.get(0), conversationId);
        assertThat(replayed.getRunId()).as("重启后同键同请求复用原运行").isEqualTo(runIds.get(0));
        assertThat(replayed.isReused()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run WHERE application_id = ? AND deleted = 0",
                        Integer.class,
                        applicationId))
                .isEqualTo(runCount);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_idempotency WHERE application_id = ? AND deleted = 0",
                        Integer.class,
                        applicationId))
                .isEqualTo(runCount);
        System.out.printf(
                "Q07-MEASURE AT-018 recoveredTasks=%d recoveryScanMs=%d recoveryAttempt=2%n", runCount, recoveryMillis);
    }

    /** AT-018 续：旧 worker 迟到落库被栅栏拒绝，恢复任务的业务结果不会被写第二遍。 */
    @Test
    void at018LateCrashedWorkerCannotDuplicateTheRecoveredResult() {
        preparePublishedService();
        Long runId = acceptRun("late-worker", String.format("%016x", 410));
        AiTaskLeaseDTO crashed = claimTaskOf(runId, "worker-crashed");
        expireLease(crashed.getTaskId());
        assertThat(taskService.recoverExpiredLeases(0, 200)).isEqualTo(1);

        AiTaskLeaseDTO recovered = claimTaskOf(runId, "worker-restarted");
        assertThat(terminalWriter.finish(
                        recovered, runService.getRun(runId), AiRunDO.STATUS_SUCCEEDED, null, 1, "recovered"))
                .isTrue();

        assertThat(taskService.finish(crashed, AiRunTaskDO.STATUS_FAILED, "STALE"))
                .as("旧 worker 的落库命中租约栅栏 0 行（任务已被新代次接管）")
                .isFalse();
        // 旧 worker 走真实执行入口：终态运行在入口处直接拒绝，不重跑模型、不写第二份结果
        assertThatThrownBy(() -> executionService.execute(crashed, AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
        assertThat(runStatus(runId)).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
        assertThat(assistantMessageCount(runId)).isEqualTo(1);
        assertThat(taskStatus(recovered.getTaskId())).isEqualTo(AiRunTaskDO.STATUS_SUCCEEDED);
    }
}
