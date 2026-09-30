package com.basicframework.framework.ai.provider.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * MCP 端点的网络准入（X07）：**默认拒绝一切**。
 *
 * <p>与 F09 的出站 HTTP 边界同一精神，但刻意**不复用** {@code GuardedExternalHttpClient}：
 * MCP SDK 自带 JDK {@code HttpClient} 传输（{@code mcp-core} 内置），不经过 F09 的请求器，
 * 因此本类承担 MCP 侧的同等义务——地址、协议、端口、认证都必须在**发出请求之前**判定完。
 *
 * <p>四道闸门（任一不通过即拒绝，且不发出任何网络请求）：
 * <ol>
 *   <li><b>主机白名单</b>：空清单拒绝一切；主机精确匹配（大小写不敏感），不做通配、不做后缀匹配
 *       （"evil-example.com" 不能因为 "example.com" 在清单里就通过）；</li>
 *   <li><b>协议</b>：必须 https。仅当目标是环回/私网地址**且**运维显式开启
 *       {@code allowPrivateTargets} 时才允许 http（用于内网部署与本地验证）；</li>
 *   <li><b>端口白名单</b>：空清单拒绝一切；</li>
 *   <li><b>地址卫生</b>：拒绝携带用户信息、查询串或片段的 baseUri——这三者是把"端点声明"
 *       变成注入通道的经典载体（{@code https://user@host}、{@code https://host?token=...}）。</li>
 * </ol>
 *
 * <p><b>残余风险（与 F09 相同）</b>：本类按主机名白名单放行，不做 DNS 解析后的地址复核，
 * 因此存在 DNS 重绑定窗口。需要更强保证时应在网络层出口网关收敛（不在本卡范围）。
 */
public final class McpEndpointPolicy {

    private final Set<String> allowedHosts;

    private final Set<Integer> allowedPorts;

    private final boolean allowPrivateTargets;

    public McpEndpointPolicy(Set<String> allowedHosts, Set<Integer> allowedPorts, boolean allowPrivateTargets) {
        this.allowedHosts = allowedHosts == null ? Set.of() : lowerCased(allowedHosts);
        this.allowedPorts = allowedPorts == null ? Set.of() : Set.copyOf(allowedPorts);
        this.allowPrivateTargets = allowPrivateTargets;
    }

    private static Set<String> lowerCased(Set<String> values) {
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 校验端点基地址；通过则返回规范化 URI，不通过抛 {@link McpClientException}
     * （终止原因 {@link McpTermination#ADDRESS_DENIED}）。
     */
    public URI requireAllowed(String baseUri) {
        URI uri = parse(baseUri);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 不可解析出主机名（"https://"、含非法字符等）一律拒绝，不尝试"尽力而为"
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点缺少合法主机名");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点不得携带用户信息、查询串或片段");
        }
        if (allowedHosts.isEmpty() || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点主机不在允许清单内");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https) {
            // 非 https 只有在"私网/环回 + 运维显式批准"时才放行；公网 http 一律拒绝
            if (!allowPrivateTargets || !isPrivateLiteral(host)) {
                throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点必须使用 https");
            }
        }
        int port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        if (allowedPorts.isEmpty() || !allowedPorts.contains(port)) {
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点端口不在允许清单内");
        }
        return uri;
    }

    private static URI parse(String baseUri) {
        if (baseUri == null || baseUri.isBlank()) {
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点地址不能为空");
        }
        try {
            return new URI(baseUri.trim());
        } catch (URISyntaxException invalid) {
            // 不把原始地址或异常正文带进消息：地址本身也是信息
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点地址不合法");
        }
    }

    /** 字面量环回/私网主机名（不做 DNS 解析，避免把解析结果当成放行依据）。 */
    private static boolean isPrivateLiteral(String host) {
        // URI.getHost() 对 IPv6 返回带方括号的形式（"[::1]"），先剥掉再判定
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1")) {
            return true;
        }
        if (normalized.startsWith("127.") || normalized.startsWith("10.") || normalized.startsWith("192.168.")) {
            return true;
        }
        if (normalized.startsWith("172.")) {
            // 172.16.0.0/12：取第二段判断是否落在该区间内
            int dot = normalized.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int secondOctet = Integer.parseInt(normalized.substring(4, dot));
                    return secondOctet >= 16 && secondOctet <= 31;
                } catch (NumberFormatException notAnAddress) {
                    return false;
                }
            }
        }
        return false;
    }
}
