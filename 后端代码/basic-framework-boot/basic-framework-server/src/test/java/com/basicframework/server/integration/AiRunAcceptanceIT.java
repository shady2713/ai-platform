package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunIdempotencyDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunIdempotencyMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
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
 * O02 运行受理端到端（真实 MySQL）：幂等复用、同键异摘要 409、受理即固定版本、
 * 事务内建立幂等记录/运行/首任务、越权与不存在同语义、响应不含正文。
 */
@Import(AiRunAcceptanceIT.ResolverConfiguration.class)
class AiRunAcceptanceIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver runScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-run-app";

    private static final String ENDPOINT_NAME = "it-run-endpoint";

    private static final String USER_A = "it-run-user-a";

    private static final String USER_B = "it-run-user-b";

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
    private AiConversationService conversationService;

    @Autowired
    private AiRunService runService;

    @Autowired
    private AiRunMapper runMapper;

    @Autowired
    private AiRunIdempotencyMapper idempotencyMapper;

    @Autowired
    private AiRunTaskMapper taskMapper;

    private Long applicationId;

    private String applicationSecret;

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

    private void prepareApplicationWithSubjects() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 运行应用")
                .setOrigins(List.of("https://run.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 运行应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "运行用户 A", "it-scope", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_B, "运行用户 B", "it-scope", 1L);
    }

    private void loginAs(String externalUserId) {
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

    /** 建好一个已发布服务（含 APP 授权），返回服务编号与发布版本编号。 */
    private long[] preparePublishedService() {
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-run.example.com/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-run");
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

        Long serviceId = serviceService.createDraft(new AiServiceSaveDTO()
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
        return new long[] {serviceId, releaseId};
    }

    private static AiRunAcceptDTO acceptDTO(Long serviceId, Long conversationId) {
        return new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationId)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .setMessage("帮我查订单 A-1")
                .setAttachmentKeys(List.of("file-a", "file-b"))
                .setBusinessContext("{\"page\":\"order\"}")
                .setDataLevel("L2_INTERNAL");
    }

    private int countRuns() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE application_id = ? AND deleted = 0", Integer.class, applicationId);
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void acceptanceCreatesRunIdempotencyAndFirstTaskAndPinsTheVersion() {
        prepareApplicationWithSubjects();
        long[] published = preparePublishedService();
        loginAs(USER_A);
        Long conversationId = conversationService.create(
                new AiConversationCreateDTO().setConversationKey("conv_run").setServiceId(published[0]));

        AiRunAcceptResultDTO first = runService.accept(acceptDTO(published[0], conversationId));

        assertThat(first.getRunId()).isNotNull();
        assertThat(first.getRunKey()).startsWith("run_");
        assertThat(first.getStatus()).isEqualTo("ACCEPTED");
        assertThat(first.isReused()).isFalse();
        assertThat(first.getReleaseId()).isEqualTo(published[1]);

        // 受理即固定版本：会话写入 releaseId，后续消息沿用该版本
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT release_id FROM ai_conversation WHERE id = ?", Long.class, conversationId))
                .isEqualTo(published[1]);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT release_id FROM ai_run WHERE id = ?", Long.class, first.getRunId()))
                .isEqualTo(published[1]);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT endpoint_config_revision FROM ai_run WHERE id = ?", Integer.class, first.getRunId()))
                .isPositive();

        // 幂等记录与首任务同事务建立：任务只保存载荷摘要
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT request_digest FROM ai_run_idempotency WHERE run_id = ?",
                        String.class,
                        first.getRunId()))
                .hasSize(64);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_task WHERE run_id = ? AND task_kind = 'RUN_STEP' AND status ="
                                + " 'QUEUED' AND attempt_count = 0",
                        Integer.class,
                        first.getRunId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT payload_digest FROM ai_run_task WHERE run_id = ?", String.class, first.getRunId()))
                .as("队列不保存正文")
                .hasSize(64);

        // 幂等复用：同键同正文返回原运行，不新建行、不重新发起调用
        AiRunAcceptResultDTO reused = runService.accept(acceptDTO(published[0], conversationId));
        assertThat(reused.isReused()).isTrue();
        assertThat(reused.getRunId()).isEqualTo(first.getRunId());
        assertThat(countRuns()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_task WHERE run_id = ?", Integer.class, first.getRunId()))
                .isEqualTo(1);

        // 附件顺序不同但集合相同：仍是同一请求
        AiRunAcceptResultDTO reordered = runService.accept(
                acceptDTO(published[0], conversationId).setAttachmentKeys(List.of("file-b", "file-a")));
        assertThat(reordered.getRunId()).isEqualTo(first.getRunId());
        assertThat(reordered.isReused()).isTrue();
        assertThat(countRuns()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIsRejectedWithoutCreatingAnything() {
        prepareApplicationWithSubjects();
        long[] published = preparePublishedService();
        loginAs(USER_A);
        Long conversationId = conversationService.create(new AiConversationCreateDTO()
                .setConversationKey("conv_conflict")
                .setServiceId(published[0]));
        AiRunAcceptResultDTO first = runService.accept(acceptDTO(published[0], conversationId));

        assertThatThrownBy(() -> runService.accept(
                        acceptDTO(published[0], conversationId).setMessage("换一个完全不同的问题")))
                .as("同一幂等键提交不同请求必须 409")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT));

        // 事务失败无半成品：冲突后行数不变
        assertThat(countRuns()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_idempotency WHERE application_id = ? AND deleted = 0",
                        Integer.class,
                        applicationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_run_task WHERE run_id = ?", Integer.class, first.getRunId()))
                .isEqualTo(1);
    }

    @Test
    void runsAreScopedToTheResolvedSubject() {
        prepareApplicationWithSubjects();
        long[] published = preparePublishedService();
        loginAs(USER_A);
        AiRunAcceptResultDTO run = runService.accept(acceptDTO(published[0], null));

        assertThat(runService.getRun(run.getRunId()).getRunKey()).isEqualTo(run.getRunKey());
        assertThat(runService
                        .getPage(new com.basicframework.framework.common.pojo.PageParam())
                        .getList())
                .hasSize(1);

        loginAs(USER_B);
        assertThatThrownBy(() -> runService.getRun(run.getRunId()))
                .as("越权与不存在同语义")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThat(runService
                        .getPage(new com.basicframework.framework.common.pojo.PageParam())
                        .getList())
                .isEmpty();
    }

    @Test
    void runMappersScopeBySubjectAndGuardWithOptimisticLock() {
        prepareApplicationWithSubjects();
        long[] published = preparePublishedService();
        loginAs(USER_A);
        AiRunAcceptResultDTO accepted = runService.accept(acceptDTO(published[0], null));
        Long runId = accepted.getRunId();

        // 主体过滤：他人主体查不到该运行（越权与不存在同语义）
        assertThat(runMapper.selectOwned(runId, applicationId, "USER", USER_A)).isNotNull();
        assertThat(runMapper.selectOwned(runId, applicationId, "USER", USER_B)).isNull();
        assertThat(runMapper.selectOwned(runId, applicationId + 1, "USER", USER_A))
                .isNull();
        assertThat(runMapper
                        .selectPageBySubject(
                                new com.basicframework.framework.common.pojo.PageParam(), applicationId, "USER", USER_A)
                        .getList())
                .extracting(AiRunDO::getId)
                .containsExactly(runId);
        assertThat(runMapper
                        .selectPageBySubject(
                                new com.basicframework.framework.common.pojo.PageParam(), applicationId, "USER", USER_B)
                        .getList())
                .isEmpty();

        // 乐观锁：旧版本不能覆盖新状态（并发写入的串行化点）
        assertThat(runMapper.updateWithVersion(
                        new AiRunDO()
                                .setId(runId)
                                .setStatus(AiRunDO.STATUS_RUNNING)
                                .setVersion(1),
                        0))
                .isEqualTo(1);
        assertThat(runMapper.updateWithVersion(
                        new AiRunDO()
                                .setId(runId)
                                .setStatus(AiRunDO.STATUS_FAILED)
                                .setVersion(2),
                        0))
                .as("携带过期版本号的更新不生效")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId))
                .isEqualTo(AiRunDO.STATUS_RUNNING);

        // 首任务：按运行 + 类型定位，重试等待列可 CAS 推进
        AiRunTaskDO task = taskMapper.selectByRunAndKind(runId, AiRunTaskDO.KIND_RUN_STEP);
        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(AiRunTaskDO.STATUS_QUEUED);
        assertThat(taskMapper.updateWithVersion(
                        new AiRunTaskDO()
                                .setId(task.getId())
                                .setStatus(AiRunTaskDO.STATUS_RUNNING)
                                .setAttemptCount(1)
                                .setNextAttemptTime(java.time.LocalDateTime.now())
                                .setVersion(1),
                        0))
                .isEqualTo(1);
        assertThat(taskMapper.updateWithVersion(
                        new AiRunTaskDO()
                                .setId(task.getId())
                                .setStatus(AiRunTaskDO.STATUS_FAILED)
                                .setVersion(2),
                        0))
                .isZero();
        assertThat(taskMapper
                        .selectByRunAndKind(runId, AiRunTaskDO.KIND_RUN_STEP)
                        .getAttemptCount())
                .isEqualTo(1);

        // 幂等记录：按主体 + 幂等键定位，同样按版本 CAS
        AiRunIdempotencyDO record = idempotencyMapper.selectByKey(applicationId, "USER", USER_A, IDEMPOTENCY_KEY);
        assertThat(record.getRunId()).isEqualTo(runId);
        assertThat(idempotencyMapper.selectByKey(applicationId, "USER", USER_B, IDEMPOTENCY_KEY))
                .as("幂等键按主体隔离：另一个主体用同一个键仍可受理自己的运行")
                .isNull();
        assertThat(idempotencyMapper.updateWithVersion(
                        new AiRunIdempotencyDO().setId(record.getId()).setVersion(1), 0))
                .isEqualTo(1);
        assertThat(idempotencyMapper.updateWithVersion(
                        new AiRunIdempotencyDO().setId(record.getId()).setVersion(2), 0))
                .isZero();
    }

    @Test
    void acceptanceRequiresTheConversationToBelongToTheSameService() {
        prepareApplicationWithSubjects();
        long[] published = preparePublishedService();
        loginAs(USER_A);
        Long conversationId = conversationService.create(
                new AiConversationCreateDTO().setConversationKey("conv_other").setServiceId(published[0]));

        // 会话已固定版本后，受理请求的服务必须与会话一致
        runService.accept(acceptDTO(published[0], conversationId));
        assertThatThrownBy(() -> runService.accept(acceptDTO(published[0] + 999, conversationId)))
                .as("不能用一个服务受理另一个服务固定的会话")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
    }
}
