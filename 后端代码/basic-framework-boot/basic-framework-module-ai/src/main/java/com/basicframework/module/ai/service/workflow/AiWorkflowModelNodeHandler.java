package com.basicframework.module.ai.service.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_REFERENCE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NODE_TYPE_MISMATCH;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.module.ai.domain.runtime.AiModelFailureCodes;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.domain.workflow.AiWorkflowNodeType;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 模型节点处理器（X08）：经 M05 受控调用（外发策略 + 计量），不做任何直连。
 *
 * <p>端点在发布期冻结进节点配置；运行期解析端点当前配置版本的模型标识（与 O04 同口径）。
 * 上游失败收敛为平台稳定错误码（不外泄上游正文），由执行器记录并受控结束。
 */
@Component
@RequiredArgsConstructor
public class AiWorkflowModelNodeHandler implements AiWorkflowNodeHandler {

    /** 模型调用编排（M05）。 */
    private final AiModelInvocationService invocationService;

    /** 模型端点服务（解析端点当前配置版本的模型标识）。 */
    private final AiModelEndpointService endpointService;

    @Override
    public AiWorkflowNodeType type() {
        return AiWorkflowNodeType.MODEL;
    }

    @Override
    public String execute(AiWorkflowGraph.Node node, String input, AiWorkflowNodeContext context) {
        Long endpointId = positiveLong(node.config().get("endpointId"));
        String template = text(node.config().get("promptTemplate"));
        if (endpointId == null || template == null) {
            // 发布期已校验；运行期防御（图快照被绕过写库时在这里拒绝）
            throw exception(AI_WORKFLOW_NODE_TYPE_MISMATCH);
        }
        String modelId = endpointService.getRevisions(endpointId).stream()
                .findFirst()
                .map(revision -> revision.getModelId())
                .orElseThrow(() -> exception(AI_WORKFLOW_NODE_REFERENCE_INVALID));
        String prompt = template.replace("{input}", input == null ? "" : input);
        AiModelInvocationResult<ModelResponse> invoked;
        try {
            invoked = invocationService.generate(
                    endpointId, new ModelRequest(modelId, prompt, context.remainingTimeout()), context.dataLevel());
        } catch (ModelException failure) {
            // 上游失败：收敛为稳定错误码，不把异常正文带给下游节点
            throw AiModelFailureCodes.toServiceException(failure);
        }
        return invoked.output().text();
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Integer integer) {
            return integer > 0 ? integer.longValue() : null;
        }
        if (value instanceof Long longValue) {
            return longValue > 0 ? longValue : null;
        }
        return null;
    }

    private static String text(Object value) {
        return value instanceof String textValue && !textValue.isBlank() ? textValue : null;
    }
}
