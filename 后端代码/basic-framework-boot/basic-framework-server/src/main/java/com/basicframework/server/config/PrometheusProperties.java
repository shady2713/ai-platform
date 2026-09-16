package com.basicframework.server.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Prometheus 独立抓取凭据；默认关闭，启用时必须显式注入 256 bit 十六进制随机令牌。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "basic-framework.monitoring.prometheus")
public class PrometheusProperties {

    private boolean enabled = false;
    private String token;

    @AssertTrue(message = "Prometheus 启用时必须配置 64 位十六进制独立随机令牌")
    public boolean isAuthenticationConfigured() {
        if (token == null || token.isBlank()) {
            return !enabled;
        }
        return token.matches("[a-fA-F0-9]{64}");
    }
}
