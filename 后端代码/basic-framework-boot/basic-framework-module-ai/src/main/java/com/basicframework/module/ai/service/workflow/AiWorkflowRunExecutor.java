package com.basicframework.module.ai.service.workflow;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunNodeMapper;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.workflow.AiWorkflowBudget;
import com.basicframework.module.ai.domain.workflow.AiWorkflowConditionOperator;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 流程运行执行器（X08）：从开始节点沿边走图，逐步受控结束。
 *
 * <p>执行语义（顺序即安全语义）：
 * <ul>
 *   <li><b>预算前置</b>：每个节点执行前先判步数与耗时预算，超出即受控结束
 *       （稳定原因 {@code STEP_BUDGET_EXCEEDED} / {@code DURATION_BUDGET_EXCEEDED}），不再执行任何节点；</li>
 *   <li><b>节点走受控入口</b>：模型/检索/查询/工具分别交给对应处理器；条件是受控比较表达式；
 *       处理器抛出的稳定错误码原样落到节点行与运行行，流程绝不把失败冒充成功；</li>
 *   <li><b>逐节点留痕</b>：每个节点一行事实（状态/错误码/耗时/输出摘要）；节点行与运行行
 *       各自单条写入，网络调用不持有长事务；</li>
 *   <li><b>终态原子</b>：运行终态用 CAS（{@code RUNNING} + 乐观锁）写入，重放无法覆盖；
 *       运行期发现坏图（绕过发布校验的快照）同样受控失败，不会半途悬挂。</li>
 * </ul>
 *
 * <p>条件节点对下游是"透明"的：分支下游拿到的是**被比较的输出**，条件行只记录选中的分支。
 */
@Slf4j
@Component
public class AiWorkflowRunExecutor {

    /** 受控结束的原因码：步数预算用尽（与 O04 的运行原因码同一词表）。 */
    public static final String REASON_STEP_BUDGET_EXCEEDED = "STEP_BUDGET_EXCEEDED";

    /** 受控结束的原因码：耗时预算用尽。 */
    public static final String REASON_DURATION_BUDGET_EXCEEDED = "DURATION_BUDGET_EXCEEDED";

    /** 受控结束的原因码：节点处理器抛出非业务异常（记录日志，不外泄异常正文）。 */
    public static final String REASON_NODE_INTERNAL_ERROR = "NODE_INTERNAL_ERROR";

    private final AiWorkflowRunMapper runMapper;

    private final AiWorkflowRunNodeMapper nodeMapper;

    private final Map<AiWorkflowNodeType, AiWorkflowNodeHandler> handlers = new EnumMap<>(AiWorkflowNodeType.class);

