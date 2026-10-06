package com.basicframework.module.ai.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelToolCall;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.context.AiContextBuilder;
import com.basicframework.module.ai.service.context.dto.AiContextResultDTO;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import com.basicframework.module.ai.service.run.dto.AiRunExecutionResultDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import com.basicframework.module.ai.service.usage.AiModelInvocationRecord;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O04 文本运行执行：预算约束、上游失败无假成功、工具明确不支持、终态不可覆盖。 */
class AiRunExecutionServiceImplTest {

    private static final Long RUN_ID = 41L;

    private static final Long SERVICE_ID = 9L;

    private static final Long RELEASE_ID = 21L;

    private static final Long CONVERSATION_ID = 31L;

    private static final Long ENDPOINT_ID = 3L;

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiServiceReleaseService releaseService = mock(AiServiceReleaseService.class);

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelInvocationService invocationService = mock(AiModelInvocationService.class);

    private final AiContextBuilder contextBuilder = mock(AiContextBuilder.class);

    private final AiConversationService conversationService = mock(AiConversationService.class);

    private final AiTaskService taskService = mock(AiTaskService.class);

    private final AiToolExecutor toolExecutor = mock(AiToolExecutor.class);

    private final AiRunTerminalWriter terminalWriter = mock(AiRunTerminalWriter.class);

    private final AiRunExecutionServiceImpl service = new AiRunExecutionServiceImpl(
            runMapper,
            releaseService,
            endpointService,
            invocationService,
            contextBuilder,
            conversationService,
            taskService,
            toolExecutor,
            terminalWriter);

