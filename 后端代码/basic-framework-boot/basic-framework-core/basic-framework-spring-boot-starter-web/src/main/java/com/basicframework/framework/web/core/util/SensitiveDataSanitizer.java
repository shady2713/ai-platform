package com.basicframework.framework.web.core.util;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 日志敏感数据脱敏器。
 *
 * <p>字段名匹配忽略大小写、下划线和连字符。默认删除凭证（含验证码校验凭证）、L4 敏感数据及 L3 直接联系方式
 * （手机号、电话、邮箱）；调用方可通过 additionalKeys 扩展接口特有字段。JSON 解析失败时返回固定占位内容，禁止回退原文。
 */
@Slf4j
@UtilityClass
public class SensitiveDataSanitizer {

    public static final String SANITIZE_FAILURE_PLACEHOLDER = "{\"_sanitized\":true}";

    public static final String UNMAPPED_REQUEST_PATH = "[unmapped]";

    private static final Set<String> EXACT_SENSITIVE_KEYS = Set.of(
            "authorization",
            "cookie",
            "setcookie",
            "sessionid",
            "jsessionid",
            "captchaverification",
            "otp",
            "smscode",
            "emailcode",
            "verificationcode",
            "requestkey",
            "responsekey",
            "encryptionkey",
            "signingkey",
            "sessionkey",
            "idcard",
            "bankcard",
            "creditcard",
            "cardnumber",
            "cvv",
            "email",
            "emailaddress",
            "mobile",
            "mobilephone",
            "phone",
            "telephone");

    private static final Set<String> SENSITIVE_KEY_SUFFIXES = Set.of(
            "password",
            "token",
            "secret",
            "credential",
            "credentials",
            "apikey",
            "accesskey",
            "accesskeyid",
            "privatekey",
            "secretkey");

    /**
     * 删除 Map 中的敏感字段并序列化。
     *
     * @param map 待处理 Map
     * @param additionalKeys 接口特有敏感字段
     * @return 脱敏后的 JSON；空 Map 返回 null
     */
    public static String sanitizeMap(Map<String, ?> map, String... additionalKeys) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Set<String> normalizedAdditionalKeys = normalizeKeys(additionalKeys);
        Map<String, Object> sanitizedMap = new LinkedHashMap<>(map.size());
        map.forEach((key, value) -> {
            if (!isSensitiveKey(key, normalizedAdditionalKeys)) {
                sanitizedMap.put(key, value);
            }
        });
        return JsonUtils.toJsonString(sanitizedMap);
    }

    /**
     * 递归删除 JSON 中的敏感字段。
     *
     * @param jsonString 待处理 JSON
     * @param additionalKeys 接口特有敏感字段
     * @return 脱敏后的 JSON；空内容返回 null；非法 JSON 返回固定占位内容
     */
    public static String sanitizeJson(String jsonString, String... additionalKeys) {
        if (jsonString == null || jsonString.isBlank()) {
            return null;
        }
        try {
            JsonNode rootNode = JsonUtils.getObjectMapper().readTree(jsonString);
            sanitizeJson(rootNode, normalizeKeys(additionalKeys));
            return JsonUtils.toJsonString(rootNode);
        } catch (Exception ex) {
            log.warn(
                    "[sanitizeJson][payloadLength({}) 脱敏失败，降级为占位内容，exception({})]",
                    jsonString.length(),
                    ex.getClass().getSimpleName());
            return SANITIZE_FAILURE_PLACEHOLDER;
        }
    }

    /**
     * 递归删除响应 data 中的敏感字段，同时保留响应状态元数据。
     *
     * @param commonResult 通用响应
     * @param additionalKeys 接口特有敏感字段
     * @return 脱敏后的 JSON；空响应返回 null；处理失败返回固定占位内容
     */
    public static String sanitizeResult(CommonResult<?> commonResult, String... additionalKeys) {
        if (commonResult == null) {
            return null;
        }
        try {
            String jsonString = JsonUtils.toJsonString(commonResult);
            JsonNode rootNode = JsonUtils.getObjectMapper().readTree(jsonString);
            sanitizeJson(rootNode.get("data"), normalizeKeys(additionalKeys));
            return JsonUtils.toJsonString(rootNode);
        } catch (Exception ex) {
            log.warn(
                    "[sanitizeResult][resultCode({}) 脱敏失败，降级为占位内容，exceptionName({})]",
                    commonResult.getCode(),
                    ex.getClass().getName());
            return SANITIZE_FAILURE_PLACEHOLDER;
        }
    }

    /**
     * 将请求路径转换为服务端路由模板，避免把路径变量中的个人信息或凭据写入日志。
     *
     * @param request 当前 HTTP 请求
     * @return 已匹配的路由模板；未匹配请求返回固定标识
     */
    public static String sanitizeRequestPath(HttpServletRequest request) {
        Object matchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return matchingPattern instanceof String pattern && StringUtils.hasText(pattern)
                ? pattern
                : UNMAPPED_REQUEST_PATH;
    }

    private static void sanitizeJson(JsonNode node, Set<String> additionalKeys) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(childNode -> sanitizeJson(childNode, additionalKeys));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> iterator = node.properties().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (isSensitiveKey(entry.getKey(), additionalKeys)) {
                iterator.remove();
            } else {
                sanitizeJson(entry.getValue(), additionalKeys);
            }
        }
    }

    private static boolean isSensitiveKey(String key, Set<String> additionalKeys) {
        String normalizedKey = normalizeKey(key);
        return additionalKeys.contains(normalizedKey)
                || EXACT_SENSITIVE_KEYS.contains(normalizedKey)
                || SENSITIVE_KEY_SUFFIXES.stream().anyMatch(normalizedKey::endsWith);
    }

    private static Set<String> normalizeKeys(String[] keys) {
        if (keys == null || keys.length == 0) {
            return Set.of();
        }
        Set<String> normalizedKeys = new HashSet<>(keys.length);
        Arrays.stream(keys)
                .map(SensitiveDataSanitizer::normalizeKey)
                .filter(key -> !key.isEmpty())
                .forEach(normalizedKeys::add);
        return normalizedKeys;
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