    public AiWorkflowRunExecutor(
            AiWorkflowRunMapper runMapper, AiWorkflowRunNodeMapper nodeMapper, List<AiWorkflowNodeHandler> handlers) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
    }

    /** 执行已受理的运行（在受理事务之外调用）。返回终态后的运行行。 */
    public AiWorkflowRunDO execute(AiWorkflowRunDO run, AiWorkflowVersionDO version) {
        AiWorkflowBudget budget = AiWorkflowBudget.of(run.getMaxSteps(), run.getMaxDurationMillis());
        long startedAt = System.nanoTime();
        AiWorkflowGraph graph;
        try {
            graph = AiWorkflowGraph.parse(version.getGraphJson());
        } catch (ServiceException brokenSnapshot) {
            // 发布期校验已被绕过（快照被直接改库）：受控失败，不进入执行循环
            return failRun(run, startedAt, String.valueOf(brokenSnapshot.getCode()));
        }
        // 外发等级是运行级事实：受理时解析并快照，这里兜底 L2_INTERNAL（与受理默认一致）
        AiOutboundLevel dataLevel = AiOutboundLevel.parse(run.getDataLevel()).orElse(AiOutboundLevel.L2_INTERNAL);
        Map<String, String> outputs = new LinkedHashMap<>();
        AiWorkflowGraph.Node current = startNode(graph);
        if (current == null) {
            return failRun(
                    run, startedAt, String.valueOf(AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        }
        String upstream = run.getInputText();
        int executed = 0;
        while (true) {
            if (!budget.hasStepLeft(executed)) {
                return failRun(run, startedAt, REASON_STEP_BUDGET_EXCEEDED);
            }
            if (budget.durationExceeded(elapsedMillis(startedAt))) {
                return failRun(run, startedAt, REASON_DURATION_BUDGET_EXCEEDED);
            }
            NodeExecution execution = executeNode(current, upstream, outputs, dataLevel, budget, startedAt);
            recordNodeRow(run.getId(), current, execution);
            outputs.put(current.key(), execution.output());
            executed++;
            advanceRunRow(run, current.key(), executed);
            if (!execution.succeeded()) {
                // 节点失败即受控结束：稳定错误码落到运行行，后续节点一律不再执行
                return failRun(run, startedAt, execution.errorCode());
            }
            if (current.type() == AiWorkflowNodeType.END) {
                return succeedRun(run, startedAt, execution.output());
            }
            boolean conditional = current.type() == AiWorkflowNodeType.CONDITION;
            String branch = conditional ? execution.output() : null;
            String conditionRef = conditional ? refKey(current) : null;
            AiWorkflowGraph.Node next = nextNode(graph, current, branch);
            if (next == null) {
                // 发布期校验保证每个非结束节点都有出边；这里防御绕过校验的快照
                return failRun(
                        run, startedAt, String.valueOf(AiErrorCodeConstants.AI_WORKFLOW_GRAPH_NO_EXIT.getCode()));
            }
            current = next;
            // 条件节点对下游"透明"：分支下游拿到的是被比较的输出而不是 TRUE/FALSE 字面量
            upstream = conditionRef != null ? outputs.getOrDefault(conditionRef, "") : execution.output();
        }
    }

    // ---------- 节点执行 ----------

    private NodeExecution executeNode(
            AiWorkflowGraph.Node node,
            String input,
            Map<String, String> outputs,
            AiOutboundLevel dataLevel,
            AiWorkflowBudget budget,
            long startedAt) {
        LocalDateTime started = LocalDateTime.now();
        NodeExecution execution =
                switch (node.type()) {
                    case START, END -> NodeExecution.succeeded(input == null ? "" : input);
                    case CONDITION -> evaluateCondition(node, outputs);
                    default -> invokeHandler(node, input, dataLevel, budget, elapsedMillis(startedAt));
                };
        return execution.withTiming(started);
    }

    private NodeExecution invokeHandler(
            AiWorkflowGraph.Node node, String input, AiOutboundLevel dataLevel, AiWorkflowBudget budget, long elapsed) {
        AiWorkflowNodeHandler handler = handlers.get(node.type());
        if (handler == null) {
            return NodeExecution.failed(String.valueOf(AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        }
        AiWorkflowNodeContext context = new AiWorkflowNodeContext(
                dataLevel, Duration.ofMillis(Math.max(1, budget.getMaxDurationMillis() - elapsed)));
        try {
            return NodeExecution.succeeded(handler.execute(node, input, context));
        } catch (ServiceException failure) {
            // 稳定错误码原样落库：节点失败即受控结束，不重试、不吞错
            return NodeExecution.failed(String.valueOf(failure.getCode()));
        } catch (RuntimeException unexpected) {
            // 非业务异常：记录稳定原因与异常类型（不含参数正文），运行受控失败
            log.warn(
                    "[invokeHandler][node={}, unexpected={}]",
                    node.key(),
                    unexpected.getClass().getSimpleName());
            return NodeExecution.failed(REASON_NODE_INTERNAL_ERROR);
        }
    }

    /** 条件节点：受控比较（无脚本），输出选中的分支；发布期已冻结引用与操作符。 */
    private NodeExecution evaluateCondition(AiWorkflowGraph.Node node, Map<String, String> outputs) {
        String ref = refKey(node);
        String operator = node.config().get("operator") instanceof String text ? text : null;
        String value = node.config().get("value") == null
                ? null
                : String.valueOf(node.config().get("value"));
        AiWorkflowConditionOperator parsed =
                AiWorkflowConditionOperator.parse(operator).orElse(null);
        if (ref.isEmpty() || parsed == null || value == null) {
            return NodeExecution.failed(String.valueOf(AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH.getCode()));
        }
        String left = outputs.getOrDefault(ref, "");
        boolean chosen = parsed.evaluate(left, value);
        return NodeExecution.succeeded(chosen ? AiWorkflowGraph.BRANCH_TRUE : AiWorkflowGraph.BRANCH_FALSE);
    }

    private String refKey(AiWorkflowGraph.Node conditionNode) {
        return conditionNode.config().get("ref") instanceof String text ? text : "";
    }

    // ---------- 图导航 ----------

    private AiWorkflowGraph.Node startNode(AiWorkflowGraph graph) {
        return graph.nodes().stream()
                .filter(node -> node.type() == AiWorkflowNodeType.START)
                .findFirst()
                .orElse(null);
    }

    private AiWorkflowGraph.Node nextNode(AiWorkflowGraph graph, AiWorkflowGraph.Node current, String branch) {
        List<AiWorkflowGraph.Edge> outgoing = graph.outgoing(current.key());
        if (branch != null) {
            return outgoing.stream()
                    .filter(edge -> branch.equals(edge.branch()))
                    .findFirst()
                    .map(edge -> graph.node(edge.to()))
                    .orElse(null);
        }
        if (outgoing.size() != 1) {
            return null;
        }
        return graph.node(outgoing.get(0).to());
    }

    // ---------- 留痕与终态 ----------

    private void recordNodeRow(Long runId, AiWorkflowGraph.Node node, NodeExecution execution) {
        nodeMapper.insert(new AiWorkflowRunNodeDO()
                .setRunId(runId)
                .setNodeKey(node.key())
                .setNodeType(node.type().name())
                .setStatus(
                        execution.succeeded()
                                ? AiWorkflowRunNodeDO.STATUS_SUCCEEDED
                                : AiWorkflowRunNodeDO.STATUS_FAILED)
                .setOutputText(truncate(execution.output()))
                .setErrorCode(execution.errorCode())
                .setStartedTime(execution.started())
                .setFinishedTime(execution.finished())
                .setDurationMs(execution.durationMs()));
    }

    private void advanceRunRow(AiWorkflowRunDO run, String nodeKey, int executed) {
        if (runMapper.updateWithVersion(
                        new AiWorkflowRunDO()
                                .setId(run.getId())
                                .setCurrentNodeKey(nodeKey)
                                .setNodeExecuted(executed)
                                .setVersion(run.getVersion() + 1),
                        run.getVersion())
                == 0) {
            throw new IllegalStateException("流程运行行被并发修改: " + run.getId());
        }
        run.setCurrentNodeKey(nodeKey);
        run.setNodeExecuted(executed);
        run.setVersion(run.getVersion() + 1);
    }

    private AiWorkflowRunDO succeedRun(AiWorkflowRunDO run, long startedAt, String output) {
        return finishRun(run, startedAt, AiWorkflowRunDO.STATUS_SUCCEEDED, output, null);
    }

    private AiWorkflowRunDO failRun(AiWorkflowRunDO run, long startedAt, String errorCode) {
        return finishRun(run, startedAt, AiWorkflowRunDO.STATUS_FAILED, null, errorCode);
    }

    private AiWorkflowRunDO finishRun(
            AiWorkflowRunDO run, long startedAt, String status, String output, String errorCode) {
        LocalDateTime now = LocalDateTime.now();
        long duration = elapsedMillis(startedAt);
        int updated = runMapper.finishWithCas(
                new AiWorkflowRunDO()
                        .setId(run.getId())
                        .setStatus(status)
                        .setOutputText(truncate(output))
                        .setErrorCode(errorCode)
                        .setFinishedTime(now)
                        .setDurationMs(duration)
                        .setVersion(run.getVersion() + 1),
                run.getVersion());
        if (updated == 0) {
            throw new IllegalStateException("流程运行终态写入失败（已被并发写入）: " + run.getId());
        }
        run.setStatus(status);
        run.setOutputText(truncate(output));
        run.setErrorCode(errorCode);
        run.setFinishedTime(now);
        run.setDurationMs(duration);
        run.setVersion(run.getVersion() + 1);
        return run;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** 输出摘要截断（与列宽一致；留痕是摘要而不是全文）。 */
    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= AiWorkflowRunNodeDO.MAX_OUTPUT_LENGTH
                ? text
                : text.substring(0, AiWorkflowRunNodeDO.MAX_OUTPUT_LENGTH);
    }

    /** 一次节点执行的结果（执行器内部）。 */
    private record NodeExecution(
            boolean succeeded, String output, String errorCode, LocalDateTime started, LocalDateTime finished) {

        static NodeExecution succeeded(String output) {
            return of(true, output == null ? "" : output, null);
        }

        static NodeExecution failed(String errorCode) {
            return of(false, "", errorCode);
        }

        private static NodeExecution of(boolean succeeded, String output, String errorCode) {
            LocalDateTime now = LocalDateTime.now();
            return new NodeExecution(succeeded, output, errorCode, now, now);
        }

        NodeExecution withTiming(LocalDateTime started) {
            return new NodeExecution(succeeded, output, errorCode, started, LocalDateTime.now());
        }

        long durationMs() {
            return Duration.between(started, finished).toMillis();
        }
    }
}
