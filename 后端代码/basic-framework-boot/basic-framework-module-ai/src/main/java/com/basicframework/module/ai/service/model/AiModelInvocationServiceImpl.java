package com.basicframework.module.ai.service.model;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.policy.AiOutboundPolicy;
import com.basicframework.module.ai.domain.policy.AiOutboundPolicyProperties;
import com.basicframework.module.ai.service.usage.AiModelInvocationMeter;
import com.basicframework.module.ai.service.usage.AiModelInvocationRecord;
import com.basicframework.module.ai.service.usage.AiModelUsageRecorder;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 模型调用编排实现（M05）。
 *
 * <p>顺序固定：**先策略、后解析、再调用**。策略拒绝时不会触发端点解析或任何上游请求；
 * 失败同样落计量（状态 FAILED + 稳定原因），保证"调用过"可被账本核对。
 */
@Service
@RequiredArgsConstructor
public class AiModelInvocationServiceImpl implements AiModelInvocationService {

    private final AiModelEndpointService endpointService;

    private final AiModelClientResolver clientResolver;

    private final AiOutboundPolicy outboundPolicy;

    private final AiOutboundPolicyProperties properties;

    /** 计量出口：Q02 交付前允许不存在；缺失时记录仍随结果返回，不落库。 */
    private final ObjectProvider<AiModelUsageRecorder> usageRecorderProvider;

    @Override
    public AiModelInvocationResult<ModelResponse> generate(
            Long endpointId, ModelRequest request, AiOutboundLevel resourceLevel) {
        return invoke(
                endpointId, ModelCapability.TEXT, resourceLevel, ModelResponse::text, port -> port.generate(request));
    }

    @Override
    public AiModelInvocationResult<StructuredModelResult> generateStructured(
            Long endpointId, StructuredModelRequest request, AiOutboundLevel resourceLevel) {
        return invoke(
                endpointId,
                ModelCapability.STRUCTURED_OUTPUT,
                resourceLevel,
                result -> result.json() == null ? "" : result.json(),
                port -> port.generateStructured(request));
    }

    @Override
    public AiModelInvocationResult<EmbeddingResponse> embed(
            Long endpointId, EmbeddingRequest request, AiOutboundLevel resourceLevel) {
        return invoke(
                endpointId,
                ModelCapability.EMBEDDING,
                resourceLevel,
                response -> request.texts().isEmpty() ? "" : request.texts().get(0),
                port -> port.embed(request));
    }

    private <T> AiModelInvocationResult<T> invoke(
            Long endpointId,
            ModelCapability capability,
            AiOutboundLevel resourceLevel,
            Function<T, String> outputText,
            Function<ModelPort, T> call) {
        // 1) 外发策略：先于解析与网络调用；被拒绝时直接抛出，不解析端点、不发请求
        outboundPolicy.assertAllowed(endpointId, resourceLevel);
        // 2) 解析调用方指定的端点：不做候选端点遍历，因此不存在隐式失败转移
        AiModelEndpointDO endpoint = endpointService.getEndpoint(endpointId);
        ModelPort port = clientResolver.resolve(endpointId);
        String invocationId = AiModelInvocationMeter.newInvocationId();
        long startedAt = System.nanoTime();
        try {
            T output = call.apply(port);
            AiModelInvocationRecord record = AiModelInvocationMeter.succeeded(
                    invocationId,
                    endpoint.getId(),
                    endpoint.getConfigRevision(),
                    endpoint.getCredentialRevision(),
                    capability.name(),
                    usageOf(output),
                    latencyMillis(startedAt),
                    properties.getMetering().isEstimateWhenMissing(),
                    properties.getMetering().getCharsPerToken(),
                    outputText.apply(output));
            record(record);
            return new AiModelInvocationResult<>(record, output);
        } catch (ModelException exception) {
            record(AiModelInvocationMeter.failed(
                    invocationId,
                    endpointId,
                    endpoint.getConfigRevision(),
                    endpoint.getCredentialRevision(),
                    capability.name(),
                    exception.getReason(),
                    latencyMillis(startedAt)));
            throw exception;
        }
    }

    private void record(AiModelInvocationRecord record) {
        AiModelUsageRecorder recorder = usageRecorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.record(record);
        }
    }

    private static ModelUsage usageOf(Object output) {
        if (output instanceof ModelResponse response) {
            return response.usage();
        }
        if (output instanceof StructuredModelResult result) {
            return result.usage();
        }
        if (output instanceof EmbeddingResponse response) {
            return response.usage();
        }
        return ModelUsage.UNKNOWN;
    }

    private static long latencyMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
