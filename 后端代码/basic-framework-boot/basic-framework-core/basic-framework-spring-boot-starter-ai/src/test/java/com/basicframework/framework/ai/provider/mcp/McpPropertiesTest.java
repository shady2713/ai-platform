package com.basicframework.framework.ai.provider.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * MCP 客户端配置：默认拒绝一切。
 *
 * <p>这些 getter/setter 是"默认拒绝"落到配置上的唯一入口，必须有断言而不是靠阅读。
 */
class McpPropertiesTest {

    @Test
    void defaultsDenyEveryProtocolVersion() {
        McpProperties properties = new McpProperties();
        assertThat(properties.getAllowedProtocolVersions()).isEmpty();
        assertThat(properties.getMaxToolsPerDiscovery()).isEqualTo(200);
    }

    @Test
    void settersRoundTripAndNormalizeNullToEmpty() {
        McpProperties properties = new McpProperties();

        properties.setAllowedProtocolVersions(new LinkedHashSet<>(Set.of("2025-06-18")));
        assertThat(properties.getAllowedProtocolVersions()).containsExactly("2025-06-18");

        // null 归一为空集合：否则下游会拿到 null 并在"是否放行版本"上抛 NPE
        properties.setAllowedProtocolVersions(null);
        assertThat(properties.getAllowedProtocolVersions()).isEmpty();

        properties.setMaxToolsPerDiscovery(50);
        assertThat(properties.getMaxToolsPerDiscovery()).isEqualTo(50);
    }
}
