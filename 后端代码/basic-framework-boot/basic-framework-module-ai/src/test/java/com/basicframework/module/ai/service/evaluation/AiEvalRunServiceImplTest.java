package com.basicframework.module.ai.service.evaluation;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RESULT_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RESULT_NOT_REVIEWABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RUN_NOT_EXECUTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RUN_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_HAS_NO_CASE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalCaseMapper;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalResultMapper;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.service.run.AiRunExecutionService;
import com.basicframework.module.ai.service.run.AiRunService;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;
import com.basicframework.module.ai.service.run.dto.AiRunExecutionResultDTO;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 评测执行器（Q04）：执行走同一运行服务、只领取本运行任务、判定走确定性规则、复核只对等复核的结果生效。
 *
 * <p>这里用替身覆盖"有上游能把用例跑完"的分支（IT 环境没有模型端点，只能验证如实失败）。
 */
class AiEvalRunServiceImplTest {

    private final AiEvalSuiteService suiteService = mock(AiEvalSuiteService.class);

    private final AiEvalCaseMapper caseMapper = mock(AiEvalCaseMapper.class);

    private final AiEvalRunMapper runMapper = mock(AiEvalRunMapper.class);

    private final AiEvalResultMapper resultMapper = mock(AiEvalResultMapper.class);

    private final AiRunService runService = mock(AiRunService.class);

    private final AiRunMapper aiRunMapper = mock(AiRunMapper.class);

    private final AiTaskService taskService = mock(AiTaskService.class);

    private final AiRunExecutionService runExecutionService = mock(AiRunExecutionService.class);

