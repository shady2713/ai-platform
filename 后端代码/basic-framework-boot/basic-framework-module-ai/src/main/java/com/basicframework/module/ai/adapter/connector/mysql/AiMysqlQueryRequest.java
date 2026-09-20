package com.basicframework.module.ai.adapter.connector.mysql;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_RESULT_TOO_LARGE;

import java.util.List;

/**
 * 只读查询请求（D03）：SQL + 绑定参数 + 结果与超时上限。
 *
 * <p>上限是**服务端硬上限**，请求只能往下调：行数最多 {@link #MAX_ROWS}，
 * 超时最多 {@link #MAX_TIMEOUT_MILLIS}。参数只允许安全标量（String/Number/Boolean/null），
 * 一律用占位符绑定，不做字符串拼接。
 */
public record AiMysqlQueryRequest(String sql, List<Object> parameters, Integer maxRows, Integer timeoutMillis) {

    /** 单次查询最大返回行数（超出即截断并标记 truncated）。 */
    public static final int MAX_ROWS = 1_000;

    /** 单次查询最大超时（毫秒）。 */
    public static final int MAX_TIMEOUT_MILLIS = 30_000;

    /** 默认超时（毫秒）。 */
    public static final int DEFAULT_TIMEOUT_MILLIS = 5_000;

    /** 绑定参数个数上限。 */
    public static final int MAX_PARAMETERS = 100;

    public AiMysqlQueryRequest {
        if (sql == null || sql.isBlank()) {
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        List<Object> bound = parameters == null ? List.of() : List.copyOf(parameters);
        if (bound.size() > MAX_PARAMETERS) {
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        for (Object value : bound) {
            if (value != null
                    && !(value instanceof String)
                    && !(value instanceof Number)
                    && !(value instanceof Boolean)
                    // 时间窗口按数据集声明的时区换算后以本地时间绑定（列是 DATETIME）
                    && !(value instanceof java.time.LocalDateTime)
                    && !(value instanceof java.time.LocalDate)) {
                throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
            }
        }
        parameters = bound;
    }

    /** 生效行数上限（默认 {@link #MAX_ROWS}，越界拒绝）。 */
    public int effectiveMaxRows() {
        if (maxRows == null) {
            return MAX_ROWS;
        }
        if (maxRows < 1 || maxRows > MAX_ROWS) {
            throw exception(AI_CONNECTOR_RESULT_TOO_LARGE);
        }
        return maxRows;
    }

    /** 生效超时（默认 {@link #DEFAULT_TIMEOUT_MILLIS}，越界拒绝）。 */
    public int effectiveTimeoutMillis() {
        if (timeoutMillis == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        if (timeoutMillis < 1 || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
        }
        return timeoutMillis;
    }
}
