package com.basicframework.module.infra.framework.file.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 文件外部存储清理的批量与退避策略。 */
@Data
@Validated
@ConfigurationProperties(prefix = "basic-framework.infra.file-deletion")
public class FileDeletionProperties {

    @Min(1)
    @Max(1000)
    private int batchSize = 100;

    @NotNull
    private Duration initialRetryDelay = Duration.ofMinutes(1);

    @NotNull
    private Duration maxRetryDelay = Duration.ofHours(24);

    @AssertTrue(message = "文件删除重试间隔必须为正数，且最大间隔不得小于初始间隔")
    public boolean isRetryDelayValid() {
        return !initialRetryDelay.isNegative()
                && !initialRetryDelay.isZero()
                && !maxRetryDelay.isNegative()
                && !maxRetryDelay.isZero()
                && maxRetryDelay.compareTo(initialRetryDelay) >= 0;
    }
}
