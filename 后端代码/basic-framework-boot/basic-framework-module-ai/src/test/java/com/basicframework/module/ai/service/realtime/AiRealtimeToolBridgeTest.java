package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_CALL_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_IN_PROGRESS_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_POLICY_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_POLICY_DENIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 会话内工具执行桥（X05 单测）：只执行免确认读工具、执行权单赢家、终态重放返回既有结论。
 */
@ExtendWith(MockitoExtension.class)
class AiRealtimeToolBridgeTest {

    private static final Long SESSION_ID = 42L;

    private static final Long CALL_ID = 9L;

    @Mock
    private AiRealtimeToolCallMapper toolCallMapper;

    @Mock
    private AiRealtimeEventApplier eventApplier;

    @Mock
    private AiToolPolicyGate policyGate;

    @Mock
    private AiToolExecutor toolExecutor;

    @InjectMocks
    private AiRealtimeToolBridge bridge;

    @Test
    void settledCallsAreReturnedWithoutSecondExecution() {
        AiRealtimeToolCallDO executed = call(AiRealtimeToolCallDO.STATUS_EXECUTED);

        assertThat(bridge.execute(session(), executed)).isSameAs(executed);
        verify(toolCallMapper, never()).claimExecution(any());
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void lostClaimOnSettledCallReturnsRecordedConclusion() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(0);
        when(toolCallMapper.selectById(CALL_ID)).thenReturn(call(AiRealtimeToolCallDO.STATUS_EXECUTED));

        assertThat(bridge.execute(session(), proposed).getStatus()).isEqualTo(AiRealtimeToolCallDO.STATUS_EXECUTED);
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void lostClaimOnRunningOrMissingCallIsRejected() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(0);
        when(toolCallMapper.selectById(CALL_ID)).thenReturn(call(AiRealtimeToolCallDO.STATUS_EXECUTING));
        assertCode(() -> bridge.execute(session(), proposed), AI_REALTIME_TOOL_IN_PROGRESS_CONFLICT);

        when(toolCallMapper.selectById(CALL_ID)).thenReturn(null);
        assertCode(() -> bridge.execute(session(), proposed), AI_REALTIME_TOOL_CALL_NOT_EXISTS);
    }

    @Test
    void confirmationRequiredAndDeniedPoliciesRejectTheCall() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(1);
        when(policyGate.decide(eq("lookup-order"), any()))
                .thenThrow(new ServiceException(AI_TOOL_CONFIRMATION_REQUIRED));

        assertCode(() -> bridge.execute(session(), proposed), AI_REALTIME_TOOL_POLICY_DENIED);
        verify(toolCallMapper).reject(eq(CALL_ID), eq("AI_TOOL_CONFIRMATION_REQUIRED"), any());
        verify(toolExecutor, never()).execute(any());

