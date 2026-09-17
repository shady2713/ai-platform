package com.basicframework.framework.ai.core.http;

import java.time.Duration;
import java.util.Map;

/**
 * 出站请求（服务端构造）。
 *
 * <p>请求头只允许由服务端显式给出：调用方不得把入站请求的头、Cookie 或宿主凭据原样转发。
 * {@link #headers} 中的 {@code Authorization} 等凭据只能来自服务端侧的端点配置（模型密钥、连接器密钥）。
 */
public record ExternalHttpRequest(
        String method, String url, Map<String, String> headers, byte[] body, Duration timeout) {

    /** 常用构造：GET，无请求体。 */
    public static ExternalHttpRequest get(String url) {
        return new ExternalHttpRequest("GET", url, Map.of(), null, null);
    }
}
