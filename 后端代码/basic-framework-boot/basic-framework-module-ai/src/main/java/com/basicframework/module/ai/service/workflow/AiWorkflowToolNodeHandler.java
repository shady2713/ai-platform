package com.basicframework.module.ai.service.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;

import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 工具节点处理器（X08）：只经 D08 政策闸门判定 + D02 受控执行，没有私有工具路径。
 *
 * <p>判定顺序与模型侧完全一致（同一个闸门实例）：工具必须存在且启用、必须绑定已发布版本、
 * 政策必须允许、参数按版本 schema 重校验。需要人工确认（CONFIRM）或被拒绝（DENY）的政策
 * 在这里以稳定错误码失败——流程运行**不能**为写调用开第二通道：写工具只能经既有
 * "确认 + 消费一次"的动作链路执行（X06），流程节点永远不直接调 {@code executeWrite}。
 */
@Component
@RequiredArgsConstructor
public class AiWorkflowToolNodeHandler implements AiWorkflowNodeHandler {

    /** 工具执行政策闸门（D08）：判定的唯一入口。 */
    private final AiToolPolicyGate policyGate;

    /** 工具执行器（D02/D08）：只接受 EXECUTE 判定。 */
    private final AiToolExecutor toolExecutor;

    @Override
    public AiWorkflowNodeType type() {
        return AiWorkflowNodeType.TOOL;
    }

    @Override
    public String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context) {
        String toolCode =
                node.config().get("toolCode") instanceof String text && StringUtils.hasText(text) ? text : null;
        if (toolCode == null) {
            // 发布期已校验；运行期防御（图快照被绕过写库时在这里拒绝）
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        Map<String, Object> arguments = argumentsOf(node.config().get("arguments"));
        AiToolDecision decision = policyGate.decide(toolCode, arguments);
        // 只有 AUTO 政策的判定能到这里；CONFIRM/DENY 已在闸门被拒（写工具不可能 AUTO）
        var result = toolExecutor.execute(decision);
        return "status=" + result.getStatus() + " items=" + result.getItemCount()
                + (result.getStoppedReason() == null ? "" : " stopped=" + result.getStoppedReason());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentsOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
    }
}
