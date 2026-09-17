package com.basicframework.module.ai.domain.policy;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 默认外发策略（M05）：按端点编号读取配置，未配置的端点使用平台默认上限。
 *
 * <p>判定规则（两条同时满足才允许）：
 * <ol>
 *   <li>资源等级不高于端点上限 {@code level}；</li>
 *   <li>资源等级在端点显式 {@code allowed-resources} 里（为空表示"不高于上限的全部等级"）。</li>
 * </ol>
 * 未识别或缺失的等级一律拒绝：默认拒绝比默认放行安全，且拒绝发生在调用上游之前。
 */
@Component
@RequiredArgsConstructor
public class DefaultAiOutboundPolicy implements AiOutboundPolicy {

    private final AiOutboundPolicyProperties properties;

    @Override
    public AiOutboundLevel levelOf(Long endpointId) {
        return policyOf(endpointId).getLevel();
    }

    @Override
    public Set<AiOutboundLevel> allowedResourcesOf(Long endpointId) {
        AiOutboundPolicyProperties.EndpointPolicy policy = policyOf(endpointId);
        Set<AiOutboundLevel> allowed = policy.getAllowedResources();
        if (allowed == null || allowed.isEmpty()) {
            // 未显式列举：允许不高于上限的全部等级
            Set<AiOutboundLevel> derived = new LinkedHashSet<>();
            for (AiOutboundLevel level : AiOutboundLevel.values()) {
                if (level.within(policy.getLevel())) {
                    derived.add(level);
                }
            }
            return Set.copyOf(derived);
        }
        return Set.copyOf(allowed);
    }

    @Override
    public void assertAllowed(Long endpointId, AiOutboundLevel resourceLevel) {
        if (resourceLevel == null) {
            throw exception(AI_MODEL_OUTBOUND_BLOCKED);
        }
        AiOutboundLevel ceiling = levelOf(endpointId);
        if (!resourceLevel.within(ceiling) || !allowedResourcesOf(endpointId).contains(resourceLevel)) {
            throw exception(AI_MODEL_OUTBOUND_BLOCKED);
        }
    }

    private AiOutboundPolicyProperties.EndpointPolicy policyOf(Long endpointId) {
        Map<String, AiOutboundPolicyProperties.EndpointPolicy> endpoints = properties.getEndpoints();
        AiOutboundPolicyProperties.EndpointPolicy policy =
                endpointId == null || endpoints == null ? null : endpoints.get(String.valueOf(endpointId));
        if (policy != null) {
            return policy;
        }
        AiOutboundPolicyProperties.EndpointPolicy fallback = new AiOutboundPolicyProperties.EndpointPolicy();
        fallback.setLevel(properties.getDefaultLevel());
        return fallback;
    }
}