    private final AiEvalRunServiceImpl service = new AiEvalRunServiceImpl(
            suiteService,
            caseMapper,
            runMapper,
            resultMapper,
            runService,
            aiRunMapper,
            taskService,
            runExecutionService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiEvalSuiteDO suite() {
        return new AiEvalSuiteDO()
                .setId(7L)
                .setApplicationId(1L)
                .setCode("order-qa-eval")
                .setServiceId(4L)
                .setSubjectType("USER")
                .setExternalUserId("eval-runner")
                .setDataLevel(AiEvalSuiteDO.LEVEL_INTERNAL)
                .setStatus(AiEvalSuiteDO.STATUS_FROZEN)
                .setRevision(2)
                .setVersion(1);
    }

    private static AiEvalCaseDO evalCase(String checks) {
        return new AiEvalCaseDO()
                .setId(11L)
                .setSuiteId(7L)
                .setCaseKey("case_amount")
                .setTitle("金额核对")
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                .setQuestion("上海地区上个月净额是多少？")
                .setExpectVersion("endpoint:3@2")
                .setChecksJson(checks)
                .setNeedsReview(false)
                .setVersion(0);
    }

    @BeforeEach
    void setUp() {
        when(suiteService.requireSuite(7L)).thenReturn(suite());
        when(runMapper.insert(any(AiEvalRunDO.class))).thenAnswer(invocation -> {
            ((AiEvalRunDO) invocation.getArgument(0)).setId(3L);
            return 1;
        });
        when(resultMapper.insert(any(AiEvalResultDO.class))).thenReturn(1);
    }

    @Test
    void startRunExecutesThroughTheSharedRunServiceAndVerifiesRulesProgrammatically() {
        when(caseMapper.selectBySuite(7L))
                .thenReturn(List.of(evalCase("[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"SUCCEEDED\"},"
                        + "{\"kind\":\"VERSION\",\"path\":\"version\",\"expected\":\"endpoint:3@2\"}]")));
        when(runService.accept(any(AiRunAcceptDTO.class))).thenReturn(new AiRunAcceptResultDTO().setRunId(55L));
        when(taskService.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(new AiTaskLeaseDTO().setTaskId(1L).setRunId(55L).setOwner("eval")));
        when(runExecutionService.execute(any(AiTaskLeaseDTO.class), any(AiRunBudget.class)))
                .thenReturn(new AiRunExecutionResultDTO()
                        .setRunId(55L)
                        .setStatus(AiRunDO.STATUS_SUCCEEDED)
                        .setDurationMillis(120)
                        .setToolCalls(0)
                        .setOutputText("净额 450.00"));
        AiRunDO accepted = new AiRunDO().setId(55L).setModelEndpointId(3L).setEndpointConfigRevision(2);
        when(aiRunMapper.selectById(55L)).thenReturn(accepted);

        assertThat(service.startRun(7L)).isEqualTo(3L);

        ArgumentCaptor<AiEvalResultDO> captor = ArgumentCaptor.forClass(AiEvalResultDO.class);
        verify(resultMapper).insert(captor.capture());
        AiEvalResultDO result = captor.getValue();
        assertThat(result.getStatus()).as("执行器可观测到的规则对上时期望为通过").isEqualTo(AiEvalResultDO.STATUS_PASSED);
        assertThat(result.getRunRef()).isEqualTo(55L);
        assertThat(result.getObservedVersion()).isEqualTo("endpoint:3@2");
        assertThat(result.getVerdictJson()).contains("\"kind\":\"VALUE\"").contains("\"passed\":true");
        assertThat(result.getCaseDigest()).hasSize(64);
        assertThat(result.getResultDigest()).hasSize(64);
        assertThat(result.getReviewStatus()).isEqualTo(AiEvalResultDO.REVIEW_NOT_REQUIRED);
        assertThat(result.getVerdictJson()).as("报告里不含模型响应正文，只有摘要").doesNotContain("净额 450.00");

        ArgumentCaptor<AiRunAcceptDTO> acceptCaptor = ArgumentCaptor.forClass(AiRunAcceptDTO.class);
        verify(runService).accept(acceptCaptor.capture());
        assertThat(acceptCaptor.getValue().getServiceId()).isEqualTo(4L);
        assertThat(acceptCaptor.getValue().getDataLevel()).isEqualTo(AiEvalSuiteDO.LEVEL_INTERNAL);
        assertThat(acceptCaptor.getValue().getIdempotencyKey()).isEqualTo("eval-3-case_amount");

        ArgumentCaptor<AiEvalRunDO> runCaptor = ArgumentCaptor.forClass(AiEvalRunDO.class);
        verify(runMapper).updateById(runCaptor.capture());
        assertThat(runCaptor.getValue())
                .as("运行行终态收敛（计数口径：通过=1，失败/错误=0）")
                .extracting(
                        AiEvalRunDO::getStatus,
                        AiEvalRunDO::getPassedCount,
                        AiEvalRunDO::getFailedCount,
                        AiEvalRunDO::getErrorCount)
                .containsExactly(AiEvalRunDO.STATUS_COMPLETED, 1, 0, 0);
    }

    @Test
    void reviewRequiredCasesStayPendingAndFailuresCountAsFailed() {
        when(caseMapper.selectBySuite(7L))
                .thenReturn(List.of(evalCase("[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"SUCCEEDED\"}]")
                        .setNeedsReview(true)));
        when(runService.accept(any(AiRunAcceptDTO.class))).thenReturn(new AiRunAcceptResultDTO().setRunId(55L));
        when(taskService.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(new AiTaskLeaseDTO().setTaskId(1L).setRunId(55L).setOwner("eval")));
        when(runExecutionService.execute(any(AiTaskLeaseDTO.class), any(AiRunBudget.class)))
                .thenReturn(new AiRunExecutionResultDTO()
                        .setRunId(55L)
                        .setStatus("SUCCEEDED")
                        .setDurationMillis(10));

        service.startRun(7L);
        ArgumentCaptor<AiEvalResultDO> captor = ArgumentCaptor.forClass(AiEvalResultDO.class);
        verify(resultMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiEvalResultDO.STATUS_REVIEW_REQUIRED);
        assertThat(captor.getValue().getReviewStatus()).isEqualTo(AiEvalResultDO.REVIEW_PENDING);

        ArgumentCaptor<AiEvalRunDO> runCaptor = ArgumentCaptor.forClass(AiEvalRunDO.class);
        verify(runMapper).updateById(runCaptor.capture());
        assertThat(runCaptor.getValue().getPassedCount()).as("待复核不计入通过").isZero();
        assertThat(runCaptor.getValue().getFailedCount()).isEqualTo(1);
    }

    @Test
    void rulesOverUnobservableFactsFailInsteadOfBeingSkipped() {
        when(caseMapper.selectBySuite(7L))
                .thenReturn(List.of(evalCase("[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"450.00\"}]")));
        when(runService.accept(any(AiRunAcceptDTO.class))).thenReturn(new AiRunAcceptResultDTO().setRunId(55L));
        when(taskService.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(new AiTaskLeaseDTO().setTaskId(1L).setRunId(55L).setOwner("eval")));
        when(runExecutionService.execute(any(AiTaskLeaseDTO.class), any(AiRunBudget.class)))
                .thenReturn(new AiRunExecutionResultDTO()
                        .setRunId(55L)
                        .setStatus("SUCCEEDED")
                        .setDurationMillis(10));

        service.startRun(7L);

        ArgumentCaptor<AiEvalResultDO> captor = ArgumentCaptor.forClass(AiEvalResultDO.class);
        verify(resultMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus())
                .as("执行器观测不到金额事实时必须判失败，不能跳过规则")
                .isEqualTo(AiEvalResultDO.STATUS_FAILED);
        assertThat(captor.getValue().getVerdictJson()).contains("\"observed\":\"<缺失>\"");
    }

    @Test
    void executionFailuresAreRecordedAsErrorsWithStableCodes() {
        when(caseMapper.selectBySuite(7L))
                .thenReturn(List.of(evalCase("[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"450.00\"}]")));
        when(runService.accept(any(AiRunAcceptDTO.class)))
                .thenThrow(new ServiceException(AI_SERVICE_RELEASE_NOT_PUBLISHED));

        service.startRun(7L);

        ArgumentCaptor<AiEvalResultDO> captor = ArgumentCaptor.forClass(AiEvalResultDO.class);
        verify(resultMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiEvalResultDO.STATUS_ERROR);
        assertThat(captor.getValue().getFailureCode())
                .isEqualTo(String.valueOf(AI_SERVICE_RELEASE_NOT_PUBLISHED.getCode()));
        assertThat(captor.getValue().getVerdictJson()).isNull();

        ArgumentCaptor<AiEvalRunDO> runCaptor = ArgumentCaptor.forClass(AiEvalRunDO.class);
        verify(runMapper).updateById(runCaptor.capture());
        assertThat(runCaptor.getValue().getErrorCount()).isEqualTo(1);
    }

    @Test
    void foreignTasksAreNotExecutedAndMissingOwnLeaseIsReported() {
        when(caseMapper.selectBySuite(7L))
                .thenReturn(List.of(evalCase("[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"450.00\"}]")));
        when(runService.accept(any(AiRunAcceptDTO.class))).thenReturn(new AiRunAcceptResultDTO().setRunId(55L));
        // 队列里只有别人的任务：压短租约后不再重试，最后如实记为 ERROR
        when(taskService.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(new AiTaskLeaseDTO().setTaskId(9L).setRunId(99L).setOwner("other")))
                .thenReturn(List.of());

        service.startRun(7L);

        verify(runExecutionService, never()).execute(any(AiTaskLeaseDTO.class), any(AiRunBudget.class));
        verify(taskService).heartbeat(any(AiTaskLeaseDTO.class), anyInt());
        ArgumentCaptor<AiEvalResultDO> captor = ArgumentCaptor.forClass(AiEvalResultDO.class);
        verify(resultMapper).insert(captor.capture());
        assertThat(captor.getValue().getFailureCode()).isEqualTo(String.valueOf(AI_EVAL_RUN_NOT_EXECUTED.getCode()));
    }

    @Test
    void queriesReportsAndReviewsHaveExplicitBoundaries() {
        when(caseMapper.selectBySuite(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.startRun(7L))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_HAS_NO_CASE));

        when(runMapper.selectPage(
                        org.mockito.ArgumentMatchers.any(PageParam.class),
                        org.mockito.ArgumentMatchers.nullable(Long.class)))
                .thenReturn(new PageResult<>(new ArrayList<>(), 0L));
        assertThat(service.pageRuns(new PageParam(), 7L).getTotal()).isZero();

        when(runMapper.selectById(3L)).thenReturn(runRow());
        when(resultMapper.selectByRun(3L)).thenReturn(List.of(resultRow()));
        assertThat(service.report(3L)).contains("\"cases\"").contains("\"caseKey\":\"case_amount\"");
        assertThat(service.listResults(3L)).hasSize(1);

        when(resultMapper.selectPage(
                        org.mockito.ArgumentMatchers.any(PageParam.class),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PageResult<>(List.of(), 0L));
        assertThat(service.pageResults(new PageParam(), 3L, AiEvalResultDO.STATUS_ERROR)
                        .getTotal())
                .isZero();
        assertThatThrownBy(() -> service.pageResults(new PageParam(), 3L, "UNKNOWN_STATUS"))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));

