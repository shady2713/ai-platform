package com.basicframework.module.ai.controller.app.v1.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C05 嵌入策略：允许域只来自应用配置、CSP 收紧、缓存键覆盖应用+配置+主题。 */
class AiEmbedPolicyTest {

    @Test
    void cspLocksEverythingToSelfHostedAndNamesExactAncestors() {
        String csp = AiEmbedPolicy.contentSecurityPolicy(List.of("https://crm.example.com", "https://g2.example.com"));

        assertThat(csp)
                .contains("script-src 'self'")
                .contains("style-src 'self'")
                .contains("object-src 'none'")
                .contains("base-uri 'none'")
                .contains("form-action 'none'")
                .contains("frame-ancestors https://crm.example.com https://g2.example.com");
        // 脚本与样式都不允许内联/求值：主题走 CSS 自定义属性，壳 HTML 里没有内联脚本
        assertThat(csp).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
        assertThat(csp).doesNotContain("frame-ancestors *");
    }

    @Test
    void allowedOriginsDelegateToApplicationOriginsContract() {
        assertThat(AiEmbedPolicy.allowedOrigins("[\"https://crm.example.com\",\"https://a.example.com:8443\"]"))
                .containsExactly("https://crm.example.com", "https://a.example.com:8443");
        // 归一化由 A01 负责：默认端口被去掉，同一来源只有一种写法
        assertThat(AiEmbedPolicy.allowedOrigins("[\"https://crm.example.com:443\"]"))
                .containsExactly("https://crm.example.com");
    }

    @Test
    void dirtyOriginsAreRejectedInsteadOfRelaxed() {
        assertThatThrownBy(() -> AiEmbedPolicy.allowedOrigins("[]")).isInstanceOf(ServiceException.class);
        // 非法 JSON 由 JSON 解析层拒绝；语义非法（空列表/路径/通配）由 A01 的契约层拒绝
        assertThatThrownBy(() -> AiEmbedPolicy.allowedOrigins("not-json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEmbedPolicy.allowedOrigins("[\"https://crm.example.com/path\"]"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiEmbedPolicy.allowedOrigins("[\"*\"]")).isInstanceOf(ServiceException.class);
    }

    @Test
    void cacheKeyCoversApplicationConfigurationAndTheme() {
        String base = AiEmbedPolicy.cacheKey("crm", 3, "fingerprint-a");

        assertThat(base).isEqualTo("embed-crm-3-fingerprint-a");
        // 换应用、换配置版本、换主题指纹都必须产生不同的键
        assertThat(AiEmbedPolicy.cacheKey("other", 3, "fingerprint-a")).isNotEqualTo(base);
        assertThat(AiEmbedPolicy.cacheKey("crm", 4, "fingerprint-a")).isNotEqualTo(base);
        assertThat(AiEmbedPolicy.cacheKey("crm", 3, "fingerprint-b")).isNotEqualTo(base);
        // 缺失值有稳定写法，不会与真实取值混淆
        assertThat(AiEmbedPolicy.cacheKey("crm", null, null)).isEqualTo("embed-crm-0-none");
        assertThat(AiEmbedPolicy.cacheKey("crm", 3, "")).isEqualTo("embed-crm-3-none");
    }

    @Test
    void etagIsQuotedStrongValidator() {
        assertThat(AiEmbedPolicy.etag(AiEmbedPolicy.cacheKey("crm", 1, "f"))).isEqualTo("\"embed-crm-1-f\"");
    }

    @Test
    void protocolVersionMatchesFrozenContract() {
        assertThat(AiEmbedPolicy.PROTOCOL_VERSION).isEqualTo("1.0");
    }
}
