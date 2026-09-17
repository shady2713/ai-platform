package com.basicframework.module.ai.domain.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 模型外发策略与计量配置（M05）。
 *
 * <p>**默认拒绝敏感数据**：未显式配置的端点使用 {@link #defaultLevel}（默认 L2 内部）作为上限，
 * 允许的资源等级是"不高于上限的全部等级"；L3/L4 必须在端点条目里显式列入
 * {@code allowed-resources} 才会外发。未识别的等级字符串在启动期失败，不做静默降级。
 *
 * <pre>
 * basic-framework.ai.outbound:
 *   default-level: L2_INTERNAL
 *   endpoints:
 *     "1":
 *       level: L1_PUBLIC
 *       allowed-resources: [L1_PUBLIC]
 *   metering:
 *     estimate-when-missing: false
 *     chars-per-token: 4
 * </pre>
 */
@Validated
@Component
@ConfigurationProperties(prefix = "basic-framework.ai.outbound")
@Data
public class AiOutboundPolicyProperties {

    /** 未显式配置端点时的外发上限；默认 L2 内部。 */
    @NotNull
    private AiOutboundLevel defaultLevel = AiOutboundLevel.L2_INTERNAL;

    /** 端点级策略：key 为端点编号字符串。 */
    @Valid
    private Map<String, EndpointPolicy> endpoints = new LinkedHashMap<>();

    /** 计量配置。 */
    @Valid
    @NotNull
    private Metering metering = new Metering();

    /** 端点级外发策略。 */
    @Data
    public static class EndpointPolicy {

        /** 该端点允许接收的最高数据等级。 */
        @NotNull
        private AiOutboundLevel level = AiOutboundLevel.L2_INTERNAL;

        /** 显式允许外发的资源等级；为空表示"不高于 level 的全部等级"。 */
        private java.util.Set<AiOutboundLevel> allowedResources = java.util.Set.of();
    }

    /** 计量配置。 */
    @Data
    public static class Metering {

        /**
         * 上游缺失 usage 时是否按字符数估算：默认关闭。
         *
         * <p>估算值只用于展示与预算护栏，标记 {@code estimated=true}，不参与真实计量与结算。
         */
        private boolean estimateWhenMissing = false;

        /** 估算用的每 token 字符数（仅在上游缺失且开启估算时使用）。 */
        @Min(1)
        private int charsPerToken = 4;
    }
}
