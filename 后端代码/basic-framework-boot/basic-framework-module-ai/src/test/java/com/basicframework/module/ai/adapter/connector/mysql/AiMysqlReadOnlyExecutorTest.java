package com.basicframework.module.ai.adapter.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** D03 只读执行器：结果有界与裁剪、超时/取消/上游失败的稳定错误码、连接归还判定。 */
class AiMysqlReadOnlyExecutorTest {

    private static final AiMysqlConnectionTarget TARGET = new AiMysqlConnectionTarget(
            9L,
            "jdbc:mysql://db.internal:3306/crm?useSSL=true",
            "crm",
            "readonly",
            "secret-value",
            List.of("crm.orders"),
            1);

    private final AiMysqlPoolRegistry poolRegistry = mock(AiMysqlPoolRegistry.class);

    private final HikariDataSource dataSource = mock(HikariDataSource.class);

    private final Connection connection = mock(Connection.class);

    private final PreparedStatement statement = mock(PreparedStatement.class);

    private final AiMysqlReadOnlyExecutor executor = new AiMysqlReadOnlyExecutor(poolRegistry);

    @BeforeEach
    void setUp() throws SQLException {
        when(poolRegistry.pool(TARGET)).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.isClosed()).thenReturn(false);
        when(connection.isValid(anyInt())).thenReturn(true);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private void stubResultSet(String[] columns, Object[][] rows) throws SQLException {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(columns.length);
        for (int index = 0; index < columns.length; index++) {
            when(meta.getColumnLabel(index + 1)).thenReturn(columns[index]);
        }
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(meta);
        Boolean[] hasNext = new Boolean[rows.length + 1];
        for (int index = 0; index < rows.length; index++) {
            hasNext[index] = true;
        }
        hasNext[rows.length] = false;
        when(resultSet.next()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        for (int column = 0; column < columns.length; column++) {
            // 按行顺序返回同一列的值：Mockito 重复打桩只保留最后一次，必须一次性给出序列
            Object[] values = new Object[rows.length];
            for (int row = 0; row < rows.length; row++) {
                values[row] = rows[row][column];
            }
            when(resultSet.getObject(column + 1))
                    .thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
        }
        when(statement.executeQuery()).thenReturn(resultSet);
    }

    @Test
    void executesReadOnlyQueryWithBoundedResultAndBoundParameters() throws SQLException {
        stubResultSet(new String[] {"id", "name", "amount", "blob", "long"}, new Object[][] {
            {1L, "alice", new BigDecimal("12.50"), new byte[] {1, 2, 3}, "x".repeat(1_200)},
            {2L, null, new Timestamp(0L), null, "ok"}
        });

        AiMysqlQueryResultDTO result = executor.execute(
                TARGET, new AiMysqlQueryRequest("SELECT * FROM crm.orders WHERE id = ?", List.of(1L), 10, 2_000));

        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getColumns()).containsExactly("id", "name", "amount", "blob", "long");
        assertThat(result.getHandleId()).isNotBlank();
        assertThat(result.getElapsedMillis()).isGreaterThanOrEqualTo(0L);
        Map<String, Object> first = result.getRows().get(0);
        assertThat(first).containsEntry("id", 1L).containsEntry("name", "alice");
        assertThat(first.get("amount")).isEqualTo("12.50");
        assertThat(first.get("blob")).isEqualTo("[binary 3 bytes]");
        assertThat(String.valueOf(first.get("long"))).hasSize(1_001).endsWith("…");
        assertThat(result.getRows().get(1)).containsEntry("name", null);

        verify(connection).setReadOnly(true);
        verify(connection).close();
        verify(statement).setObject(1, 1L);
        verify(statement).setQueryTimeout(2);
        verify(statement, org.mockito.Mockito.times(1)).setMaxRows(11);
        assertThat(executor.inFlightCount()).as("执行结束不留句柄").isZero();
        assertThat(executor.cancel(result.getHandleId())).as("已结束的句柄不可取消").isFalse();
        assertThat(executor.cancel(null)).isFalse();
        assertThat(executor.cancel("unknown")).isFalse();
    }

    @Test
    void marksTruncationWhenMoreRowsAreAvailable() throws SQLException {
        stubResultSet(new String[] {"id"}, new Object[][] {{1L}, {2L}, {3L}});

        AiMysqlQueryResultDTO result =
                executor.execute(TARGET, new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), 2, null));

        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void rejectsWideResultSetsAndOutOfRangeLimits() throws SQLException {
        String[] columns = new String[65];
        for (int index = 0; index < columns.length; index++) {
            columns[index] = "c" + index;
        }
        stubResultSet(columns, new Object[][] {new Object[65]});

        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT * FROM crm.orders", List.of(), 10, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_RESULT_TOO_LARGE));

        assertThatThrownBy(() -> new AiMysqlQueryRequest("SELECT 1", List.of(), 1_001, null).effectiveMaxRows())
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_RESULT_TOO_LARGE));
        assertThatThrownBy(() -> new AiMysqlQueryRequest("SELECT 1", List.of(), null, 30_001).effectiveTimeoutMillis())
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
        assertThatThrownBy(() -> new AiMysqlQueryRequest("SELECT 1", List.of(new Object()), null, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
        assertThatThrownBy(() -> new AiMysqlQueryRequest(" ", List.of(), null, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
        assertThat(new AiMysqlQueryRequest("SELECT 1", null, null, null).effectiveMaxRows())
                .isEqualTo(AiMysqlQueryRequest.MAX_ROWS);
        assertThat(new AiMysqlQueryRequest("SELECT 1", null, null, null).effectiveTimeoutMillis())
                .isEqualTo(AiMysqlQueryRequest.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    void rejectsUnauthorizedSqlBeforeTouchingThePool() {
        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT * FROM crm.customers", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OBJECT_NOT_AUTHORIZED));

        verify(poolRegistry, never()).pool(any());
    }

    @Test
    void mapsUpstreamFailuresToStableCodesAndDiscardsBrokenConnections() throws SQLException {
        // doThrow：executeQuery 已被打成抛异常，再写 when(...) 会先触发旧桩
        doThrow(new SQLException("Statement cancelled due to timeout or client request"))
                .when(statement)
                .executeQuery();
        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), null, 1_000)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_QUERY_TIMEOUT));

        doThrow(new SQLException("Unknown column 'nope' in 'field list'"))
                .when(statement)
                .executeQuery();
        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT nope FROM crm.orders", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_QUERY_FAILED));

        doThrow(new SQLException("Connection is read-only. Queries leading to data modification"))
                .when(statement)
                .executeQuery();
        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY));

        doThrow(new SQLNonTransientConnectionException("connection lost"))
                .when(statement)
                .executeQuery();
        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE));

        // 失败路径会探测连接有效性：失效连接关闭后由池丢弃并重建，可用连接照常归还
        when(connection.isValid(anyInt())).thenReturn(false);
        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE));
        verify(connection, org.mockito.Mockito.atLeastOnce()).isValid(1);
        verify(connection, org.mockito.Mockito.atLeastOnce()).close();
        assertThat(executor.inFlightCount()).isZero();
    }

    @Test
    void mapsPoolFailureToUnavailable() {
        when(poolRegistry.pool(TARGET)).thenThrow(new IllegalStateException("pool unavailable"));

        assertThatThrownBy(() -> executor.execute(
                        TARGET, new AiMysqlQueryRequest("SELECT id FROM crm.orders", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_MYSQL_UNAVAILABLE));
    }
}
