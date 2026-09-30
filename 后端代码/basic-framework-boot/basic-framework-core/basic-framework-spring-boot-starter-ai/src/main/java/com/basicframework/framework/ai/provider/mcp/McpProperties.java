package com.basicframework.framework.ai.provider.mcp;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 客户端配置（X07）：{@code basic-framework.ai.mcp.*}。
 *
 * <p><b>默认拒绝一切</b>：空的允许清单意味着没有任何 MCP 服务器、任何协议版本被批准。
 * 这是刻意的——MCP 服务器通常持有真实数据面，"没配过就先连上看看"是最危险的默认值。
 *
 * <p>与 F09 的 {@code AiHttpProperties} 同形但**独立**：MCP 走 SDK 自带的 JDK HttpClient 传输，
 * 不经过 F09 的请求器，因此需要自己的清单。两份清单刻意不共享，避免"放行了 HTTP 就等于
 * 放行了 MCP"这种跨协议的权限渗透。
 */
@ConfigurationProperties(prefix = "basic-framework.ai.mcp")
public class McpProperties {

    /** 允许的 MCP 协议版本（空 = 拒绝一切版本）。 */
    private Set<String> allowedProtocolVersions = new LinkedHashSet<>();

    /** 单次发现允许的工具条数上限（超出即拒绝，不截断）。 */
    private int maxToolsPerDiscovery = 200;

    public Set<String> getAllowedProtocolVersions() {
        return allowedProtocolVersions;
    }

    public void setAllowedProtocolVersions(Set<String> allowedProtocolVersions) {
        this.allowedProtocolVersions =
                allowedProtocolVersions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedProtocolVersions);
    }

    public int getMaxToolsPerDiscovery() {
        return maxToolsPerDiscovery;
    }

    public void setMaxToolsPerDiscovery(int maxToolsPerDiscovery) {
        this.maxToolsPerDiscovery = maxToolsPerDiscovery;
    }
}
