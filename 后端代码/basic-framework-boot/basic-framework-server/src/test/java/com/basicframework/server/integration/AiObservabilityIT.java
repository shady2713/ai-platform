package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.controller.admin.observability.AiObservabilityController;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorDetailRespVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorPageReqVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorRespVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunTimelineRespVO;
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
import com.basicframework.module.ai.service.usage.AiUsageLedgerService;
import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import java.time.LocalDateTime;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Q03 运行监控端到端（真实 MySQL，写入提交）：筛选分页、失败原因、耗时分解（实测与未计量）、
 * 以及"只允许原任务可重试类型"的重试命令。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AiObservabilityIT.ScopeResolverConfiguration.class)
class AiObservabilityIT extends AbstractPersistenceIntegrationTest {

    /** 与 O06/A06 的 IT 同源：授权判定依赖主体范围解析，测试里给出受控范围（不改生产解析）。 */
    @TestConfiguration
    static class ScopeResolverConfiguration {

        @Bean
        SubjectScopeResolver observabilityScopeResolver() {
            return request -> Optional.of(
                    new SubjectScope(Set.of(10L), Set.of("report-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-observability-app";

    private static final String ENDPOINT_NAME = "it-observability-endpoint";

    private static final String USER_A = "it-observability-user";

    @Autowired
    private AiObservabilityController observabilityController;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiApplicationService applicationService;

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
    private AiUsageLedgerService usageLedgerService;

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
            jdbcTemplate.update("DELETE FROM ai_usage_ledger WHERE application_id = ?", appId);
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
                .setName("IT 运行监控应用")
                .setOrigins(List.of("https://observability.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 运行监控应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, USER_A, "监控用户", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        grantService.createGrant(applicationId, "USER", USER_A, "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-observability.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-observability");
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

    /** 控制器带 @PreAuthorize（管理端权限）；IT 直接调用目标对象，权限策略由单测与框架契约测试覆盖。 */
    private AiObservabilityController target() {
        return AopTestUtils.getTargetObject(observabilityController);
    }

    private void login() {
        String token = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, USER_A, List.of())
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

    private Long acceptRun(String conversationKey, String idempotencyKey) {
        login();
        Long conversationId = conversationService.create(new AiConversationCreateDTO()
                .setConversationKey(conversationKey)
                .setServiceId(serviceId));
        AiRunAcceptResultDTO accepted = runService.accept(new AiRunAcceptDTO()
                .setServiceId(serviceId)
                .setConversationId(conversationId)
                .setIdempotencyKey(idempotencyKey)
                .setMessage("帮我查订单 A-1")
                .setDataLevel("L2_INTERNAL"));
        return accepted.getRunId();
    }

    /** 记录两条模型计量：一条上游报告（有耗时），一条来源未知（没有耗时事实）。 */
    private void recordUsages(Long runId) {
        assertThat(usageLedgerService.record(new AiUsageRecordDTO()
                        .setApplicationId(applicationId)
                        .setDurationMs(800)
                        .setEndpointRef("endpoint:" + 1)
                        .setInputTokens(120L)
                        .setInvocationId("it-obs-inv-reported")
                        .setModelRef("gpt-4o-mini")
                        .setModelRevision(1)
                        .setOutputTokens(40L)
                        .setRunId(runId)
                        .setServiceId(serviceId)
                        .setStatus("SUCCEEDED")
                        .setUsageSource("REPORTED")))
                .as("上游报告的计量必须落账本")
                .isTrue();
        assertThat(usageLedgerService.record(new AiUsageRecordDTO()
                        .setApplicationId(applicationId)
                        .setInvocationId("it-obs-inv-unknown")
                        .setModelRef("gpt-4o-mini")
                        .setRunId(runId)
                        .setServiceId(serviceId)
                        .setStatus("FAILED")
                        .setUsageSource("UNKNOWN")))
                .as("来源未知的计量也要如实落账本（只是没有 token/耗时事实）")
                .isTrue();
    }

    /** 控制器返回 CommonResult：列表断言只关心 PageResult，这里统一拆一层。 */
    private PageResult<AiRunMonitorRespVO> page(AiRunMonitorPageReqVO reqVO) {
        return target().page(reqVO).getData();
    }

    /** 分页 VO 的分页字段继承自 PageParam（返回 PageParam），链式会丢类型：这里统一用语句构造。 */
    private static AiRunMonitorPageReqVO pageReq(
            Long applicationId, int pageNo, int pageSize, java.util.function.Consumer<AiRunMonitorPageReqVO> tweak) {
        AiRunMonitorPageReqVO reqVO = new AiRunMonitorPageReqVO().setApplicationId(applicationId);
        reqVO.setPageNo(pageNo);
        reqVO.setPageSize(pageSize);
        tweak.accept(reqVO);
        return reqVO;
    }

    private static AiRunMonitorPageReqVO pageReq(Long applicationId, int pageNo, int pageSize) {
        return pageReq(applicationId, pageNo, pageSize, reqVO -> {});
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void runListFiltersByServerSideConditionsAndPaginates() {
        preparePublishedService();
        Long runId = acceptRun("conv_obs_page", "it-obs-idem-page-0001");
        eventService.append(runId, "QUEUED", null, null);
        eventService.append(runId, "SUCCEEDED", "table", "{\"rows\":1}");

        PageResult<AiRunMonitorRespVO> byApplication = page(pageReq(applicationId, 1, 10));
        assertThat(byApplication.getTotal()).isEqualTo(1);
        AiRunMonitorRespVO row = byApplication.getList().get(0);
        assertThat(row.getRunId()).isEqualTo(runId);
        assertThat(row.getStatus()).isEqualTo("ACCEPTED");
        assertThat(row.getTaskStatus()).isEqualTo("QUEUED");
        assertThat(row.getLatestSeq()).isEqualTo(2);
        assertThat(row.isRetryable()).as("排队中的任务不需要人工重试").isFalse();
        assertThat(row.getRetryBlockedReason()).contains("QUEUED");

        // 同应用 + 同服务：命中；换一个服务编号：不命中（筛选真的下发到库里）
        assertThat(page(pageReq(applicationId, 1, 10, reqVO -> reqVO.setServiceId(serviceId)))
                        .getTotal())
                .isEqualTo(1);
        assertThat(page(pageReq(applicationId, 1, 10, reqVO -> reqVO.setServiceId(serviceId + 9_999L)))
                        .getTotal())
                .isZero();
        assertThat(page(pageReq(applicationId, 1, 10, reqVO -> reqVO.setSubjectType("USER")))
                        .getTotal())
                .isEqualTo(1);
        assertThat(page(pageReq(applicationId, 1, 10, reqVO -> reqVO.setSubjectType("APP")))
                        .getTotal())
                .isZero();
        // 时间窗外为空：聚合口径不受调用方"顺手放宽"影响
        LocalDateTime now = LocalDateTime.now();
        assertThat(page(pageReq(applicationId, 1, 10, reqVO -> {
                            reqVO.setFrom(now.minusMinutes(1));
                            reqVO.setTo(now.plusMinutes(1));
                        }))
                        .getTotal())
                .isEqualTo(1);
        assertThat(page(pageReq(applicationId, 1, 10, reqVO -> {
                            reqVO.setFrom(now.plusMinutes(1));
                            reqVO.setTo(now.plusMinutes(2));
                        }))
                        .getTotal())
                .isZero();
        // 翻页边界：第二页为空但总数不变
        assertThat(page(pageReq(applicationId, 2, 1)).getList()).isEmpty();

        assertThatThrownBy(() -> target().page(pageReq(applicationId, 1, 10, reqVO -> reqVO.setStatus("NOT_A_STATUS"))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> target().page(pageReq(applicationId, 1, 10, reqVO -> reqVO.setSubjectType("ROBOT"))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> page(pageReq(applicationId, 1, 10, reqVO -> {
                    reqVO.setFrom(now);
                    reqVO.setTo(now.minusMinutes(5));
                })))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void detailSplitsMeasuredFromUnmeasuredAndKeepsUnknownUsageVisible() {
        preparePublishedService();
        Long runId = acceptRun("conv_obs_detail", "it-obs-idem-detail-0001");
        recordUsages(runId);
        eventService.append(runId, "QUEUED", "table", "{\"rows\":1}");

        AiRunMonitorDetailRespVO detail = target().get(runId).getData();
        assertThat(detail.getRunId()).isEqualTo(runId);
        assertThat(detail.getModelEndpointId()).isNotNull();
        assertThat(detail.getEndpointConfigRevision()).isNotNull();
        assertThat(detail.getContentHash()).hasSize(64);
        assertThat(detail.getRunVersion()).isNotNull();
        assertThat(detail.getTaskId()).isNotNull();
        assertThat(detail.getTiming().getModelDurationMs())
                .as("模型耗时是账本实测值之和，不是估算")
                .isEqualTo(800L);
        assertThat(detail.getTiming().getModelInvocationCount()).isEqualTo(2);
        assertThat(detail.getTiming().getUnknownUsageCount())
                .as("来源未知的调用单独计数，不按 0 计入耗时")
                .isEqualTo(1);
        assertThat(detail.getTiming().getRetrievalDurationMs()).isNull();
        assertThat(detail.getTiming().getBusinessApiDurationMs()).isNull();
        assertThat(detail.getTiming().getUnmeasuredStages()).containsExactly("RETRIEVAL", "BUSINESS_API");
        assertThat(detail.getStatus()).isEqualTo("ACCEPTED");
        assertThat(detail.isRetryable()).as("排队中的任务不需要人工重试").isFalse();
        assertThat(detail.getCreateTime()).as("运行受理时间必须可读（总耗时以它为起点）").isNotNull();

        // 终态用库内同源时间构造（update_time = create_time + 5 秒）：耗时不依赖 JVM 与数据库的时钟差
        jdbcTemplate.update(
                "UPDATE ai_run SET status = 'FAILED', update_time = DATE_ADD(create_time, INTERVAL 5 SECOND)"
                        + " WHERE id = ?",
                runId);
        assertThat(target().get(runId).getData().getTiming().getTotalDurationMs())
                .as("总耗时=受理到终态（同源时间差）")
                .isEqualTo(5_000L);

        List<AiRunTimelineRespVO> timeline = target().timeline(runId, null, 100).getData();
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getSeq()).isEqualTo(1);
        assertThat(timeline.get(0).isBlockPresent()).as("事件块正文不进列表接口，只标记有无").isTrue();
        assertThat(timeline.get(0).getBlockType()).isEqualTo("table");

        // 失败后的运行：可重试，且原因码来自任务行（稳定词表）
        jdbcTemplate.update(
                "UPDATE ai_run_task SET status = 'FAILED', attempt_count = 3, last_error_code = 'STEP_BUDGET_EXCEEDED'"
                        + " WHERE run_id = ?",
                runId);
        AiRunMonitorDetailRespVO failed = target().get(runId).getData();
        assertThat(failed.isRetryable()).isTrue();
        assertThat(failed.getLastErrorCode()).isEqualTo("STEP_BUDGET_EXCEEDED");
        assertThat(failed.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void retryOnlyAcceptsRetryableTaskStatusesAndGuardsVersion() {
        preparePublishedService();
        Long runId = acceptRun("conv_obs_retry", "it-obs-idem-retry-0001");
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM ai_run_task WHERE run_id = ?", Long.class, runId);
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);

        // 排队中：不是"可重试类型"，拒绝
        assertThatThrownBy(() -> target().retry(
                                new com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO()
                                        .setRunId(runId)
                                        .setVersion(version)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));

        // 版本不符：拒绝（界面拿过期状态操作会被拦住）
        jdbcTemplate.update("UPDATE ai_run SET status = 'FAILED' WHERE id = ?", runId);
        jdbcTemplate.update("UPDATE ai_run_task SET status = 'FAILED' WHERE id = ?", taskId);
        assertThatThrownBy(() -> target().retry(
                                new com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO()
                                        .setRunId(runId)
                                        .setVersion(version + 100)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));

        // 失败任务 + 正确版本：任务回 QUEUED、运行回 ACCEPTED、尝试次数清零
        Integer current = jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);
        target().retry(new com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO()
                .setRunId(runId)
                .setVersion(current));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId))
                .isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run_task WHERE id = ?", String.class, taskId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM ai_run_task WHERE id = ?", Integer.class, taskId))
                .isZero();

        // 结果未知：拒绝普通重试（重复执行可能产生第二份副作用）
        jdbcTemplate.update("UPDATE ai_run SET status = 'FAILED' WHERE id = ?", runId);
        jdbcTemplate.update("UPDATE ai_run_task SET status = 'UNKNOWN' WHERE id = ?", taskId);
        Integer afterRetry =
                jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);
        AiRunMonitorDetailRespVO unknownDetail = target().get(runId).getData();
        assertThat(unknownDetail.isRetryable()).isFalse();
        assertThat(unknownDetail.getRetryBlockedReason()).contains("结果未知");
        assertThatThrownBy(() -> target().retry(
                                new com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO()
                                        .setRunId(runId)
                                        .setVersion(afterRetry)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));

        // 运行终态成功：也不能重试
        jdbcTemplate.update("UPDATE ai_run_task SET status = 'FAILED' WHERE id = ?", taskId);
        jdbcTemplate.update("UPDATE ai_run SET status = 'SUCCEEDED' WHERE id = ?", runId);
        Integer successVersion =
                jdbcTemplate.queryForObject("SELECT version FROM ai_run WHERE id = ?", Integer.class, runId);
        assertThatThrownBy(() -> target().retry(
                                new com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO()
                                        .setRunId(runId)
                                        .setVersion(successVersion)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));
    }

    @Test
    void unknownRunIsRejectedInsteadOfReturningEmptyDetail() {
        assertThatThrownBy(() -> target().get(Long.MAX_VALUE))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> target().timeline(Long.MAX_VALUE, null, 10))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }
}
