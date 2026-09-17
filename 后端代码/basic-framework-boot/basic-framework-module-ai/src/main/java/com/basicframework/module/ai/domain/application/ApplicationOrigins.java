package com.basicframework.module.ai.domain.application;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_ORIGIN_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 应用 Origin 列表的校验与归一化（A01）。
 *
 * <p>只接受**精确来源**：{@code scheme://host[:port]}，scheme 限 http/https，必须带主机名；
 * 路径（除空与 {@code /}）、查询、片段、用户信息与任何通配符（{@code *}）一律拒绝。
 * 归一化规则：scheme 与 host 转小写、去掉默认端口、去重保序——同一条来源只有一种写法，
 * 校验与比较都基于归一化结果，避免 {@code https://A.com} 与 {@code https://a.com:443} 被当成两条。
 */
public final class ApplicationOrigins {

    private ApplicationOrigins() {}

    /** 校验并归一化为 JSON 数组文本（持久化形态）。 */
    public static String normalizeToJson(List<String> origins) {
        return JsonUtils.toJsonString(normalize(origins));
    }

    /** 解析持久化的 JSON 数组文本；脏数据按非法处理，不静默丢弃。 */
    public static List<String> parse(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        List<String> parsed = JsonUtils.parseArray(json, String.class);
        if (parsed == null) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        return List.copyOf(parsed);
    }

    /** 校验并归一化；非法来源（路径、通配、缺少主机等）直接拒绝。 */
    public static List<String> normalize(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String origin : origins) {
            normalized.add(normalizeOne(origin));
        }
        return new ArrayList<>(normalized);
    }

    private static String normalizeOne(String origin) {
        if (!StringUtils.hasText(origin) || origin.contains("*")) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        URI uri;
        try {
            uri = new URI(origin.trim());
        } catch (URISyntaxException exception) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65_535)) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return port == -1 || defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
