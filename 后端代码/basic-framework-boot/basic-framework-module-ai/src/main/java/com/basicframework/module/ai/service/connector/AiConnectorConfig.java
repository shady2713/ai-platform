package com.basicframework.module.ai.service.connector;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 连接器声明式配置（D01）：**结构化字段**的唯一校验入口。
 *
 * <p>为什么不接受整段连接串：连接串参数（`allowLoadLocalInfile`、`autoDeserialize`、
 * 反斜杠转义、`#` 注释等）会把"配置"变成可执行的攻击面。本类只接受白名单键 + 严格取值，
 * 未知键与含连接串语法的取值一律**拒绝**（而不是转义），因此恶意参数无法从外部注入。
 *
 * <p>取值规则（两类连接器共用精神）：
 * <ul>
 *   <li>主机名只允许字母数字、点、连字符，且必须形如主机名（禁止 scheme、冒号、斜杠、查询、用户信息）；</li>
 *   <li>端口 1..65535；库名/用户名只允许 {@code [A-Za-z0-9_]}；</li>
 *   <li>HTTP 基址必须是 https，且不得携带查询串、片段或用户信息；</li>
 *   <li>枚举值（method/authType/sslMode）只允许白名单取值。</li>
 * </ul>
 */
public final class AiConnectorConfig {

    /** HTTP 允许的配置键。 */
    private static final Set<String> HTTP_KEYS = Set.of("baseUrl", "method", "healthPath", "authType", "timeoutMillis");

    /** HTTP 必填键。 */
    private static final Set<String> HTTP_REQUIRED_KEYS = Set.of("baseUrl", "method");

    /** MYSQL 允许的配置键。 */
    private static final Set<String> MYSQL_KEYS = Set.of("host", "port", "database", "username", "sslMode");

    /** MYSQL 必填键。 */
    private static final Set<String> MYSQL_REQUIRED_KEYS = Set.of("host", "port", "database", "username");

    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private static final Pattern HEALTH_PATH_PATTERN = Pattern.compile("^/[A-Za-z0-9_./-]{0,127}$");

    /** 基址路径：空、"/" 或简单段（不含穿越）。 */
    private static final Pattern PATH_PATTERN = Pattern.compile("^(/[A-Za-z0-9_.-]+)*/?$");

    private static final Set<String> METHODS = Set.of("GET", "POST");

    private static final Set<String> AUTH_TYPES = Set.of("NONE", "BEARER", "BASIC");

    private static final Set<String> SSL_MODES = Set.of("DISABLED", "REQUIRED", "VERIFY_IDENTITY");

    private static final int MAX_TIMEOUT_MILLIS = 60_000;

    private final String connectorType;

    private final Map<String, Object> values;

    private AiConnectorConfig(String connectorType, Map<String, Object> values) {
        this.connectorType = connectorType;
        this.values = values;
    }

    /** 解析并校验配置文本（JSON 对象；键与取值都必须在白名单内）。 */
    public static AiConnectorConfig parse(String connectorType, String configJson) {
        if (connectorType == null || configJson == null || configJson.isBlank()) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(configJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        if (parsed == null) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        parsed.forEach((key, value) -> values.put(String.valueOf(key), value));
        if (AiConnectorDO_TYPES.HTTP.equals(connectorType)) {
            requireKeys(values, HTTP_KEYS, HTTP_REQUIRED_KEYS);
            validateHttp(values);
        } else if (AiConnectorDO_TYPES.MYSQL.equals(connectorType)) {
            requireKeys(values, MYSQL_KEYS, MYSQL_REQUIRED_KEYS);
            validateMysql(values);
        } else {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        return new AiConnectorConfig(connectorType, values);
    }

    /** 规范化后的配置文本（键按白名单顺序，便于比较与审计）。 */
    public String canonicalJson() {
        return JsonUtils.toJsonString(values);
    }

    public String type() {
        return connectorType;
    }

    public String string(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Integer integer(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    /** MYSQL 连接的 JDBC 地址（只由已校验字段拼出，禁止外部传入整段连接串）。 */
    public String jdbcUrl() {
        return "jdbc:mysql://" + string("host") + ":" + integer("port") + "/" + string("database")
                + "?useSSL=" + (SSL_MODES.iterator().next().equals(string("sslMode")) ? "false" : "true")
                + "&connectTimeout=5000&socketTimeout=5000&allowLoadLocalInfile=false&autoDeserialize=false";
    }

    private static void requireKeys(Map<String, Object> values, Set<String> allowed, Set<String> required) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                // 未知键（例如连接串参数）一律拒绝，不静默忽略
                throw exception(AI_CONNECTOR_CONFIG_INVALID);
            }
        }
        for (String key : required) {
            if (!values.containsKey(key) || values.get(key) == null) {
                throw exception(AI_CONNECTOR_CONFIG_INVALID);
            }
        }
    }

    private static void validateHttp(Map<String, Object> values) {
        String baseUrl = String.valueOf(values.get("baseUrl"));
        try {
            URI uri = new URI(baseUrl);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !HOST_PATTERN.matcher(uri.getHost()).matches()
                    // 路径只允许简单段：禁止目录穿越（".."）与反斜杠
                    || path.contains("..")
                    || path.contains("\\")
                    || !PATH_PATTERN.matcher(path).matches()) {
                throw exception(AI_CONNECTOR_CONFIG_INVALID);
            }
        } catch (URISyntaxException invalid) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        if (!METHODS.contains(String.valueOf(values.get("method")).toUpperCase(java.util.Locale.ROOT))) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        String authType = String.valueOf(values.getOrDefault("authType", "NONE"));
        if (!AUTH_TYPES.contains(authType.toUpperCase(java.util.Locale.ROOT))) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        Object healthPath = values.get("healthPath");
        if (healthPath != null
                && !HEALTH_PATH_PATTERN.matcher(String.valueOf(healthPath)).matches()) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        Object timeout = values.get("timeoutMillis");
        if (timeout != null) {
            int millis = Integer.parseInt(String.valueOf(timeout));
            if (millis < 1 || millis > MAX_TIMEOUT_MILLIS) {
                throw exception(AI_CONNECTOR_CONFIG_INVALID);
            }
        }
    }

    private static void validateMysql(Map<String, Object> values) {
        if (!HOST_PATTERN.matcher(String.valueOf(values.get("host"))).matches()) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        int port = Integer.parseInt(String.valueOf(values.get("port")));
        if (port < 1 || port > 65_535) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        if (!IDENTIFIER_PATTERN.matcher(String.valueOf(values.get("database"))).matches()
                || !IDENTIFIER_PATTERN
                        .matcher(String.valueOf(values.get("username")))
                        .matches()) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        String sslMode = String.valueOf(values.getOrDefault("sslMode", "REQUIRED"));
        if (!SSL_MODES.contains(sslMode.toUpperCase(java.util.Locale.ROOT))) {
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
    }

    /** 类型常量（与 DO 保持同一词表，避免服务层反向依赖 DO 的静态字段命名）。 */
    private static final class AiConnectorDO_TYPES {

        private static final String HTTP = "HTTP";

        private static final String MYSQL = "MYSQL";
    }

    /** 供测试与调用方使用的类型白名单。 */
    public static List<String> supportedTypes() {
        return List.of(AiConnectorDO_TYPES.HTTP, AiConnectorDO_TYPES.MYSQL);
    }
}
