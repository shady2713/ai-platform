package com.basicframework.framework.ai.core.http;

import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 出站 HTTP 传输边界策略（F09 冻结）。
 *
 * <p>默认**拒绝一切出站目标**：{@link #allowedHosts} 为空时任何请求都被拒绝（fail-closed）。
 * 企业内网目标必须同时出现在 {@link #allowedHosts} 并把 {@link #allowPrivateTargets} 置为 true，
 * 表示该目标已被显式批准；其余私网/环回地址一律拒绝。
 */
@Validated
@Data
@ConfigurationProperties(prefix = "basic-framework.ai.http")
public class AiHttpProperties {

    /** 允许的出站主机名（精确匹配，不支持下级通配）；默认空 = 全部拒绝。 */
    private List<String> allowedHosts = List.of();

    /** 允许的出站端口；默认只允许 443。 */
    private List<Integer> allowedPorts = List.of(443);

    /** 是否允许连接到显式批准的内网/环回目标（仅用于企业内网模型或连接器）。 */
    private boolean allowPrivateTargets = false;

    /** 连接超时。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 读取（响应）超时。 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** 响应体上限，超出即中断并报稳定错误。 */
    private int maxResponseBytes = 1_048_576;

    /** 单次请求的请求头数量上限（防止头部膨胀）。 */
    private int maxHeaderCount = 32;
}
