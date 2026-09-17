package com.basicframework.module.ai.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M05 外发策略：默认拒绝敏感数据、端点级上限与显式允许清单、拒绝先于任何网络调用。
 */
class DefaultAiOutboundPolicyTest {

    private static AiOutboundPolicyProperties properties(
            AiOutboundLevel defaultLevel, Map<String, AiOutboundPolicyProperties.EndpointPolicy> endpoints) {
        AiOutboundPolicyProperties properties = new AiOutboundPolicyProperties();
        properties.setDefaultLevel(defaultLevel);
        properties.setEndpoints(endpoints);
        return properties;
    }

    private static AiOutboundPolicyProperties.EndpointPolicy policy(AiOutboundLevel level, AiOutboundLevel... allowed) {
        AiOutboundPolicyProperties.EndpointPolicy endpointPolicy = new AiOutboundPolicyProperties.EndpointPolicy();
        endpointPolicy.setLevel(level);
        endpointPolicy.setAllowedResources(Set.of(allowed));
        return endpointPolicy;
    }

    private static void assertBlocked(Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED.getCode());
    }

    @Test
    void unconfiguredEndpointUsesDefaultCeilingAndAllowsOnlyLevelsBelowIt() {
        DefaultAiOutboundPolicy policy = new DefaultAiOutboundPolicy(properties(AiOutboundLevel.L2_INTERNAL, Map.of()));

        assertThat(policy.levelOf(9L)).isEqualTo(AiOutboundLevel.L2_INTERNAL);
        assertThat(policy.allowedResourcesOf(9L))
                .containsExactlyInAnyOrder(AiOutboundLevel.L1_PUBLIC, AiOutboundLevel.L2_INTERNAL);

        policy.assertAllowed(9L, AiOutboundLevel.L1_PUBLIC);
        policy.assertAllowed(9L, AiOutboundLevel.L2_INTERNAL);
        assertBlocked(() -> policy.assertAllowed(9L, AiOutboundLevel.L3_PERSONAL));
        assertBlocked(() -> policy.assertAllowed(9L, AiOutboundLevel.L4_SECRET));
    }

    @Test
    void endpointOverrideNarrowsCeiling() {
        Map<String, AiOutboundPolicyProperties.EndpointPolicy> endpoints = new LinkedHashMap<>();
        endpoints.put("7", policy(AiOutboundLevel.L1_PUBLIC));
        DefaultAiOutboundPolicy policy = new DefaultAiOutboundPolicy(properties(AiOutboundLevel.L4_SECRET, endpoints));

        assertThat(policy.levelOf(7L)).isEqualTo(AiOutboundLevel.L1_PUBLIC);
        policy.assertAllowed(7L, AiOutboundLevel.L1_PUBLIC);
        assertBlocked(() -> policy.assertAllowed(7L, AiOutboundLevel.L2_INTERNAL));
        // 未覆盖的端点仍用默认上限
        assertThat(policy.levelOf(8L)).isEqualTo(AiOutboundLevel.L4_SECRET);
    }

    @Test
    void explicitAllowlistWinsOverDerivedLevels() {
        Map<String, AiOutboundPolicyProperties.EndpointPolicy> endpoints = new LinkedHashMap<>();
        endpoints.put("7", policy(AiOutboundLevel.L3_PERSONAL, AiOutboundLevel.L3_PERSONAL));
        DefaultAiOutboundPolicy policy =
                new DefaultAiOutboundPolicy(properties(AiOutboundLevel.L2_INTERNAL, endpoints));

        policy.assertAllowed(7L, AiOutboundLevel.L3_PERSONAL);
        assertThat(policy.allowedResourcesOf(7L)).containsExactly(AiOutboundLevel.L3_PERSONAL);
        // 显式清单是白名单：即使等级低于上限，未列入也不允许
        assertBlocked(() -> policy.assertAllowed(7L, AiOutboundLevel.L1_PUBLIC));
    }

    @Test
    void missingResourceLevelOrEndpointIsRejected() {
        DefaultAiOutboundPolicy policy = new DefaultAiOutboundPolicy(properties(AiOutboundLevel.L2_INTERNAL, Map.of()));

        assertBlocked(() -> policy.assertAllowed(9L, null));
        // 端点编号缺失时按默认策略判定，不会因为"没填"而放行
        assertBlocked(() -> policy.assertAllowed(null, AiOutboundLevel.L3_PERSONAL));
        assertThat(policy.levelOf(null)).isEqualTo(AiOutboundLevel.L2_INTERNAL);
    }

    @Test
    void levelVocabularyMapsToDataClassification() {
        assertThat(AiOutboundLevel.parse("l3_personal")).contains(AiOutboundLevel.L3_PERSONAL);
        assertThat(AiOutboundLevel.parse(" L4_SECRET ")).contains(AiOutboundLevel.L4_SECRET);
        assertThat(AiOutboundLevel.parse("unknown")).isEmpty();
        assertThat(AiOutboundLevel.parse(null)).isEmpty();
        assertThat(AiOutboundLevel.L1_PUBLIC.within(AiOutboundLevel.L2_INTERNAL))
                .isTrue();
        assertThat(AiOutboundLevel.L4_SECRET.within(AiOutboundLevel.L2_INTERNAL))
                .isFalse();
        assertThat(Optional.of(AiOutboundLevel.L4_SECRET.rank())).contains(4);
    }
}