    @BeforeEach
    void setUp() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run());
        when(taskService.rebuildIdentity(RUN_ID))
                .thenReturn(new AiExecutionContext(5L, "USER", "u-1001", Set.of(10L), Set.of("report-1"), "it", 1L));
        AiServiceReleaseDO release = new AiServiceReleaseDO()
                .setId(RELEASE_ID)
                .setServiceId(SERVICE_ID)
                .setModelEndpointId(ENDPOINT_ID)
                .setEndpointConfigRevision(3)
                .setPromptTemplate("你是订单助手")
                .setContentHash("a".repeat(64))
                .setStatus(AiServiceReleaseDO.STATUS_ACTIVE);
        when(releaseService.listReleases(SERVICE_ID)).thenReturn(List.of(release));
        when(releaseService.listReleaseBindings(RELEASE_ID)).thenReturn(List.of());
        when(contextBuilder.build(any()))
                .thenReturn(new AiContextResultDTO().setPrompt("拼装后的提示词").setSections(List.of()));
        when(conversationService.listMessages(CONVERSATION_ID, null, 100))
                .thenReturn(List.of(new AiConversationMessageDO()
                        .setId(77L)
                        .setConversationId(CONVERSATION_ID)
                        .setRole(AiConversationMessageDO.ROLE_USER)
                        .setContent("帮我查订单 A-1")
                        .setStatus(AiConversationMessageDO.STATUS_ACTIVE)));
        when(conversationService.loadRunContext(CONVERSATION_ID, 20))
                .thenReturn(new AiConversationRunContextDTO().setHistory(List.of()));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO()
                        .setEndpointId(ENDPOINT_ID)
                        .setRevision(1)
                        .setModelId("gpt-4o-mini")));
        when(terminalWriter.finish(any(), any(), anyString(), any(), anyInt(), any()))
                .thenReturn(true);
    }

    private static AiRunDO run() {
        return new AiRunDO()
                .setId(RUN_ID)
                .setServiceId(SERVICE_ID)
                .setReleaseId(RELEASE_ID)
                .setConversationId(CONVERSATION_ID)
                .setModelEndpointId(ENDPOINT_ID)
                .setDataLevel("L2_INTERNAL")
                .setStatus(AiRunDO.STATUS_ACCEPTED)
                .setStepCount(0)
                .setVersion(0);
    }

    private static AiTaskLeaseDTO lease() {
        return new AiTaskLeaseDTO()
                .setTaskId(61L)
                .setRunId(RUN_ID)
                .setTaskKind("RUN_STEP")
                .setOwner("worker-a")
                .setEpoch(1)
                .setAttempt(1);
    }

    private static ModelResponse response(List<ModelToolCall> toolCalls, String text) {
        return new ModelResponse(text, ModelUsage.of(50, 20), toolCalls, "gpt-4o-mini", "stop");
    }

    private void stubModel(ModelResponse output) {
        when(invocationService.generate(eq(ENDPOINT_ID), any(ModelRequest.class), any()))
                .thenReturn(new AiModelInvocationResult<>(mock(AiModelInvocationRecord.class), output));
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void executesTextRunAndWritesAssistantMessageWithTerminalState() {
        stubModel(response(List.of(), "订单 A-1 已发货"));

        AiRunExecutionResultDTO result = service.execute(lease(), AiRunBudget.defaults());

        assertThat(result.getStatus()).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
        assertThat(result.getSteps()).isEqualTo(1);
        assertThat(result.getToolCalls()).isZero();
        assertThat(result.getOutputText()).isEqualTo("订单 A-1 已发货");
        assertThat(result.toString()).as("执行结果不进日志的正文").doesNotContain("已发货");
        // 助手消息与终态由同一个写入器在同一事务内落库
        verify(terminalWriter)
                .finish(eq(lease()), any(), eq(AiRunDO.STATUS_SUCCEEDED), eq(null), eq(1), eq("订单 A-1 已发货"));
        // 上下文用发布版本冻结的提示词与会话里的用户消息
        verify(contextBuilder)
                .build(org.mockito.ArgumentMatchers.argThat(request ->
                        "你是订单助手".equals(request.getSystemPrompt()) && "帮我查订单 A-1".equals(request.getUserMessage())));
    }

    @Test
    void upstreamFailureIsWrittenAsFailedWithoutFakeSuccess() {
        when(invocationService.generate(eq(ENDPOINT_ID), any(ModelRequest.class), any()))
                .thenThrow(new ModelException(ModelException.Reason.UPSTREAM_FAILED, "上游不可达"));

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_CALL_FAILED));

        verify(terminalWriter)
                .finish(
                        eq(lease()),
                        any(),
                        eq(AiRunDO.STATUS_FAILED),
                        eq(String.valueOf(AiErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode())),
                        eq(1),
                        eq(null));
    }

    @Test
    void toolCallWithoutImplementationIsExplicitlyUnsupported() {
        stubModel(response(List.of(new ModelToolCall("call-1", "order.query", "{}")), "我先查一下"));
        when(toolExecutor.find("order.query")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TOOL_UNSUPPORTED));

        verify(terminalWriter)
                .finish(eq(lease()), any(), eq(AiRunDO.STATUS_FAILED), eq("TOOL_UNSUPPORTED"), eq(1), eq(null));
    }

    @Test
    void budgetsBoundStepsToolCallsAndDuration() {
        // 工具次数上限 0：模型一请求工具就超预算
        stubModel(response(List.of(new ModelToolCall("call-1", "order.query", "{}")), "text"));
        when(toolExecutor.find("order.query")).thenReturn(Optional.of(mock(AiToolExecutor.ToolBinding.class)));
        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.of(8, 120_000L, 0)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_BUDGET_EXCEEDED));
        verify(terminalWriter)
                .finish(eq(lease()), any(), eq(AiRunDO.STATUS_FAILED), eq("TOOL_BUDGET_EXCEEDED"), eq(1), eq(null));

        // 步数上限 1：单次模型调用后没有剩余步数
        stubModel(response(List.of(), "text"));
        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.of(1, 120_000L, 4)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_BUDGET_EXCEEDED));
        verify(terminalWriter)
                .finish(eq(lease()), any(), eq(AiRunDO.STATUS_FAILED), eq("STEP_BUDGET_EXCEEDED"), eq(1), eq(null));
    }

    @Test
    void lateOrRepeatedTerminalWriteDoesNotOverwriteTheStoredState() {
        stubModel(response(List.of(), "text"));
        // 终态已被其它路径写入（重复执行、取消）：写入器返回 false，不覆盖
        when(terminalWriter.finish(any(), any(), anyString(), any(), anyInt(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .as("终态已被写入时不覆盖，返回终态冲突")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
    }

    @Test
    void alreadyTerminalRunIsNotExecutedAgain() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run().setStatus(AiRunDO.STATUS_SUCCEEDED));

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
        verify(invocationService, never()).generate(any(), any(), any());
        verify(terminalWriter, never()).finish(any(), any(), anyString(), any(), anyInt(), any());
    }

    @Test
    void runWithoutReplayableInputIsNotExecutable() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run().setConversationId(null));

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_EXECUTABLE));
        verify(invocationService, never()).generate(any(), any(), any());

        when(runMapper.selectById(RUN_ID)).thenReturn(run());
        when(conversationService.listMessages(CONVERSATION_ID, null, 100)).thenReturn(List.of());
        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_EXECUTABLE));

        when(runMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.execute(lease().setRunId(999L), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }

    @Test
    void identityIsRebuiltBeforeAnyModelCall() {
        when(taskService.rebuildIdentity(RUN_ID))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void stepBudgetPreCheckFailureIsWrittenAsTerminalState() {
        // 上限 0 步：预检在真正执行前就判定没有可用步数。预算工厂拒绝这种非法预算，
        // 所以这里用 mock 表达"预算已用尽"这一状态，而不是等执行后才发现。
        AiRunBudget exhausted = mock(AiRunBudget.class);
        when(exhausted.hasStepLeft(0)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(lease(), exhausted))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_BUDGET_EXCEEDED));

        // 预检失败与执行后超预算是同一个原因，就必须落同一个终态：
        // 不落终态的话运行留在非终态，恢复作业会重新租约再撞一次同一条预检、白烧一次 attempt，
        // 最后以"重试预算耗尽"收场，把排查引向根本不是原因的执行器。
        verify(terminalWriter)
                .finish(eq(lease()), any(), eq(AiRunDO.STATUS_FAILED), eq("STEP_BUDGET_EXCEEDED"), eq(1), eq(null));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void durationBudgetPreCheckFailureIsWrittenAsTerminalState() {
        // 身份重建与上下文拼装已把耗时预算用尽（mock 直接表达"已超耗时"，
        // 比在 stub 里 sleep 去赌时钟更稳定，结论完全相同）。
        AiRunBudget exhausted = mock(AiRunBudget.class);
        when(exhausted.hasStepLeft(0)).thenReturn(true);
        when(exhausted.durationExceeded(anyLong())).thenReturn(true);

        assertThatThrownBy(() -> service.execute(lease(), exhausted))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_BUDGET_EXCEEDED));

        verify(terminalWriter)
                .finish(eq(lease()), any(), eq(AiRunDO.STATUS_FAILED), eq("DURATION_BUDGET_EXCEEDED"), eq(1), eq(null));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void deniedIdentityStopsTheRunBeforeAnyModelCall() {
        // 组织范围与资源范围**都**为空才是 DENY（撤销后重建的直接结果）：
        // 不能把"范围为空"当成"不过滤"继续跑
        when(taskService.rebuildIdentity(RUN_ID))
                .thenReturn(new AiExecutionContext(5L, "USER", "u-1001", Set.of(), Set.of(), "it", 1L));

        assertThatThrownBy(() -> service.execute(lease(), AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_EXECUTABLE));
        verify(invocationService, never()).generate(any(), any(), any());
    }

    @Test
    void missingLeaseIsNotExecutableAndMissingBudgetFallsBackToPlatformDefaults() {
        // 没有租约就没有幂等键与终态栅栏，不能默认成"可执行"
        assertThatThrownBy(() -> service.execute(null, AiRunBudget.defaults()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        verify(invocationService, never()).generate(any(), any(), any());

        // 预算缺省走平台默认：不能把 null 当成 0 预算，也不能直接崩给调用方
        stubModel(response(List.of(), "text"));
        assertThat(service.execute(lease(), null).getStatus()).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
        verify(terminalWriter).finish(eq(lease()), any(), eq(AiRunDO.STATUS_SUCCEEDED), eq(null), eq(1), eq("text"));
    }
}