        when(policyGate.decide(eq("lookup-order"), any())).thenThrow(new ServiceException(AI_TOOL_POLICY_DENIED));
        assertCode(() -> bridge.execute(session(), proposed), AI_REALTIME_TOOL_POLICY_DENIED);
        verify(toolCallMapper).reject(eq(CALL_ID), eq("AI_TOOL_POLICY_DENIED"), any());
    }

    @Test
    void writeToolsAreRejectedInsideSessions() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(1);
        when(policyGate.decide(eq("lookup-order"), any())).thenReturn(decision("WRITE"));

        assertCode(() -> bridge.execute(session(), proposed), AI_REALTIME_TOOL_POLICY_DENIED);
        verify(toolCallMapper).reject(eq(CALL_ID), eq("AI_TOOL_WRITE_REQUIRES_CONFIRMATION"), any());
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void readToolExecutionRecordsStableConclusion() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(1);
        when(policyGate.decide(eq("lookup-order"), any())).thenReturn(decision("READ"));
        when(toolExecutor.execute(any())).thenReturn(new AiConnectorExecutionResultDTO().setStatus("COMPLETE"));
        when(toolCallMapper.selectById(CALL_ID)).thenReturn(call(AiRealtimeToolCallDO.STATUS_EXECUTED));

        assertThat(bridge.execute(session(), proposed).getStatus()).isEqualTo(AiRealtimeToolCallDO.STATUS_EXECUTED);
        verify(toolCallMapper).bindDecision(eq(CALL_ID), eq(7L), eq("READ"));
        verify(toolCallMapper)
                .finishExecution(eq(CALL_ID), eq(AiRealtimeToolCallDO.STATUS_EXECUTED), eq("COMPLETE"), any());
    }

    @Test
    void failedExecutionIsRecordedAndRethrown() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(1);
        when(policyGate.decide(eq("lookup-order"), any())).thenReturn(decision("READ"));
        when(toolExecutor.execute(any())).thenThrow(new ServiceException(AI_TOOL_POLICY_DENIED));

        assertCode(() -> bridge.execute(session(), proposed), AI_TOOL_POLICY_DENIED);
        verify(toolCallMapper)
                .finishExecution(
                        eq(CALL_ID),
                        eq(AiRealtimeToolCallDO.STATUS_FAILED),
                        eq(String.valueOf(AI_TOOL_POLICY_DENIED.getCode())),
                        any());

        doThrow(new IllegalStateException("boom")).when(toolExecutor).execute(any());
        assertThatThrownBy(() -> bridge.execute(session(), proposed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
        verify(toolCallMapper)
                .finishExecution(
                        eq(CALL_ID), eq(AiRealtimeToolCallDO.STATUS_FAILED), eq("IllegalStateException"), any());
    }

    @Test
    void invalidFrozenArgumentsAreRejected() {
        AiRealtimeToolCallDO broken = call(AiRealtimeToolCallDO.STATUS_PROPOSED).setArgumentsJson("{not-json");
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(1);

        assertCode(() -> bridge.execute(session(), broken), AI_REALTIME_TOOL_POLICY_DENIED);
        verify(toolCallMapper).reject(eq(CALL_ID), eq("ARGUMENTS_INVALID"), any());
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void unboundedUpstreamConclusionIsBoundedToColumnWidth() {
        AiRealtimeToolCallDO proposed = call(AiRealtimeToolCallDO.STATUS_PROPOSED);
        when(toolCallMapper.claimExecution(CALL_ID)).thenReturn(1);
        when(policyGate.decide(eq("lookup-order"), any())).thenReturn(decision("READ"));
        when(toolExecutor.execute(any()))
                .thenReturn(new AiConnectorExecutionResultDTO().setDetailCode("x".repeat(120)));
        when(toolCallMapper.selectById(CALL_ID)).thenReturn(call(AiRealtimeToolCallDO.STATUS_EXECUTED));

        bridge.execute(session(), proposed);

        verify(toolCallMapper)
                .finishExecution(eq(CALL_ID), eq(AiRealtimeToolCallDO.STATUS_EXECUTED), eq("x".repeat(64)), any());
    }

    private static AiRealtimeToolCallDO call(String status) {
        return new AiRealtimeToolCallDO()
                .setId(CALL_ID)
                .setSessionId(SESSION_ID)
                .setTurnNo(0L)
                .setCallId("call_1")
                .setToolCode("lookup-order")
                .setArgumentsJson("{\"orderNo\":\"A1\"}")
                .setStatus(status)
                .setVersion(0);
    }

    private static AiRealtimeSessionDO session() {
        return new AiRealtimeSessionDO().setId(SESSION_ID).setTurnNo(0L);
    }

    private static AiToolDecision decision(String toolType) {
        AiToolVersionDO version = new AiToolVersionDO();
        version.setId(7L);
        version.setToolType(toolType);
        version.setSourceRef("lookup-order");
        return new AiToolDecision(AiToolDecision.Outcome.EXECUTE, version, 5L, "lookup-order", Map.of());
    }

    private static void assertCode(ThrowingOperation operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
