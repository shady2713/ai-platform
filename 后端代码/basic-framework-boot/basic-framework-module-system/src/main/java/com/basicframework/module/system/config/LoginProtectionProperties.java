package com.basicframework.module.system.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 账号密码登录的失败计数与短时锁定配置。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "basic-framework.security.login-protection")
public class LoginProtectionProperties {

    @Min(2)
    @Max(20)
    private int maxFailedAttempts = 5;

    @NotNull
    private Duration lockDuration = Duration.ofMinutes(15);

    @AssertTrue(message = "login-protection.lock-duration 必须在 1 分钟到 24 小时之间")
    public boolean isLockDurationValid() {
        return lockDuration != null
                && lockDuration.compareTo(Duration.ofMinutes(1)) >= 0
                && lockDuration.compareTo(Duration.ofHours(24)) <= 0;
    }
}
