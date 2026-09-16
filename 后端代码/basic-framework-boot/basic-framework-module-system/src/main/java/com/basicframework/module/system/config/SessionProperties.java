package com.basicframework.module.system.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 管理端会话配置。刷新令牌使用固定绝对有效期，不因刷新而延长。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "basic-framework.security.session")
public class SessionProperties {

    @NotNull
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    @NotNull
    private Duration refreshTokenTtl = Duration.ofDays(30);

    @AssertTrue(message = "会话令牌有效期必须大于 0，且刷新令牌有效期不得短于访问令牌")
    public boolean isValidityPeriodValid() {
        return isPositive(accessTokenTtl)
                && isPositive(refreshTokenTtl)
                && refreshTokenTtl.compareTo(accessTokenTtl) >= 0;
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
