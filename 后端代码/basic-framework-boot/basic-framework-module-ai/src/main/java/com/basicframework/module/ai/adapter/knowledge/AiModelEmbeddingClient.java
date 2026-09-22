package com.basicframework.module.ai.adapter.knowledge;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingException;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 嵌入客户端实现（K05）：解析知识库声明的嵌入模型 → 调用 M04 的模型调用服务。
 *
 * <p>三条约束：
 * <ol>
 *   <li><b>端点必须存在且启用</b>：知识库的 `embedding_model` 按端点名称解析，解析不到就是稳定失败
 *       （不猜模型、不退回默认端点）；</li>
 *   <li><b>维度一致性由模型中心把关</b>：调用前用
 *       {@code assertEmbeddingDimensionUnchanged} 校验观测维度与既有索引一致——同维度不同模型 revision
 *       也不允许混用（AT-029）；</li>
 *   <li><b>外发等级</b>：知识原文属于内部数据，按 {@link AiOutboundLevel#INTERNAL} 判定，
 *       被策略拒绝时稳定失败（不降级、不重试到其它端点）。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AiModelEmbeddingClient implements AiKnowledgeEmbeddingClient {

    /** 单次嵌入请求的超时。 */
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);

    private final AiModelEndpointService endpointService;

    private final AiModelInvocationService invocationService;

    @Override
    public EmbeddingBatch embed(String embeddingModel, List<String> texts) {
        if (!StringUtils.hasText(embeddingModel) || texts == null || texts.isEmpty()) {
            throw new AiKnowledgeEmbeddingException(AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID, null);
        }
        AiModelEndpointDO endpoint = resolveEndpoint(embeddingModel);
        AiModelInvocationResult<EmbeddingResponse> result;
        try {
            result = invocationService.embed(
                    endpoint.getId(), EmbeddingRequest.of(embeddingModel, texts), AiOutboundLevel.L2_INTERNAL);
        } catch (RuntimeException failure) {
            throw AiKnowledgeEmbeddingException.of(AiKnowledgeEmbeddingException.Reason.UPSTREAM_FAILED, failure);
        }
        if (result == null || result.output() == null) {
            throw new AiKnowledgeEmbeddingException(AiKnowledgeEmbeddingException.Reason.UPSTREAM_FAILED, null);
        }
        EmbeddingResponse response = result.output();
        if (response.size() != texts.size()) {
            throw new AiKnowledgeEmbeddingException(AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID, "size");
        }
        int dimension = response.dimensions();
        if (dimension <= 0) {
            throw new AiKnowledgeEmbeddingException(AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID, "dimension");
        }
        try {
            // 同维度不同 revision 也不允许混索引：维度基线由模型中心记录并在此校验
            endpointService.assertEmbeddingDimensionUnchanged(endpoint.getId(), dimension);
        } catch (RuntimeException mismatch) {
            throw AiKnowledgeEmbeddingException.of(AiKnowledgeEmbeddingException.Reason.DIMENSION_MISMATCH, mismatch);
        }
        return new EmbeddingBatch(response.vectors(), embeddingModel, dimension);
    }

    private AiModelEndpointDO resolveEndpoint(String embeddingModel) {
        var page = endpointService.getEndpointPage(new PageParam(), embeddingModel, null);
        return page.getList().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                .findFirst()
                .orElseThrow(() -> new AiKnowledgeEmbeddingException(
                        AiKnowledgeEmbeddingException.Reason.ENDPOINT_UNAVAILABLE, embeddingModel));
    }
}
