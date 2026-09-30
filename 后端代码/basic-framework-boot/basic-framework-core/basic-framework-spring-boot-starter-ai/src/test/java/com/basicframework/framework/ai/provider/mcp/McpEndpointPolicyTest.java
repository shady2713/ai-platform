package com.basicframework.framework.ai.provider.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * MCP 端点准入（默认拒绝）。
 *
 * <p>这是"网络地址默认拒绝"的直接证据：空清单拒绝一切，未命中拒绝，非 https 拒绝，
 * 私网未批准拒绝，且**拒绝发生在任何网络动作之前**（本类不持有任何传输）。
 */
class McpEndpointPolicyTest {

    private static McpEndpointPolicy policy(String hosts, Integer... ports) {
        return new McpEndpointPolicy(java.util.Set.of(hosts), java.util.Set.of(ports), false);
    }

    @Test
    void emptyAllowlistDeniesEverything() {
        McpEndpointPolicy empty = new McpEndpointPolicy(java.util.Set.of(), java.util.Set.of(443), false);
        assertThatThrownBy(() -> empty.requireAllowed("https://mcp.example.com"))
                .isInstanceOf(McpClientException.class)
                .extracting(failure -> ((McpClientException) failure).termination())
                .isEqualTo(McpTermination.ADDRESS_DENIED);
    }

    @Test
    void emptyPortAllowlistDeniesEvenAllowedHost() {
        McpEndpointPolicy noPorts =
                new McpEndpointPolicy(java.util.Set.of("mcp.example.com"), java.util.Set.of(), false);
        assertThatThrownBy(() -> noPorts.requireAllowed("https://mcp.example.com"))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
    }

    @Test
    void hostNotInAllowlistIsDenied() {
        assertThatThrownBy(() -> policy("mcp.example.com", 443).requireAllowed("https://evil.example.com"))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
    }

    @Test
    void suffixLookalikeHostIsDeniedBecauseMatchingIsExact() {
        // 后缀/子串相近的主机不得因为"看起来像"而被放行
        assertThatThrownBy(() -> policy("example.com", 443).requireAllowed("https://evil-example.com"))
                .isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> policy("example.com", 443).requireAllowed("https://example.com.evil.net"))
                .isInstanceOf(McpClientException.class);
    }

    @Test
    void allowedHttpsEndpointPassesAndHostMatchIsCaseInsensitive() {
        assertThat(policy("MCP.Example.COM", 443).requireAllowed("https://mcp.example.com"))
                .isNotNull();
        assertThat(policy("mcp.example.com", 443).requireAllowed("https://MCP.EXAMPLE.COM"))
                .isNotNull();
    }

    @Test
    void defaultPortIsDerivedWhenAbsent() {
        // https 不带端口按 443 判定；白名单里没有 443 就拒绝
        assertThatThrownBy(() -> policy("mcp.example.com", 8443).requireAllowed("https://mcp.example.com"))
                .isInstanceOf(McpClientException.class);
    }

    @Test
    void publicHttpIsDeniedEvenWhenHostAllowed() {
        assertThatThrownBy(() -> policy("mcp.example.com", 443).requireAllowed("http://mcp.example.com"))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
    }

    @Test
    void privateTargetsRequireExplicitApproval() {
        McpEndpointPolicy strict = new McpEndpointPolicy(java.util.Set.of("127.0.0.1"), java.util.Set.of(8080), false);
        assertThatThrownBy(() -> strict.requireAllowed("http://127.0.0.1:8080")).isInstanceOf(McpClientException.class);

        McpEndpointPolicy approved = new McpEndpointPolicy(java.util.Set.of("127.0.0.1"), java.util.Set.of(8080), true);
        assertThat(approved.requireAllowed("http://127.0.0.1:8080")).isNotNull();
    }

    @Test
    void privateLiteralClassification() {
        assertThat(allowPrivate("http://localhost:8080")).isTrue();
        assertThat(allowPrivate("http://127.0.0.1:8080")).isTrue();
        assertThat(allowPrivate("http://127.1.2.3:8080")).isTrue();
        assertThat(allowPrivate("http://10.0.0.5:8080")).isTrue();
        assertThat(allowPrivate("http://192.168.1.5:8080")).isTrue();
        assertThat(allowPrivate("http://172.16.0.1:8080")).isTrue();
        assertThat(allowPrivate("http://172.31.255.1:8080")).isTrue();
        // 172.32 已在 172.16/12 之外；172.abc 不是地址；无第二段也不算私网字面量
        assertThat(allowPrivate("http://172.32.0.1:8080")).isFalse();
        assertThat(allowPrivate("http://172.abc.0.1:8080")).isFalse();
        assertThat(allowPrivate("http://172.16:8080")).isFalse();
        assertThat(allowPrivate("http://8.8.8.8:8080")).isFalse();
        assertThat(allowPrivate("http://[::1]:8080")).isTrue();
    }

    /**
     * 在"私网已批准"的前提下判断该地址是否会被放行。
     *
     * <p>白名单是精确匹配，因此这里从待测地址里取出真实主机再放进白名单——
     * 否则测的就不是"私网字面量判定"，而是"主机没命中白名单"了。
     */
    private static boolean allowPrivate(String baseUri) {
        java.net.URI uri = java.net.URI.create(baseUri);
        // 注意：URI.getHost() 对非 IP 字面量（如 "172.16"）返回 null，改用 authority 兜底，
        // 否则 Set.of(null) 会先在这里炸掉，测不到被测逻辑。
        String host = uri.getHost() == null ? uri.getAuthority() : uri.getHost();
        McpEndpointPolicy lenient =
                new McpEndpointPolicy(java.util.Set.of(host), java.util.Set.of(uri.getPort()), true);
        try {
            lenient.requireAllowed(baseUri);
            return true;
        } catch (McpClientException denied) {
            return false;
        }
    }

    @Test
    void addressCarryingUserInfoQueryOrFragmentIsDenied() {
        McpEndpointPolicy allowed = policy("mcp.example.com", 443);
        assertThatThrownBy(() -> allowed.requireAllowed("https://user@mcp.example.com"))
                .isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> allowed.requireAllowed("https://mcp.example.com?token=secret"))
                .isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> allowed.requireAllowed("https://mcp.example.com#frag"))
                .isInstanceOf(McpClientException.class);
    }

    @Test
    void blankOrHostlessAddressIsDenied() {
        McpEndpointPolicy allowed = policy("mcp.example.com", 443);
        assertThatThrownBy(() -> allowed.requireAllowed(null)).isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> allowed.requireAllowed("   ")).isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> allowed.requireAllowed("https://")).isInstanceOf(McpClientException.class);
    }

    @Test
    void unparsableAddressIsDeniedWithoutLeakingIt() {
        McpEndpointPolicy allowed = policy("mcp.example.com", 443);
        assertThatThrownBy(() -> allowed.requireAllowed("ht!tp://[bad"))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> {
                    assertThat(((McpClientException) failure).termination()).isEqualTo(McpTermination.ADDRESS_DENIED);
                    // 拒绝消息不得回显地址本身（拒绝消息不应成为探测通道）
                    assertThat(failure.getMessage()).doesNotContain("bad");
                });
    }

    @Test
    void nullSetsInConstructorCollapseToDenyAll() {
        McpEndpointPolicy nulls = new McpEndpointPolicy(null, null, false);
        assertThatThrownBy(() -> nulls.requireAllowed("https://mcp.example.com"))
                .isInstanceOf(McpClientException.class);
    }
}
