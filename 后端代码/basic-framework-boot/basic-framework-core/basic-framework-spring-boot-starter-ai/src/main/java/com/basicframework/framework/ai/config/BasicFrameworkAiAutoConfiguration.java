package com.basicframework.framework.ai.config;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.provider.springai.SpringAiModelClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * AI 能力接缝自动配置。
 *
 * <p>默认不注册任何 Bean：只有显式设置 {@code basic-framework.ai.enabled=true} 时才装配
 * {@link AiProviderValidator}，把配置错误与提供方缺失挡在启动期。
 * 业务模块只消费本接缝发布的契约（{@code core.model}），不感知提供方实现细节。
 */
@AutoConfiguration
@EnableConfigurationProperties({AiProperties.class, AiHttpProperties.class, AiModelProperties.class})
public class BasicFrameworkAiAutoConfiguration {

    /**
     * 启用 AI 能力时注册装配校验器；bean 初始化即完成校验，失败让上下文刷新中止。
     */
    @Bean
    @ConditionalOnProperty(prefix = "basic-framework.ai", name = "enabled", havingValue = "true")
    public AiProviderValidator aiProviderValidator(AiProperties properties, ObjectProvider<ModelPort> modelPorts) {
        return new AiProviderValidator(properties, modelPorts);
    }

    /**
     * 受控出站 HTTP 客户端：所有外部调用（模型、连接器、Webhook）都经由此边界。
     * 默认策略拒绝一切目标，必须通过 {@code basic-framework.ai.http.allowed-hosts} 显式允许。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ExternalHttpClient.class)
    @ConditionalOnProperty(prefix = "basic-framework.ai", name = "enabled", havingValue = "true")
    public ExternalHttpClient aiExternalHttpClient(AiHttpProperties httpProperties) {
        return new GuardedExternalHttpClient(httpProperties);
    }

    /**
     * 受管模型客户端工厂：按端点快照与版本键有界缓存 Spring AI 客户端。
     * 与出站 HTTP 边界共用同一份允许清单策略（AiHttpProperties），
     * 并把 M03 的调用护栏（输出上限、有界重试、流式超时）下发给每个客户端。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ModelClientFactory.class)
    @ConditionalOnProperty(prefix = "basic-framework.ai", name = "enabled", havingValue = "true")
    public ModelClientFactory aiModelClientFactory(AiHttpProperties httpProperties, AiModelProperties modelProperties) {
        return new SpringAiModelClientFactory(httpProperties, modelProperties);
    }
}
