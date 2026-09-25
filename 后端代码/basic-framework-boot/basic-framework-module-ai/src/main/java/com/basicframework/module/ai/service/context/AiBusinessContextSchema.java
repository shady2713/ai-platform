package com.basicframework.module.ai.service.context;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONTEXT_SCHEMA_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务上下文协议（C08）：宿主可传字段的**逐键形状**校验。
 *
 * <p>为什么在"键白名单"之外还要校验形状：S04 已拒绝未注册的键（因此上下文改不了 app/user/scope——
 * 那些键不在此列），但每个键的取值仍是自由的。上下文会被拼进模型输入，放任取值形态会让
 * "业务上下文"变成第二条不受控输入通道。这里按 FR-13 的字段逐键收口：
 *
 * <ul>
 *   <li>{@code page}：页面标识（受限字符集与长度）；</li>
 *   <li>{@code objectType}：对象类型（小写标识符，与资源类型词表同风格）；</li>
 *   <li>{@code objectId}：业务对象标识（不透明字符串，不做数据库编号解析）；</li>
 *   <li>{@code filters}：筛选条件（对象，最多 20 个键，取值只允许标量与标量数组）；</li>
 *   <li>{@code locale}：语言标签（形如 {@code zh-CN}）；</li>
 *   <li>{@code timezone}：IANA 时区或 UTC 偏移（形如 {@code Asia/Shanghai}）。</li>
 * </ul>
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>形状不合规即拒绝</b>（{@code AI_CONTEXT_SCHEMA_INVALID}），不做"去掉坏字段继续用"；
 *       "无权对象"由业务侧决定是追问还是拒绝（本类只保证不把非法输入送进模型）；</li>
 *   <li><b>上下文的取值不参与身份判定</b>：本类只接受上述形状，任何身份/范围字段都会因未注册键被拒；</li>
 *   <li><b>规范化输出键序稳定</b>：上下文进入运行请求摘要（幂等键的一部分），键序不稳定会让
 *       "同一请求"被算成两个不同请求。</li>
 * </ol>
 */
public final class AiBusinessContextSchema {

    /** 注册键（与 S04 的白名单同值，顺序即规范化输出顺序）。 */
    public static final List<String> REGISTERED_KEYS =
            List.of("page", "objectType", "objectId", "filters", "locale", "timezone");

    private static final Set<String> REGISTERED = new LinkedHashSet<>(REGISTERED_KEYS);

    private static final int MAX_PAGE_LENGTH = 128;

    private static final int MAX_OBJECT_TYPE_LENGTH = 64;

    private static final int MAX_OBJECT_ID_LENGTH = 128;

    private static final int MAX_FILTER_KEYS = 20;

    private static final int MAX_FILTER_ITEMS = 50;

    private static final int MAX_FILTER_VALUE_LENGTH = 256;

    private static final int MAX_CONTEXT_LENGTH = 4_000;

    private static final Pattern LOWERCASE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

    private static final Pattern SAFE_TEXT = Pattern.compile("^[\\p{L}\\p{N} _.:/@-]{1,128}$");

    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$");

    /** 时区形状（先做粗筛，再用 JDK 的 tzdb 判定是否真实存在）。 */
    private static final Pattern TIMEZONE =
            Pattern.compile("^[A-Za-z0-9_+-]{1,32}(?:/[A-Za-z0-9_+-]{1,32})*(?::\\d{2})?$");

    private AiBusinessContextSchema() {}

    /**
     * 校验并规范化业务上下文 JSON。
     *
     * @param raw 宿主传来的上下文文本（可空：无上下文是合法输入）
     * @return 规范化 JSON；无上下文时返回 null
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_CONTEXT_LENGTH) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, "长度");
        }
        Map<String, Object> parsed = parse(raw);
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String key : REGISTERED_KEYS) {
            if (!parsed.containsKey(key)) {
                continue;
            }
            normalized.put(key, normalizeValue(key, parsed.get(key)));
        }
        return JsonUtils.toJsonString(normalized);
    }

    /** 某键是否已注册（供调用方在报错时给出稳定字段名）。 */
    public static boolean isRegisteredKey(String key) {
        return key != null && REGISTERED.contains(key);
    }

    private static Map<String, Object> parse(String raw) {
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(raw, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, "格式");
        }
        if (parsed == null) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, "格式");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : parsed.entrySet()) {
            String key = entry.getKey() == null ? null : String.valueOf(entry.getKey());
            if (!isRegisteredKey(key)) {
                // 未注册键一律拒绝：身份/范围类字段（appCode、subjectId、scope…）因此进不来
                throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Object normalizeValue(String key, Object value) {
        if (value == null) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        }
        return switch (key) {
            case "page" -> text(value, key, MAX_PAGE_LENGTH, SAFE_TEXT);
            case "objectType" -> identifier(value, key);
            case "objectId" -> text(value, key, MAX_OBJECT_ID_LENGTH, null);
            case "filters" -> filters(value, key);
            case "locale" -> text(value, key, 32, LOCALE);
            case "timezone" -> timezone(value, key);
            default -> throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        };
    }

    private static String text(Object value, String key, int maxLength, Pattern pattern) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        }
        if (pattern != null && !pattern.matcher(text).matches()) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        }
        return text;
    }

    /**
     * 时区：必须是 JDK 时区库认识的标识（`UTC`、`Asia/Shanghai`、`+08:00` 都接受）。
     *
     * <p>只做形状检查会让拼错的时区悄悄生效（业务日期区间算错一天），所以这里用 tzdb 实判。
     */
    private static String timezone(Object value, String key) {
        String text = text(value, key, 64, TIMEZONE);
        try {
            java.time.ZoneId.of(text);
        } catch (RuntimeException unknown) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        }
        return text;
    }

    private static String identifier(Object value, String key) {
        if (!(value instanceof String text)
                || !LOWERCASE_IDENTIFIER.matcher(text).matches()) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        }
        return text;
    }

    /** 筛选条件：对象 + 标量/标量数组取值（嵌套对象与超限都拒绝）。 */
    private static Map<String, Object> filters(Object value, String key) {
        if (!(value instanceof Map<?, ?> map) || map.size() > MAX_FILTER_KEYS) {
            throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String field = entry.getKey() == null ? null : String.valueOf(entry.getKey());
            if (field == null || !LOWERCASE_IDENTIFIER.matcher(field).matches()) {
                throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
            }
            normalized.put(field, filterValue(entry.getValue(), key));
        }
        return normalized;
    }

    private static Object filterValue(Object value, String key) {
        if (value instanceof String text) {
            if (text.length() > MAX_FILTER_VALUE_LENGTH) {
                throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
            }
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_FILTER_ITEMS) {
                throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
            }
            return list.stream().map(item -> filterValue(item, key)).toList();
        }
        throw exception(AI_CONTEXT_SCHEMA_INVALID, key);
    }
}
