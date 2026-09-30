package com.basicframework.module.ai.service.tool;

import com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端准入配置（X07）：<b>默认拒绝一切</b>。
 *
 * <p>沿用 F09 的出站策略形态（空清单拒绝一切），因为"未配置即放行"在 MCP 场景后果更重：
 * 一个没配过白名单的部署，只要能连上就会开始对任意 MCP 服务器发握手请求。
 *
 * <p>三个开关的默认取值刻意都是"最严"：
 * <ul>
 *   <li>{@code allowed-hosts} 空 → 拒绝一切主机；</li>
 *   <li>{@code allowed-ports} 空 → 拒绝一切端口；</li>
 *   <li>{@code allowed-protocol-versions} 空 → 拒绝一切协议版本（连"先连上再协商"都不允许）。</li>
 * </ul>
 *
 * <p>{@code allow-anonymous-servers} 默认 false：MCP 服务器通常持有真实数据面，
 * 匿名访问必须由运维显式放行。
 */
@Component
@ConfigurationProperties(prefix = "basic-framework.ai.mcp")
public class McpClientAccessPolicy {

    /** 允许的主机清单（精确匹配，不支持通配与后缀匹配）。 */
    private Set<String> allowedHosts = Set.of();

    /** 允许的端口清单。 */
    private Set<Integer> allowedPorts = Set.of();

    /** 允许的 MCP 协议版本。 */
    private Set<String> allowedProtocolVersions = Set.of();

    /** 是否允许私网/环回目标（用于内网部署与本地验证；公网 http 始终拒绝）。 */
    private boolean allowPrivateTargets;

    /** 是否允许匿名（无凭据）访问 MCP 服务器。 */
    private boolean allowAnonymousServers;

    /** 单次发现允许的工具条数上限（超出即拒绝，不截断）。 */
    private int maxToolsPerDiscovery = 200;

    /** 单次发现的有界尝试次数（会被端点记录再夹紧到 1..5）。 */
    private int maxAttempts = 2;

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
    }

    public Set<Integer> getAllowedPorts() {
        return allowedPorts;
    }

    public void setAllowedPorts(Set<Integer> allowedPorts) {
        this.allowedPorts = allowedPorts == null ? Set.of() : Set.copyOf(allowedPorts);
    }

    public Set<String> getAllowedProtocolVersions() {
        return allowedProtocolVersions;
    }

    public void setAllowedProtocolVersions(Set<String> allowedProtocolVersions) {
        this.allowedProtocolVersions = allowedProtocolVersions == null ? Set.of() : Set.copyOf(allowedProtocolVersions);
    }

    public boolean isAllowPrivateTargets() {
        return allowPrivateTargets;
    }

    public void setAllowPrivateTargets(boolean allowPrivateTargets) {
        this.allowPrivateTargets = allowPrivateTargets;
    }

    public boolean isAllowAnonymousServers() {
        return allowAnonymousServers;
    }

    public void setAllowAnonymousServers(boolean allowAnonymousServers) {
        this.allowAnonymousServers = allowAnonymousServers;
    }

    public int getMaxToolsPerDiscovery() {
        return maxToolsPerDiscovery;
    }

    public void setMaxToolsPerDiscovery(int maxToolsPerDiscovery) {
        this.maxToolsPerDiscovery = maxToolsPerDiscovery;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** 装配出站准入策略。 */
    public McpEndpointPolicy endpointPolicy() {
        return new McpEndpointPolicy(allowedHosts, allowedPorts, allowPrivateTargets);
    }

    /** 装配协议版本允许清单（空集合会让适配器拒绝一切发现）。 */
    public List<String> protocolVersions() {
        return allowedProtocolVersions == null ? List.of() : List.copyOf(allowedProtocolVersions);
    }
}
