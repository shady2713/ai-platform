package com.basicframework.module.ai.adapter.connector.http;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_ORIGIN_MISMATCH;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.service.connector.AiConnectorConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 声明式 HTTP 连接器执行器（D02）。
 *
 * <p>安全与有界约束（对应 AT-038–AT-041）：
 * <ul>
 *   <li><b>URL 不可由调用方替换</b>：只使用"连接器 baseUrl + 操作路径模板"，且解析后的地址必须与
 *       baseUrl **同源**（协议/主机/端口一致），否则拒绝（SSRF 防线）；</li>
 *   <li><b>请求头不可由调用方替换</b>：头只来自连接器自身的认证配置（{@link AiConnectorAuthHeaders}），
 *       导入时已丢弃 header 参数；</li>
 *   <li><b>参数值受限</b>：只接受声明过的参数名，且取值必须是安全标量（无引号/斜杠/查询语法），
 *       禁止调用方注入查询串片段；</li>
 *   <li><b>分页有界</b>：页数上限固定（声明值再受平台上限约束），命中重复游标立即停止，
 *       达到页数上限时结论为 PARTIAL（不是"成功全量"）；</li>
 *   <li><b>失败稳定</b>：上游/策略失败只返回稳定原因码，不返回上游正文与凭据。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AiHttpConnectorExecutor {

    /** 平台级页数上限（声明值不得超过它）。 */
    public static final int MAX_PAGES_LIMIT = 20;

    /** 单条条目长度上限（超出截断标记，避免把大对象整体带进上下文）。 */
    private static final int MAX_ITEM_LENGTH = 4_000;

    /** 条目数量上限（跨页累计）。 */
    private static final int MAX_ITEMS = 1_000;

    /** 单次请求超时上限。 */
    private static final int MAX_TIMEOUT_MILLIS = 30_000;

    /** 参数名白名单。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");

    /** 参数取值：安全标量（字母数字、下划线、连字符、点、冒号、中文与空格）。 */
    private static final Pattern VALUE_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.:\\- ]{0,256}$");

    private static final String STATUS_COMPLETE = "COMPLETE";

    private static final String STATUS_PARTIAL = "PARTIAL";

    private static final String STATUS_FAILED = "FAILED";

    private final AiConnectorMapper connectorMapper;

    private final AiConnectorOperationMapper operationMapper;

    private final AiConnectorAuthHeaders authHeaders;

    /** 受控出站客户端：AI 能力启用时才装配（与模型客户端同一条件）。 */
    private final ObjectProvider<ExternalHttpClient> httpClientProvider;

    /** 执行一个已发布的连接器操作（含有限分页）。 */
    public AiConnectorExecutionResultDTO execute(AiConnectorExecutionRequestDTO request) {
        if (request == null || request.getConnectorId() == null || !StringUtils.hasText(request.getOperationKey())) {
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        AiConnectorDO connector = connectorMapper.selectById(request.getConnectorId());
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        if (!AiConnectorDO.STATUS_ENABLED.equals(connector.getStatus())) {
            throw exception(AI_CONNECTOR_DISABLED);
        }
        AiConnectorOperationDO operation = operationMapper.selectByKey(connector.getId(), request.getOperationKey());
        if (operation == null) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_FOUND);
        }
        if (!AiConnectorOperationDO.STATUS_PUBLISHED.equals(operation.getStatus())) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_PUBLISHED);
        }
        ExternalHttpClient client = httpClientProvider.getIfAvailable();
        if (client == null) {
            return new AiConnectorExecutionResultDTO()
                    .setStatus(STATUS_FAILED)
                    .setDetailCode("AI_DISABLED")
                    .setStoppedReason("upstream-failed");
        }
        AiConnectorConfig config = AiConnectorConfig.parse(connector.getConnectorType(), connector.getConfigJson());
        Map<String, Map<String, Object>> declared = declaredParameters(operation);
        Map<String, Object> arguments = request.getArguments() == null ? Map.of() : request.getArguments();
        String baseUrl = config.string("baseUrl");
        if (!operation.getPathTemplate().startsWith("/")) {
            // 路径模板必须是相对路径：绝对 URL 会让请求离开连接器声明的 Origin（防御性检查，
            // 导入器已拒绝此类模板，这里保证被直接改库篡改的操作同样无法越界）
            throw exception(AI_CONNECTOR_ORIGIN_MISMATCH);
        }
        String path = resolvePath(operation.getPathTemplate(), declared, arguments);
        String query = resolveQuery(declared, arguments);
        String url = baseUrl + path + query;
        requireSameOrigin(baseUrl, url);
        byte[] body = "POST".equals(operation.getHttpMethod())
                ? JsonUtils.toJsonString(resolveBody(declared, arguments)).getBytes(StandardCharsets.UTF_8)
                : null;
        Map<String, String> headers = new LinkedHashMap<>(authHeaders.of(connector, config));
        headers.put("Accept", "application/json");
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        return paginate(client, operation, url, headers, body);
    }

    /** 有限分页：页数上限 + 重复游标停止；达到上限时结论为 PARTIAL。 */
    private AiConnectorExecutionResultDTO paginate(
            ExternalHttpClient client,
            AiConnectorOperationDO operation,
            String url,
            Map<String, String> headers,
            byte[] body) {
        Map<String, Object> pagination = parseJson(operation.getPaginationJson());
        String type = String.valueOf(pagination.getOrDefault("type", "NONE")).toUpperCase(java.util.Locale.ROOT);
        int maxPages = Math.min(intOf(pagination.get("maxPages"), 1), MAX_PAGES_LIMIT);
        String cursorParam =
                pagination.get("cursorParam") == null ? null : String.valueOf(pagination.get("cursorParam"));
        String cursorPath = pagination.get("cursorPath") == null ? null : String.valueOf(pagination.get("cursorPath"));
        String pageParam = pagination.get("pageParam") == null ? null : String.valueOf(pagination.get("pageParam"));
        String listPath = String.valueOf(parseJson(operation.getResponseJson()).getOrDefault("listPath", ""));
        List<String> items = new ArrayList<>();
        Set<String> seenCursors = new LinkedHashSet<>();
        int page = 0;
        String cursor = null;
        String stoppedReason = "no-more-pages";
        String status = STATUS_COMPLETE;
        while (true) {
            String pageUrl = url;
            if (page > 0 && "PAGE".equals(type) && StringUtils.hasText(pageParam)) {
                pageUrl = url + (url.contains("?") ? "&" : "?") + URLEncoder.encode(pageParam, StandardCharsets.UTF_8)
                        + "=" + (page + 1);
            } else if (page > 0 && "CURSOR".equals(type) && StringUtils.hasText(cursorParam) && cursor != null) {
                pageUrl = url + (url.contains("?") ? "&" : "?")
                        + URLEncoder.encode(cursorParam, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            }
            requireSameOrigin(originOf(url), pageUrl);
            ExternalHttpResponse response;
            try {
                response = client.execute(new ExternalHttpRequest(
                        operation.getHttpMethod(), pageUrl, headers, body, Duration.ofMillis(MAX_TIMEOUT_MILLIS)));
            } catch (ExternalHttpException failure) {
                // 策略拒绝/超时/连接失败：稳定原因码，不带上游正文
                return new AiConnectorExecutionResultDTO()
                        .setStatus(STATUS_FAILED)
                        .setPages(page + 1)
                        .setItemCount(items.size())
                        .setItems(items)
                        .setStoppedReason("upstream-failed")
                        .setDetailCode(failure.getReason().name());
            }
            page++;
            // 出站边界不跟随重定向（Redirect.NEVER）：3xx 不是数据页，若当成空页会得出"取完了"的错误结论
            if (response.status() >= 300) {
                return new AiConnectorExecutionResultDTO()
                        .setStatus(STATUS_FAILED)
                        .setPages(page)
                        .setItemCount(items.size())
                        .setItems(items)
                        .setStoppedReason("upstream-failed")
                        .setDetailCode("HTTP_" + response.status());
            }
            Map<String, Object> payload =
                    JsonUtils.parseObject(new String(response.body(), StandardCharsets.UTF_8), Map.class);
            if (payload == null) {
                payload = Map.of();
            }
            collectItems(payload, listPath, items);
            if (!"PAGE".equals(type) && !"CURSOR".equals(type)) {
                stoppedReason = "no-more-pages";
                break;
            }
            if (page >= maxPages) {
                // 达到页数上限：结论是 PARTIAL，绝不当成"取完了"
                status = STATUS_PARTIAL;
                stoppedReason = "page-limit";
                break;
            }
            String next = StringUtils.hasText(cursorPath) ? stringAt(payload, cursorPath) : null;
            if (!StringUtils.hasText(next)) {
                stoppedReason = "no-more-pages";
                break;
            }
            if (!seenCursors.add(next)) {
                // 重复游标：立即停止（避免无限翻页）；**没取完就不是完整结果**
                status = STATUS_PARTIAL;
                stoppedReason = "repeated-cursor";
                break;
            }
            cursor = next;
        }
        return new AiConnectorExecutionResultDTO()
                .setStatus(status)
                .setPages(page)
                .setItemCount(items.size())
                .setItems(items)
                .setStoppedReason(stoppedReason);
    }

    /** 提取条目：按声明的列表路径取数组；无列表路径时把整个响应体当作单条。 */
    private void collectItems(Map<String, Object> payload, String listPath, List<String> items) {
        Object node = StringUtils.hasText(listPath) ? nodeAt(payload, listPath) : payload;
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (items.size() >= MAX_ITEMS) {
                    return;
                }
                items.add(bound(JsonUtils.toJsonString(item)));
            }
            return;
        }
        if (items.size() < MAX_ITEMS) {
            items.add(bound(JsonUtils.toJsonString(node)));
        }
    }

    /** 路径模板替换：占位符只允许来自声明的 path 参数，取值必须是安全标量。 */
    private static String resolvePath(
            String template, Map<String, Map<String, Object>> declared, Map<String, Object> arguments) {
        String path = template;
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            String name = entry.getKey();
            if (!"path".equals(entry.getValue().get("in"))) {
                continue;
            }
            String value = value(arguments, name);
            if (value == null) {
                throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
            }
            path = path.replace("{" + name + "}", URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        if (path.contains("{") || path.contains("}")) {
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        return path;
    }

    /** 查询串只由声明的 query 参数拼出（调用方无法注入额外查询片段）。 */
    private static String resolveQuery(Map<String, Map<String, Object>> declared, Map<String, Object> arguments) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            String name = entry.getKey();
            if (!"query".equals(entry.getValue().get("in"))) {
                continue;
            }
            String value = value(arguments, name);
            if (value != null) {
                parts.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8));
            } else if (Boolean.TRUE.equals(entry.getValue().get("required"))) {
                throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
            }
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static Map<String, Object> resolveBody(
            Map<String, Map<String, Object>> declared, Map<String, Object> arguments) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            String name = entry.getKey();
            if (!"body".equals(entry.getValue().get("in"))) {
                continue;
            }
            String value = value(arguments, name);
            if (value != null) {
                body.put(name, value);
            } else if (Boolean.TRUE.equals(entry.getValue().get("required"))) {
                throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
            }
        }
        return body;
    }

    /** 参数取值：必须是安全标量（拒绝引号、斜杠、查询语法、控制字符）。 */
    private static String value(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map || raw instanceof List) {
            // 结构化取值一律拒绝：声明式参数只接受标量
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        String text = String.valueOf(raw);
        if (!VALUE_PATTERN.matcher(text).matches()) {
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        return text;
    }

    /** SSRF 防线：目标地址必须与连接器 baseUrl 同源。 */
    private static void requireSameOrigin(String baseUrl, String target) {
        String base = originOf(baseUrl);
        String actual = originOf(target);
        if (!base.equals(actual)) {
            throw exception(AI_CONNECTOR_ORIGIN_MISMATCH);
        }
    }

    private static String originOf(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + ":" + port;
    }

    private static Map<String, Map<String, Object>> declaredParameters(AiConnectorOperationDO operation) {
        Map<String, Object> raw = parseJson(operation.getParameterJson());
        Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!NAME_PATTERN.matcher(entry.getKey()).matches() || !(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            value.forEach((key, fieldValue) -> item.put(String.valueOf(key), fieldValue));
            declared.put(entry.getKey(), item);
        }
        return declared;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseObject(json, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (IllegalArgumentException invalid) {
            return Map.of();
        }
    }

    private static String stringAt(Map<String, Object> payload, String path) {
        Object node = nodeAt(payload, path);
        return node == null ? null : String.valueOf(node);
    }

    /** 简单 JSON 指针（点号路径，最多 8 层）。 */
    private static Object nodeAt(Map<String, Object> payload, String path) {
        if (!StringUtils.hasText(path)) {
            return payload;
        }
        Object node = payload;
        int depth = 0;
        for (String segment : path.split("\\.")) {
            if (++depth > 8 || !(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(segment);
        }
        return node;
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static String bound(String item) {
        return item.length() <= MAX_ITEM_LENGTH ? item : item.substring(0, MAX_ITEM_LENGTH) + "…";
    }
}
