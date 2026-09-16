package com.basicframework.framework.common.util.http;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 工具类
 *
 */
public final class HttpUtils {

    private HttpUtils() {}

    /**
     * 编码 URL 参数
     *
     * @param value 参数
     * @return 编码后的参数
     */
    public static String encodeUtf8(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 解码 URL 参数
     *
     * @param value 参数
     * @return 解码后的参数
     */
    public static String decodeUtf8(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static String removeUrlQuery(String url) {
        if (!StrUtil.contains(url, '?')) {
            return url;
        }
        int queryIndex = url.indexOf('?');
        return url.substring(0, queryIndex);
    }

    /**
     * HTTP post 请求，基于 {@link cn.hutool.http.HttpUtil} 实现
     *
     * 为什么要封装该方法，因为 HttpUtil 默认封装的方法，没有允许传递 headers 参数
     *
     * @param url URL
     * @param headers 请求头
     * @param requestBody 请求体
     * @return 请求结果
     */
    public static String post(String url, Map<String, String> headers, String requestBody) {
        try (HttpResponse response =
                HttpRequest.post(url).addHeaders(headers).body(requestBody).execute()) {
            return response.body();
        }
    }
}
