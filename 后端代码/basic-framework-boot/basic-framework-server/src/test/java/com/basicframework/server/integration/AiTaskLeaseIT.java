package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.job.AiTaskRecoveryJob;
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
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
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
 * O03 任务领取与租约端到端（真实 MySQL，写入提交）：CAS 领取不重复、心跳与落库的领取代次栅栏、
 * 租约过期后恢复（模拟进程中断）、达到重试上限置 FAILED、worker 重建可信身份。
 *
 * <p>本类关闭测试事务：租约过期与并发领取必须看到彼此**已提交**的写入，否则栅栏语义无法被真实验证。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiTaskLeaseIT.ResolverConfiguration.class)
class AiTaskLeaseIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver taskLeaseScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-task-app";

    private static final String ENDPOINT_NAME = "it-task-endpoint";

    private static final String USER_A = "it-task-user";

    private static final String IDEMPOTENCY_KEY = "idem-0123456789abcdef";

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
                .setName("IT 任务应用")
                .setOrigins(List.of("https://task.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 任务应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "任务用户", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-task.example.com/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-task");
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

    /** 受理一个运行，返回其首任务编号。 */
    private Long acceptRunAndGetTaskId() {
        loginOnCurrentThread();
        AiRunAcceptResultDTO accepted = runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .setMessage("帮我查订单 A-1")
                .setDataLevel("L2_INTERNAL"));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ai_run_task WHERE run_id = ? AND task_kind = 'RUN_STEP'",
                Long.class,
                accepted.getRunId());
    }

    private String taskStatus(Long taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ai_run_task WHERE id = ?", String.class, taskId);
    }

    private int taskAttempts(Long taskId) {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM ai_run_task WHERE id = ?", Integer.class, taskId);
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

    @Test
    void claimIsExclusiveWhileTheLeaseIsValid() {
        preparePublishedService();
        Long taskId = acceptRunAndGetTaskId();

        List<AiTaskLeaseDTO> first = taskService.claim("worker-a", 10, 60);
        assertThat(first).singleElement().satisfies(lease -> {
            assertThat(lease.getTaskId()).isEqualTo(taskId);
            assertThat(lease.getEpoch()).isEqualTo(1);
            assertThat(lease.getAttempt()).isEqualTo(1);
        });
        assertThat(taskStatus(taskId)).isEqualTo(AiRunTaskDO.STATUS_RUNNING);
        assertThat(taskService.countActiveLeases()).isEqualTo(1);

        // 有效租约期内第二个 worker 领不到（不重复领取）
        assertThat(taskService.claim("worker-b", 10, 60)).isEmpty();
        assertThat(taskAttempts(taskId)).as("未被领取时不消耗尝试次数").isEqualTo(1);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameTaskTwice() throws Exception {
        preparePublishedService();
        Long taskId = acceptRunAndGetTaskId();
        workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<AiTaskLeaseDTO>>> futures = new java.util.ArrayList<>();
        for (String owner : List.of("worker-a", "worker-b")) {
            futures.add(workers.submit((Callable<List<AiTaskLeaseDTO>>) () -> {
                loginOnCurrentThread();
                start.await(10, TimeUnit.SECONDS);
                return taskService.claim(owner, 10, 60);
            }));
        }
        start.countDown();

        int claimedTotal = 0;
        for (Future<List<AiTaskLeaseDTO>> future : futures) {
            claimedTotal += future.get(30, TimeUnit.SECONDS).size();
        }

        assertThat(claimedTotal).as("两个 worker 并发领取同一个任务时只有一个成功").isEqualTo(1);
        assertThat(taskAttempts(taskId)).isEqualTo(1);
        assertThat(taskService.countActiveLeases()).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsRecoveredAndLateWorkerCannotOverwriteTheNewOne() {
        preparePublishedService();
        Long taskId = acceptRunAndGetTaskId();

        // worker-a 领取后"进程中断"（不落库、不心跳），租约过期
        AiTaskLeaseDTO first = taskService.claim("worker-a", 10, 1).get(0);
        expireLease(taskId);
        assertThat(taskService.countActiveLeases()).isZero();

        // 恢复 Job 把任务放回待领取（未达重试上限）
        AiTaskRecoveryJob recoveryJob = new AiTaskRecoveryJob(taskService, 0, 200);
        assertThat(recoveryJob.execute("")).contains("1");
        assertThat(taskStatus(taskId)).isEqualTo(AiRunTaskDO.STATUS_QUEUED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT lease_owner FROM ai_run_task WHERE id = ?", String.class, taskId))
                .isNull();

        // worker-b 接管：代次递增
        AiTaskLeaseDTO second = taskService.claim("worker-b", 10, 60).get(0);
        assertThat(second.getEpoch()).isEqualTo(2);
        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(taskService.heartbeat(second, 60)).isTrue();
        assertThat(taskService.finish(second, AiRunTaskDO.STATUS_SUCCEEDED, null))
                .isTrue();
        assertThat(taskStatus(taskId)).isEqualTo(AiRunTaskDO.STATUS_SUCCEEDED);

        // 迟到的 worker-a：既不能续租也不能覆盖终态
        assertThat(taskService.heartbeat(first, 60)).as("旧代次无法续租").isFalse();
        assertThat(taskService.finish(first, AiRunTaskDO.STATUS_FAILED, "TIMEOUT"))
                .as("旧 worker 迟到的落库不生效")
                .isFalse();
        assertThat(taskStatus(taskId)).isEqualTo(AiRunTaskDO.STATUS_SUCCEEDED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT last_error_code FROM ai_run_task WHERE id = ?", String.class, taskId))
                .isNull();
    }

    @Test
    void taskIsFailedWhenAttemptsReachTheLimit() {
        preparePublishedService();
        Long taskId = acceptRunAndGetTaskId();
        jdbcTemplate.update("UPDATE ai_run_task SET max_attempts = 2 WHERE id = ?", taskId);

        // 第一次：领取后租约过期 → 恢复回 QUEUED（尝试次数 1 < 上限 2）
        AiTaskLeaseDTO first = taskService.claim("worker-a", 10, 1).get(0);
        expireLease(taskId);
        assertThat(taskService.recoverExpiredLeases(0, 200)).isEqualTo(1);
        assertThat(taskStatus(taskId)).isEqualTo(AiRunTaskDO.STATUS_QUEUED);
        assertThat(first.getEpoch()).isEqualTo(1);

        // 第二次：再次领取后租约过期 → 尝试次数达到上限，置 FAILED 且不再回到队列
        AiTaskLeaseDTO second = taskService.claim("worker-a", 10, 1).get(0);
        assertThat(second.getAttempt()).isEqualTo(2);
        expireLease(taskId);
        assertThat(taskService.recoverExpiredLeases(0, 200)).isEqualTo(1);
        assertThat(taskStatus(taskId)).as("达到重试上限的任务不再回队列").isEqualTo(AiRunTaskDO.STATUS_FAILED);
        assertThat(taskService.claim("worker-c", 10, 60)).isEmpty();
    }

    @Test
    void workerRebuildsIdentityFromServerSideFacts() {
        preparePublishedService();
        Long taskId = acceptRunAndGetTaskId();
        Long runId = jdbcTemplate.queryForObject("SELECT run_id FROM ai_run_task WHERE id = ?", Long.class, taskId);

        var context = taskService.rebuildIdentity(runId);
        assertThat(context.applicationId()).isEqualTo(applicationId);
        assertThat(context.externalUserId()).isEqualTo(USER_A);
        assertThat(context.isDeny()).isFalse();

        // 主体被撤销后：重建身份直接失败，worker 不得继续执行受限步骤
        subjectService.disableSubject(applicationId, AiSubjectType.USER, USER_A);
        assertThatThrownBy(() -> taskService.rebuildIdentity(runId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
    }
}
