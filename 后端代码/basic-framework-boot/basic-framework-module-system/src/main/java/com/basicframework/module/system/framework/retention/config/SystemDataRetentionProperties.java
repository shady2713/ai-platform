package com.basicframework.module.system.framework.retention.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** system 审计、通信与消息记录保留策略。 */
@Data
@Validated
@ConfigurationProperties(prefix = "basic-framework.system.data-retention")
public class SystemDataRetentionProperties {

    @Min(1)
    @Max(3650)
    private int loginLogDays = 180;

    @Min(1)
    @Max(3650)
    private int operateLogDays = 180;

    @Min(1)
    @Max(3650)
    private int smsLogDays = 30;

    /** 短信验证码只增不减会无限膨胀；验证码分钟级失效，保留期远大于有效期即可安全清理。 */
    @Min(1)
    @Max(3650)
    private int smsCodeDays = 7;

    @Min(1)
    @Max(3650)
    private int readNotifyMessageDays = 90;

    @Min(10)
    @Max(5000)
    private int batchSize = 500;

    @Min(1)
    @Max(1000)
    private int maxBatches = 20;
}
