package com.basicframework.framework.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 模型调用护栏配置（M03 建立，M04 增加嵌入批次上限）：输出大小、重试、流式超时与批次上限的
 * 唯一配置来源。
 *
 * <p>所有护栏都有保守默认值；越界配置在启动期失败（fail-closed），不留到首次调用。
 */
@Validated
@Data
@ConfigurationProperties(prefix = "basic-framework.ai.model")
public class AiModelProperties {

    /** 单次调用的最大尝试次数（含首次）；只在可重试失败上生效。 */
    @Min(1)
    @Max(5)
    private int maxAttempts = 3;

    /** 重试之间的固定等待时长。 */
    private Duration retryBackoff = Duration.ofMillis(200);

    /** 单次调用允许的输出字符上限（文本与工具调用参数合计）；超出即中断并给出稳定错误。 */
    @Min(1024)
    private int maxOutputChars = 262_144;

    /** 流式输出的事件间空闲超时；超过该时长没有任何事件即判定超时并关闭流。 */
    private Duration streamIdleTimeout = Duration.ofSeconds(30);

    /** 流式事件队列容量：生产端快于消费端时形成有界背压，不无限占用内存。 */
    @Min(1)
    @Max(4096)
    private int streamQueueCapacity = 64;

    /** 结构化输出的最大修复步数（0 表示不做修复，直接判定）；实现支持的类型数为上限。 */
    @Min(0)
    @Max(3)
    private int maxRepairSteps = 3;

    /** 单次嵌入调用的最大文本条数；超出即拒绝（批次长度异常属输入错误，不重发）。 */
    @Min(1)
    @Max(2048)
    private int maxEmbeddingBatch = 64;
}
