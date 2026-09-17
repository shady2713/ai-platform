package com.basicframework.framework.ai.config;

import com.basicframework.framework.ai.core.model.ModelCapability;
import jakarta.validation.constraints.AssertTrue;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 能力接缝配置。
 *
 * <p>默认关闭：未显式设置 {@code basic-framework.ai.enabled=true} 时不装配任何模型相关能力，
 * 缺失配置即拒绝启用，符合 fail-closed 取向。
 *
 * <p>启用后必须显式声明平台需要的能力集合（{@code capabilities}），装配期由
 * {@link AiProviderValidator} 校验提供方实现是否覆盖全部声明能力；任一项缺失在启动期失败，
 * 不留到首次请求。
 */
@Validated
@ConfigurationProperties(prefix = "basic-framework.ai")
public class AiProperties {

    /** 是否启用 AI 能力；默认关闭。 */
    private boolean enabled = false;

    /** 平台声明需要的模型能力集合；启用时必须非空。 */
    private Set<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);

    /**
     * 启用时能力集合必须非空：配置开关与能力声明必须成对出现，避免启用了一个没有需求的空能力面。
     */
    @AssertTrue(message = "启用 AI 能力时必须通过 basic-framework.ai.capabilities 声明至少一项能力")
    public boolean isCapabilityDeclaredWhenEnabled() {
        return !enabled || !capabilities.isEmpty();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<ModelCapability> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Set<ModelCapability> capabilities) {
        this.capabilities = capabilities;
    }
}
