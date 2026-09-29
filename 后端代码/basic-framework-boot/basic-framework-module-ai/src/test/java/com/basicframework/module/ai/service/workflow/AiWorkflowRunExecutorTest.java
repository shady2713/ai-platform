package com.basicframework.module.ai.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunNodeMapper;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 流程运行执行器（X08 验收 3）：预算受控结束、节点走受控入口、失败不冒充成功、逐节点留痕。
 *
 * <p>处理器用测试替身直接给出行为（成功文本 / 稳定错误码 / 非业务异常），
 * 断言对象是运行行与节点行的**落库事实**。
 */
@ExtendWith(MockitoExtension.class)
class AiWorkflowRunExecutorTest {

    private static final Long RUN_ID = 31L;

    @Mock
    private AiWorkflowRunMapper runMapper;

    @Mock
    private AiWorkflowRunNodeMapper nodeMapper;

    private AiWorkflowRunDO run;

    private AiWorkflowVersionDO version;

    @BeforeEach
    void setUp() {
        run = new AiWorkflowRunDO()
                .setId(RUN_ID)
                .setWorkflowId(9L)
                .setWorkflowVersionId(12L)
                .setStatus(AiWorkflowRunDO.STATUS_RUNNING)
                .setDataLevel("L2_INTERNAL")
                .setInputText("订单输入文本")
                .setNodeExecuted(0)
                .setVersion(0);
        version = new AiWorkflowVersionDO().setId(12L).setVersionNo(1);
    }

