package com.basicframework.framework.ai.provider.mcp;

import java.time.Duration;

/**
 * 一个 MCP 服务器的连接端点声明（X07）。
 *
 * <p>这是**声明**而不是自由文本：地址、认证、超时、重试上限全部是结构化字段，
 * 便于在网络边界上逐项校验与审计（对应"网络地址、授权令牌、超时默认拒绝"）。
 *
 * <p>规范化构造器里的归一是刻意的：端点声明可能来自数据库或管理端输入，
 * 缺失/越界的值一律**收敛到受控默认或被夹紧**，而不是让 {@code null} 或 0 透传到传输层
 * （0 毫秒超时会让"未配置"变成"立即失败"，负值与超大重试次数会变成事实上的无限重连）。
 *
 * @param baseUri           基地址（必须为 https，由 {@link McpEndpointPolicy} 校验）
 * @param authorization     完整的 Authorization 头值；无认证时为 {@code null}
 * @param connectTimeout    连接超时
 * @param requestTimeout    单次请求超时
 * @param maxAttempts       最大尝试次数（含首次），夹紧到 1..{@value #MAX_ATTEMPTS_CEILING}
 */
public record McpServerEndpoint(
        String baseUri, String authorization, Duration connectTimeout, Duration requestTimeout, int maxAttempts) {

    /** 重试次数硬上限：即使声明更大也按此夹紧，"有界"由类型保证而不是靠调用方自觉。 */
    public static final int MAX_ATTEMPTS_CEILING = 5;

    /** 重试次数下限：至少一次尝试。 */
    public static final int MIN_ATTEMPTS = 1;

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public McpServerEndpoint {
        baseUri = baseUri == null ? "" : baseUri.trim();
        authorization = authorization == null || authorization.isBlank() ? null : authorization.trim();
        // 归一：缺失/非正数收敛到默认值（不把"未配置"变成 0 毫秒立即失败）
        connectTimeout = positiveOrDefault(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        // 归一：请求超时设硬上限，防止声明一个"实际上等于不超时"的端点
        requestTimeout = positiveOrDefault(requestTimeout, MAX_REQUEST_TIMEOUT);
        if (requestTimeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            requestTimeout = MAX_REQUEST_TIMEOUT;
        }
        if (maxAttempts < MIN_ATTEMPTS) {
            maxAttempts = MIN_ATTEMPTS;
        }
        if (maxAttempts > MAX_ATTEMPTS_CEILING) {
            maxAttempts = MAX_ATTEMPTS_CEILING;
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    /** 是否声明了认证令牌（无令牌时由上层决定是否允许匿名发现）。 */
    public boolean hasAuthorization() {
        return authorization != null;
    }
}
