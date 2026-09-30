package com.basicframework.framework.ai.config;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.provider.mcp.McpClientAdapter;
import com.basicframework.framework.ai.provider.mcp.McpProperties;
import com.basicframework.framework.ai.provider.mcp.McpSdkSessionFactory;
import com.basicframework.framework.ai.provider.mcp.McpSessionFactory;
import com.basicframework.framework.ai.provider.springai.SpringAiModelClientFactory;
import java.util.List;
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
@EnableConfigurationProperties({AiProperties.class, AiHttpProperties.class, AiModelProperties.class, McpProperties.class
})
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

    /**
     * 受控 MCP 客户端适配器（X07）：协议级工具发现，有界重试、明确终止、只发现不执行。
     *
     * <p><b>刻意不挂在 {@code basic-framework.ai.enabled} 开关下</b>（与本类其它 Bean 不同）：
     * 消费它的 module-ai 侧 MCP 组件是无条件装配的，如果这里条件化，模块侧就会在
     * "AI 未显式启用"的部署里因缺 Bean 而启动失败。更重要的是，这个 Bean 本身是安全的：
     * 协议版本允许清单默认为空，因此它装配上也**拒绝一切发现**——"Bean 存在"不等于"放行了"。
     *
     * <p>出站地址/端口/主机清单不在这里：它由 module-ai 侧的 {@code McpEndpointPolicy} 承担，
     * 因为那里才拿得到 D01 连接器登记的地址与加密凭据。
     *
     * <p>{@link McpSessionFactory} 走 {@link ObjectProvider} 而不是直接 new：传输是可替换的接缝，
     * 部署方（或集成测试）可以注册自己的工厂，而不必替换整个适配器。
     */
    @Bean
    @ConditionalOnMissingBean(McpClientAdapter.class)
    public McpClientAdapter mcpClientAdapter(
            McpProperties mcpProperties, ObjectProvider<McpSessionFactory> sessionFactories) {
        McpSessionFactory sessionFactory = sessionFactories.getIfAvailable(
                () -> new McpSdkSessionFactory(List.copyOf(mcpProperties.getAllowedProtocolVersions())));
        return new McpClientAdapter(
                sessionFactory, mcpProperties.getAllowedProtocolVersions(), mcpProperties.getMaxToolsPerDiscovery());
    }
}
