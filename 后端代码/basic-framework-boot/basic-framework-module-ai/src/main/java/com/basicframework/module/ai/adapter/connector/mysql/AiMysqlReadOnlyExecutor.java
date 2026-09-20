package com.basicframework.module.ai.adapter.connector.mysql;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_QUERY_CANCELLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_QUERY_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_QUERY_TIMEOUT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_RESULT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY;

import com.basicframework.framework.common.exception.ServiceException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 只读查询执行器（D03）：在连接器自己的池上执行**单条只读查询**。
 *
 * <p>执行语义（对应本卡验收）：
 * <ul>
 *   <li>执行前先过 {@link AiMysqlSqlGuard}（单条只读 + 关键字黑名单 + 对象白名单）；</li>
 *   <li>参数一律用占位符绑定，不做字符串拼接；</li>
 *   <li>超时：客户端 {@code Statement.setQueryTimeout} 主动中断，服务端 {@code max_execution_time} 兜底；</li>
 *   <li>取消：按句柄调用 {@link #cancel(String)}（另一线程），被取消的查询返回稳定错误码；</li>
 *   <li>结果有界：行数（含"多取一行判断截断"）、列数与单值长度都有上限，超列直接拒绝；</li>
 *   <li>取消/超时后连接要么可用、要么被丢弃：执行完检查连接有效性，失效连接直接关闭让池重建。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMysqlReadOnlyExecutor {

    /** 结果列数上限（超出即拒绝：宽表不是查询通道该返回的东西）。 */
    private static final int MAX_COLUMNS = 64;

    /** 单值长度上限（字符）：超出按上限裁剪并加省略号。 */
    private static final int MAX_VALUE_LENGTH = 1_000;

    /** 取消判定等待窗口（毫秒）：语句取消后驱动会抛错，这里不额外等待。 */
    private final AiMysqlPoolRegistry poolRegistry;

    /** 在途语句：句柄 -> 语句 + 是否由调用方显式取消。 */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    /** 执行单条只读查询（结果行数受请求上限约束）。 */
    public AiMysqlQueryResultDTO execute(AiMysqlConnectionTarget target, AiMysqlQueryRequest request) {
        AiMysqlSqlGuard.validate(request.sql(), target);
        int maxRows = request.effectiveMaxRows();
        int timeoutMillis = request.effectiveTimeoutMillis();
        String handleId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        Connection connection = null;
        InFlight inFlightQuery = null;
        boolean failed = false;
        try {
            connection = poolRegistry.pool(target).getConnection();
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(request.sql())) {
                bind(statement, request.parameters());
                statement.setQueryTimeout(Math.max(1, (timeoutMillis + 999) / 1_000));
                // 多取一行用于判定"是否还有更多行"，因此行数上限要 +1
                statement.setMaxRows(maxRows + 1);
                inFlightQuery = new InFlight(statement);
                inFlight.put(handleId, inFlightQuery);
                try (ResultSet resultSet = statement.executeQuery()) {
                    AiMysqlQueryResultDTO result = read(resultSet, maxRows);
                    result.setHandleId(handleId).setElapsedMillis(System.currentTimeMillis() - startedAt);
                    return result;
                }
            }
        } catch (SQLException failure) {
            failed = true;
            throw translate(inFlightQuery, failure);
        } catch (RuntimeException failure) {
            failed = true;
            if (failure instanceof ServiceException) {
                throw failure;
            }
            log.warn(
                    "只读查询执行异常：connectorId={}, reason={}",
                    target.connectorId(),
                    failure.getClass().getSimpleName());
            throw exception(AI_CONNECTOR_MYSQL_UNAVAILABLE);
        } finally {
            inFlight.remove(handleId);
            release(connection, failed);
        }
    }

    /** 按句柄取消在途查询（另一线程调用）；返回是否找到并请求了取消。 */
    public boolean cancel(String handleId) {
        if (handleId == null) {
            return false;
        }
        InFlight query = inFlight.get(handleId);
        if (query == null) {
            return false;
        }
        query.cancelledByCaller().set(true);
        try {
            query.statement().cancel();
            return true;
        } catch (SQLException failure) {
            log.warn("只读查询取消失败：reason={}", failure.getClass().getSimpleName());
            return false;
        }
    }

    /** 在途查询数量（用于验证"取消后没有残留句柄"）。 */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** 在途查询句柄（可观测：调用方据此取消正在跑的查询）。 */
    public List<String> inFlightHandles() {
        return List.copyOf(inFlight.keySet());
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private AiMysqlQueryResultDTO read(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        if (columnCount > MAX_COLUMNS) {
            throw exception(AI_CONNECTOR_RESULT_TOO_LARGE);
        }
        List<String> columns = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            columns.add(meta.getColumnLabel(index));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= columnCount; index++) {
                row.put(columns.get(index - 1), sanitize(resultSet.getObject(index)));
            }
            rows.add(row);
        }
        return new AiMysqlQueryResultDTO()
                .setColumns(columns)
                .setRows(rows)
                .setRowCount(rows.size())
                .setTruncated(truncated);
    }

    /** 单值裁剪：只保留可安全序列化的标量；二进制与大文本不原样带出。 */
    private static Object sanitize(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            // 数值统一走十进制字符串：避免科学计数法与精度丢失
            return decimal.toPlainString();
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "[binary " + bytes.length + " bytes]";
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_VALUE_LENGTH ? text : text.substring(0, MAX_VALUE_LENGTH) + "…";
    }

    /** SQL 异常 -> 稳定错误码（不回上游正文、不含连接信息）。 */
    private static RuntimeException translate(InFlight inFlightQuery, SQLException failure) {
        String message =
                failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (inFlightQuery != null && inFlightQuery.cancelledByCaller().get()) {
            return exception(AI_CONNECTOR_QUERY_CANCELLED);
        }
        if (message.contains("cancelled due to timeout") || message.contains("timeout")) {
            return exception(AI_CONNECTOR_QUERY_TIMEOUT);
        }
        if (message.contains("connection is read-only")) {
            return exception(AI_CONNECTOR_SQL_NOT_READ_ONLY);
        }
        if (failure instanceof java.sql.SQLTimeoutException) {
            return exception(AI_CONNECTOR_QUERY_TIMEOUT);
        }
        if (failure instanceof java.sql.SQLTransientConnectionException
                || failure instanceof java.sql.SQLNonTransientConnectionException
                || message.contains("communications link failure")) {
            return exception(AI_CONNECTOR_MYSQL_UNAVAILABLE);
        }
        return exception(AI_CONNECTOR_QUERY_FAILED);
    }

    /**
     * 归还连接：无论成功失败都必须关闭（Hikari 的关闭即归还），否则连接不会回到池里。
     *
     * <p>失败路径先探测有效性：失效连接（取消/超时后可能已被上游断开）交由连接池丢弃并重建，
     * 可用连接照常归还——这正是"取消后连接可复用或关闭"的实现。
     */
    private void release(Connection connection, boolean failed) {
        if (connection == null) {
            return;
        }
        try {
            if (failed && !connection.isClosed() && !connection.isValid(1)) {
                log.warn("只读连接在失败后不可用，交由连接池丢弃并重建");
            }
            connection.close();
        } catch (SQLException failure) {
            log.warn("只读连接归还未完成：reason={}", failure.getClass().getSimpleName());
        }
    }

    /** 在途查询：语句 + 调用方取消标记（超时与显式取消要区分开）。 */
    private record InFlight(Statement statement, AtomicBoolean cancelledByCaller) {

        private InFlight(Statement statement) {
            this(statement, new AtomicBoolean(false));
        }
    }
}
