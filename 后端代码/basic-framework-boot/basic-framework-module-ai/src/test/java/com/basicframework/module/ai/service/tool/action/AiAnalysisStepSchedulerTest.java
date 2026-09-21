package com.basicframework.module.ai.service.tool.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.action.AiRunStepCounterMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepRequestDTO;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepResultDTO;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** D09 分析步骤调度：运行活跃性（取消后不再执行）、步数与耗时预算、AUTO/CONFIRM 分发。 */
class AiAnalysisStepSchedulerTest {

    private static final Long RUN_ID = 401L;

    private static final Long APPLICATION_ID = 11L;

    private static final String SUBJECT_TYPE = "USER";

    private static final String EXTERNAL_USER = "u-001";

    private static final String TOOL_CODE = "query-orders";

    private final AiRunStepCounterMapper stepCounterMapper = mock(AiRunStepCounterMapper.class);

    private final AiToolPolicyGate policyGate = mock(AiToolPolicyGate.class);

    private final AiToolExecutor toolExecutor = mock(AiToolExecutor.class);

    private final AiToolActionService actionService = mock(AiToolActionService.class);

    private final AiToolService toolService = mock(AiToolService.class);

    private final AiToolMapper toolMapper = mock(AiToolMapper.class);

    private final AiAnalysisStepScheduler scheduler = new AiAnalysisStepScheduler(
            stepCounterMapper, policyGate, toolExecutor, actionService, toolService, toolMapper);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiToolVersionDO version(String policy) {
        return new AiToolVersionDO()
                .setId(101L)
                .setToolId(91L)
                .setVersionNo(1)
                .setStatus(AiToolVersionDO.STATUS_PUBLISHED)
                .setToolType("READ")
                .setPolicy(policy)
                .setSourceRef("getOrders")
                .setInputSchemaJson("{\"region\":{\"type\":\"string\",\"required\":true}}")
                .setOutputSchemaJson("{\"columns\":[]}")
                .setSchemaHash("a".repeat(64))
                .setVersion(1);
    }

    private static AiAnalysisStepRequestDTO request() {
        return new AiAnalysisStepRequestDTO()
                .setRunId(RUN_ID)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType(SUBJECT_TYPE)
                .setExternalUserId(EXTERNAL_USER)
                .setToolCode(TOOL_CODE)
                .setArguments(Map.of("region", "EAST"));
    }

    private void runIs(String status, int stepCount) {
        when(stepCounterMapper.selectRun(RUN_ID))
                .thenReturn(new AiRunStepCounterMapper.AiRunStepRow(status, stepCount, 0L));
    }

    @BeforeEach
    void setUp() {
        runIs("RUNNING", 0);
        when(stepCounterMapper.incrementIfActive(RUN_ID)).thenReturn(1);
        when(toolService.requirePublishedVersion(TOOL_CODE)).thenReturn(version("CONFIRM"));
        when(toolMapper.selectById(91L))
                .thenReturn(new AiToolDO()
                        .setId(91L)
                        .setCode(TOOL_CODE)
                        .setConnectorId(71L)
                        .setVersion(1));
    }

    @Test
    void executesAutoPolicyStepAndCountsIt() {
        when(policyGate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .thenReturn(new AiToolDecision(
                        AiToolDecision.Outcome.EXECUTE, version("AUTO"), 71L, "getOrders", Map.of("region", "EAST")));
        when(toolExecutor.execute(any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("COMPLETE")
                        .setStoppedReason("no-more-pages"));

        AiAnalysisStepResultDTO result = scheduler.executeStep(request());

        assertThat(result.getOutcome()).isEqualTo(AiAnalysisStepResultDTO.OUTCOME_EXECUTED);
        assertThat(result.getStepsUsed()).isEqualTo(1);
        assertThat(result.getSourceStatus()).isEqualTo("COMPLETE");
        verify(stepCounterMapper).incrementIfActive(RUN_ID);
    }

    @Test
    void createsPendingActionForConfirmPolicyWithoutExecuting() {
        when(policyGate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED));
        when(actionService.createFromDecision(
                        any(), eq(RUN_ID), eq(APPLICATION_ID), eq(SUBJECT_TYPE), eq(EXTERNAL_USER)))
                .thenReturn(new AiToolActionDO()
                        .setId(501L)
                        .setChallenge("c".repeat(32))
                        .setExpiresAt(LocalDateTime.now().plusMinutes(15)));

        AiAnalysisStepResultDTO result = scheduler.executeStep(request());

        assertThat(result.getOutcome()).isEqualTo(AiAnalysisStepResultDTO.OUTCOME_AWAITING_CONFIRMATION);
        assertThat(result.getActionId()).isEqualTo(501L);
        assertThat(result.getChallenge()).hasSize(32);
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void refusesStepsAfterCancelAndWhenBudgetExhausted() {
        // 取消/终态：运行不再活跃（原子占用也会失败）
        runIs("CANCELLED", 1);
        assertThatThrownBy(() -> scheduler.executeStep(request()))
                .as("取消后不再执行后续步骤（AT-016）")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_RUN_NOT_ACTIVE));
        verify(toolExecutor, never()).execute(any());
        verify(policyGate, never()).decide(any(), any());

        // 运行不存在
        when(stepCounterMapper.selectRun(RUN_ID)).thenReturn(null);
        assertThatThrownBy(() -> scheduler.executeStep(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_RUN_NOT_ACTIVE));

        // 步数预算用尽
        runIs("RUNNING", AiRunBudget.DEFAULT_MAX_STEPS);
        assertThatThrownBy(() -> scheduler.executeStep(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_ANALYSIS_STEP_LIMIT_EXCEEDED));
        verify(stepCounterMapper, never()).incrementIfActive(RUN_ID);

        // 耗时预算用尽
        when(stepCounterMapper.selectRun(RUN_ID))
                .thenReturn(new AiRunStepCounterMapper.AiRunStepRow("RUNNING", 1, 3_600_000L));
        assertThatThrownBy(() -> scheduler.executeStep(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_ANALYSIS_STEP_LIMIT_EXCEEDED));

        // 原子占用失败（运行在检查后被取消）→ 拒绝
        runIs("RUNNING", 1);
        when(stepCounterMapper.incrementIfActive(RUN_ID)).thenReturn(0);
        assertThatThrownBy(() -> scheduler.executeStep(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_RUN_NOT_ACTIVE));
    }

    @Test
    void propagatesPolicyDenialsAndValidatesRequests() {
        when(policyGate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));
        assertThatThrownBy(() -> scheduler.executeStep(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));

        for (AiAnalysisStepRequestDTO invalid : java.util.List.of(
                new AiAnalysisStepRequestDTO(),
                new AiAnalysisStepRequestDTO()
                        .setRunId(RUN_ID)
                        .setApplicationId(APPLICATION_ID)
                        .setToolCode(TOOL_CODE),
                new AiAnalysisStepRequestDTO()
                        .setRunId(RUN_ID)
                        .setApplicationId(APPLICATION_ID)
                        .setSubjectType(SUBJECT_TYPE)
                        .setToolCode("  "))) {
            assertThatThrownBy(() -> scheduler.executeStep(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        }
    }
}
