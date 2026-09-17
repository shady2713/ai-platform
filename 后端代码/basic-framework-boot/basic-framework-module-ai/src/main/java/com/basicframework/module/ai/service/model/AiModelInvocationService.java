package com.basicframework.module.ai.service.model;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;

/**
 * 模型调用编排（M05）：外发策略校验 → 解析目标端点 → 真实调用 → 计量。
 *
 * <p>约束：
 * <ul>
 *   <li>策略校验在**任何网络调用之前**执行；被拒绝时不发出请求；</li>
 *   <li>只用调用方指定的端点，**不做隐式跨供应商失败转移**：上游失败按稳定原因抛出，
 *       不会改投其他端点（要换端点必须由调用方显式发起新调用）；</li>
 *   <li>每次实际调用产生一条计量记录（invocationId 每次调用唯一），
 *       用量语义区分 UNKNOWN 与 ESTIMATED。</li>
 * </ul>
 */
public interface AiModelInvocationService {

    /** 文本生成（带外发等级校验与计量）。 */
    AiModelInvocationResult<ModelResponse> generate(
            Long endpointId, ModelRequest request, AiOutboundLevel resourceLevel);

    /** 结构化输出（带外发等级校验与计量）。 */
    AiModelInvocationResult<StructuredModelResult> generateStructured(
            Long endpointId, StructuredModelRequest request, AiOutboundLevel resourceLevel);

    /** 批量嵌入（带外发等级校验与计量）。 */
    AiModelInvocationResult<EmbeddingResponse> embed(
            Long endpointId, EmbeddingRequest request, AiOutboundLevel resourceLevel);
}
