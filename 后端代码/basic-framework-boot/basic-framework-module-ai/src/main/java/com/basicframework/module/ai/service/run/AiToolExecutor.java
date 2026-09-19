package com.basicframework.module.ai.service.run;

import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import java.util.List;
import java.util.Optional;

/**
 * 受控工具执行接口（O04）：模型给出的 tool-call 只能交给它执行，执行器本身不做任何"自动调用"。
 *
 * <p>约束：
 * <ul>
 *   <li>工具实现必须按**当前运行身份**（{@link AiExecutionContext}）重新判定资源授权，
 *       不接受模型给出的任何身份或范围提示；</li>
 *   <li>没有实现时返回空（{@link #find}），调用方必须按"明确不支持"结束，而不是跳过工具继续生成；</li>
 *   <li>执行结果只回传稳定结构（成功/失败 + 稳定原因码），正文与凭据由具体工具自己按数据分级处理。</li>
 * </ul>
 */
public interface AiToolExecutor {

    /** 查找可执行该工具的实现（没有实现时返回空）。 */
    Optional<ToolBinding> find(String toolKey);

    /** 工具绑定：资源标识 + 需要的动作 + 执行入口。 */
    interface ToolBinding {

        /** 资源类型（用于授权判定与审计） */
        AiResourceType resourceType();

        /** 资源标识 */
        String resourceKey();

        /** 需要的动作 */
        List<AiAction> actions();

        /** 执行一次工具调用（调用方负责在事务之外执行，并携带重建后的身份）。 */
        ToolResult execute(AiExecutionContext context, String arguments);
    }

    /** 工具执行结果：只保留稳定结论，不携带正文。 */
    record ToolResult(boolean succeeded, String detailCode) {

        public static ToolResult succeeded(String detailCode) {
            return new ToolResult(true, detailCode);
        }

        public static ToolResult failed(String detailCode) {
            return new ToolResult(false, detailCode);
        }
    }
}