    @Test
    void walksPipelineThroughControlledHandlerAndFinishesSuccessfully() {
        version.setGraphJson(pipeline());
        AiWorkflowRunExecutor executor =
                executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> "模型结论:" + input));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_SUCCEEDED);
        assertThat(finished.getOutputText()).isEqualTo("模型结论:订单输入文本");
        assertThat(finished.getNodeExecuted()).isEqualTo(3);
        assertThat(finished.getCurrentNodeKey()).isEqualTo("end");
        assertThat(finished.getErrorCode()).isNull();
        // 每个节点一行事实；模型节点拿到的输入是开始节点的输出
        ArgumentCaptor<AiWorkflowRunNodeDO> rows = ArgumentCaptor.forClass(AiWorkflowRunNodeDO.class);
        verify(nodeMapper, times(3)).insert(rows.capture());
        assertThat(rows.getAllValues().get(0).getNodeKey()).isEqualTo("start");
        assertThat(rows.getAllValues().get(1).getOutputText()).isEqualTo("模型结论:订单输入文本");
        assertThat(rows.getAllValues().get(2).getNodeKey()).isEqualTo("end");
        verify(runMapper).finishWithCas(any(AiWorkflowRunDO.class), anyInt());
    }

    @Test
    void conditionRoutesByControlledComparisonAndPassesComparedOutputDownstream() {
        version.setGraphJson(conditionPipeline("CONTAINS", "已通过"));
        AiWorkflowRunExecutor executor = executor(
                handler(AiWorkflowNodeType.MODEL, (node, input, context) -> "审核未通过:原因"),
                handler(AiWorkflowNodeType.TOOL, (node, input, context) -> "fallback收到:" + input));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_SUCCEEDED);
        // FALSE 分支的下游拿到的是被比较的输出，而不是 TRUE/FALSE 字面量
        ArgumentCaptor<AiWorkflowRunNodeDO> rows = ArgumentCaptor.forClass(AiWorkflowRunNodeDO.class);
        verify(nodeMapper, times(5)).insert(rows.capture());
        assertThat(rows.getAllValues().get(2).getOutputText()).isEqualTo("FALSE");
        assertThat(rows.getAllValues().get(3).getOutputText()).isEqualTo("fallback收到:审核未通过:原因");
        assertThat(finished.getOutputText()).isEqualTo("fallback收到:审核未通过:原因");
    }

    @Test
    void stepBudgetEndsTheRunInAControlledWay() {
        version.setGraphJson(pipeline());
        run.setMaxSteps(2);
        run.setMaxDurationMillis(60_000L);
        AiWorkflowRunExecutor executor = executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> "结论"));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_FAILED);
        assertThat(finished.getErrorCode()).isEqualTo(AiWorkflowRunExecutor.REASON_STEP_BUDGET_EXCEEDED);
        // 开始与模型各执行一步，结束节点因预算不再执行
        verify(nodeMapper, times(2)).insert(any(AiWorkflowRunNodeDO.class));
    }

    @Test
    void durationBudgetEndsTheRunInAControlledWay() {
        version.setGraphJson(pipeline());
        run.setMaxSteps(8);
        run.setMaxDurationMillis(1L);
        AiWorkflowRunExecutor executor = executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> {
            // 忙等越过 1ms 预算（不做真实 sleep，避免噪声）
            long deadline = System.nanoTime() + 10_000_000L;
            while (System.nanoTime() < deadline) {
                // 自旋到预算超时
            }
            return "结论";
        }));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_FAILED);
        assertThat(finished.getErrorCode()).isEqualTo(AiWorkflowRunExecutor.REASON_DURATION_BUDGET_EXCEEDED);
    }

    @Test
    void handlerServiceExceptionFailsNodeAndRunWithStableCode() {
        version.setGraphJson(pipeline());
        AiWorkflowRunExecutor executor = executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> {
            throw new ServiceException(AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED);
        }));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_FAILED);
        assertThat(finished.getErrorCode())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED.getCode()));
        ArgumentCaptor<AiWorkflowRunNodeDO> rows = ArgumentCaptor.forClass(AiWorkflowRunNodeDO.class);
        verify(nodeMapper, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues().get(1).getStatus()).isEqualTo(AiWorkflowRunNodeDO.STATUS_FAILED);
        assertThat(rows.getAllValues().get(1).getErrorCode())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED.getCode()));
    }

    @Test
    void unexpectedHandlerFailureIsRecordedAsInternalErrorWithoutLeakingDetails() {
        version.setGraphJson(pipeline());
        AiWorkflowRunExecutor executor = executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> {
            throw new IllegalStateException("connection reset to secret-host");
        }));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_FAILED);
        assertThat(finished.getErrorCode()).isEqualTo(AiWorkflowRunExecutor.REASON_NODE_INTERNAL_ERROR);
        assertThat(finished.getOutputText()).isNull();
    }

    @Test
    void snapshotWithoutStartFailsControlledInsteadOfHanging() {
        version.setGraphJson(
                "{\"nodes\":[{\"key\":\"a\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                        + "{\"key\":\"end\",\"type\":\"END\"}],"
                        + "\"edges\":[{\"from\":\"a\",\"to\":\"end\"}]}");
        AiWorkflowRunExecutor executor = executor();

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_FAILED);
        assertThat(finished.getErrorCode())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
    }

    @Test
    void snapshotWithDeadEndFailsControlledInsteadOfHanging() {
        version.setGraphJson("{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"a\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"a\"}]}");
        AiWorkflowRunExecutor executor = executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> "结论"));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_FAILED);
        assertThat(finished.getErrorCode())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_WORKFLOW_GRAPH_NO_EXIT.getCode()));
    }

    @Test
    void nodeOutputIsTruncatedToTheColumnLimit() {
        version.setGraphJson(pipeline());
        String longOutput = "x".repeat(3000);
        AiWorkflowRunExecutor executor =
                executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> longOutput));

        AiWorkflowRunDO finished = executor.execute(run, version);

        assertThat(finished.getOutputText()).hasSize(AiWorkflowRunNodeDO.MAX_OUTPUT_LENGTH);
    }

    @Test
    void handlerReceivesRunDataLevelAndRemainingBudget() {
        version.setGraphJson(pipeline());
        run.setDataLevel("L1_PUBLIC");
        run.setMaxDurationMillis(120_000L);
        AiWorkflowRunExecutor executor = executor(handler(AiWorkflowNodeType.MODEL, (node, input, context) -> {
            assertThat(context.dataLevel()).isEqualTo(AiOutboundLevel.L1_PUBLIC);
            assertThat(context.remainingTimeout()).isLessThanOrEqualTo(Duration.ofMillis(120_000));
            return "ok";
        }));

        assertThat(executor.execute(run, version).getStatus()).isEqualTo(AiWorkflowRunDO.STATUS_SUCCEEDED);
    }

    @Test
    void concurrentRunRowUpdateIsRefused() {
        version.setGraphJson(pipeline());
        AiWorkflowRunExecutor executor = executor();
        // 覆盖默认桩：CAS 推进失败 = 并发写者出现
        org.mockito.Mockito.when(runMapper.updateWithVersion(any(AiWorkflowRunDO.class), anyInt()))
                .thenReturn(0);

        assertThatThrownBy(() -> executor.execute(run, version)).isInstanceOf(IllegalStateException.class);
    }

    // ---------- 夹具 ----------

    /** 开始 → 模型 → 结束 的合法管线。 */
    private static String pipeline() {
        return "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"ask\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"总结 {input}\"}},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"ask\"},{\"from\":\"ask\",\"to\":\"end\"}]}";
    }

    /** 开始 → 模型 → 条件（TRUE 通过 / FALSE 兜底工具）→ 结束。 */
    private static String conditionPipeline(String operator, String value) {
        return "{\"nodes\":[{\"key\":\"start\",\"type\":\"START\"},"
                + "{\"key\":\"ask\",\"type\":\"MODEL\",\"config\":{\"endpointId\":1,\"promptTemplate\":\"p\"}},"
                + "{\"key\":\"route\",\"type\":\"CONDITION\",\"config\":{\"ref\":\"ask\",\"operator\":\""
                + operator + "\",\"value\":\"" + value + "\"}},"
                + "{\"key\":\"fallback\",\"type\":\"TOOL\",\"config\":{\"toolCode\":\"crm_query_order\"}},"
                + "{\"key\":\"end\",\"type\":\"END\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"ask\"},{\"from\":\"ask\",\"to\":\"route\"},"
                + "{\"from\":\"route\",\"to\":\"end\",\"branch\":\"TRUE\"},"
                + "{\"from\":\"route\",\"to\":\"fallback\",\"branch\":\"FALSE\"},"
                + "{\"from\":\"fallback\",\"to\":\"end\"}]}";
    }

    private AiWorkflowRunExecutor executor(AiWorkflowNodeHandler... handlers) {
        // 默认让运行行的 CAS 推进成功（单写者语义）；预算失败分支会覆盖这些桩
        org.mockito.Mockito.lenient()
                .when(runMapper.updateWithVersion(any(AiWorkflowRunDO.class), anyInt()))
                .thenReturn(1);
        org.mockito.Mockito.lenient()
                .when(runMapper.finishWithCas(any(AiWorkflowRunDO.class), anyInt()))
                .thenReturn(1);
        org.mockito.Mockito.lenient()
                .doAnswer(invocation -> {
                    invocation.<AiWorkflowRunNodeDO>getArgument(0).setId(1L);
                    return 1;
                })
                .when(nodeMapper)
                .insert(any(AiWorkflowRunNodeDO.class));
        return new AiWorkflowRunExecutor(runMapper, nodeMapper, List.of(handlers));
    }

    private AiWorkflowNodeHandler handler(AiWorkflowNodeType type, NodeBody body) {
        return new AiWorkflowNodeHandler() {
            @Override
            public AiWorkflowNodeType type() {
                return type;
            }

            @Override
            public String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context) {
                return body.execute(node, input, context);
            }
        };
    }

    /** 测试替身节点的行为。 */
    private interface NodeBody {
        String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context);
    }
}