        assertThatThrownBy(() -> service.requireRun(null))
                .satisfies(exception -> assertCode(exception, AI_EVAL_RUN_NOT_EXISTS));
        assertThatThrownBy(() -> service.requireResult(null))
                .satisfies(exception -> assertCode(exception, AI_EVAL_RESULT_NOT_EXISTS));

        when(resultMapper.selectById(9L)).thenReturn(resultRow());
        assertThatThrownBy(() -> service.review(9L, true, "x".repeat(513)))
                .as("备注超长被拒绝")
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));
        when(resultMapper.selectById(9L)).thenReturn(resultRow().setReviewStatus(AiEvalResultDO.REVIEW_NOT_REQUIRED));
        assertThatThrownBy(() -> service.review(9L, true, null))
                .satisfies(exception -> assertCode(exception, AI_EVAL_RESULT_NOT_REVIEWABLE));

        when(resultMapper.selectById(9L)).thenReturn(resultRow());
        service.review(9L, false, "金额口径不对");
        ArgumentCaptor<AiEvalResultDO> captor = ArgumentCaptor.forClass(AiEvalResultDO.class);
        verify(resultMapper).updateById(captor.capture());
        assertThat(captor.getValue().getReviewStatus()).isEqualTo(AiEvalResultDO.REVIEW_REJECTED);
        assertThat(captor.getValue().getStatus()).isEqualTo(AiEvalResultDO.STATUS_FAILED);
        assertThat(captor.getValue().getReviewedTime()).isNotNull();
        assertThat(captor.getValue().getResultDigest()).hasSize(64);
        verify(resultMapper, never()).insert(any(AiEvalResultDO.class));
        verify(aiRunMapper, never()).selectById(anyLong());
        verify(taskService, never()).claim(anyString(), anyInt(), anyInt());
    }

    private static AiEvalRunDO runRow() {
        AiEvalRunDO run = new AiEvalRunDO()
                .setApplicationId(1L)
                .setCaseTotal(1)
                .setErrorCount(0)
                .setFailedCount(0)
                .setId(3L)
                .setPassedCount(1)
                .setServiceId(4L)
                .setStatus(AiEvalRunDO.STATUS_COMPLETED)
                .setSuiteDigest("a".repeat(64))
                .setSuiteId(7L)
                .setSuiteRevision(2);
        run.setStartedTime(java.time.LocalDateTime.now().minusMinutes(1));
        run.setFinishedTime(java.time.LocalDateTime.now());
        return run;
    }

    private static AiEvalResultDO resultRow() {
        return new AiEvalResultDO()
                .setCaseDigest("b".repeat(64))
                .setCaseKey("case_amount")
                .setId(9L)
                .setResultDigest("c".repeat(64))
                .setReviewStatus(AiEvalResultDO.REVIEW_PENDING)
                .setRunId(3L)
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                .setStatus(AiEvalResultDO.STATUS_REVIEW_REQUIRED)
                .setVerdictJson("[{\"kind\":\"MONEY\",\"passed\":true}]");
    }
}
