package com.basicframework.module.infra.framework.retention.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** infra 运行日志保留策略。 */
@Data
@Validated
@ConfigurationProperties(prefix = "basic-framework.infra.data-retention")
public class InfraDataRetentionProperties {

    @Min(1)
    @Max(3650)
    private int accessLogDays = 14;

    @Min(1)
    @Max(3650)
    private int errorLogDays = 14;

    @Min(1)
    @Max(3650)
    private int jobLogDays = 14;

    @Min(10)
    @Max(5000)
    private int batchSize = 100;

    @Min(1)
    @Max(1000)
    private int maxBatches = 100;
}
