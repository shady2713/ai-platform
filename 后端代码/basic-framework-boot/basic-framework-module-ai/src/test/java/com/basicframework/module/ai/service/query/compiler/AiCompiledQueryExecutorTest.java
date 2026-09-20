package com.basicframework.module.ai.service.query.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.SqlParameter;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D06 执行桥：预算原样传递、绑定值按序传递、空编译结果拒绝。 */
class AiCompiledQueryExecutorTest {

    private final AiMysqlConnectorService mysqlConnectorService = mock(AiMysqlConnectorService.class);

    private final AiCompiledQueryExecutor executor = new AiCompiledQueryExecutor(mysqlConnectorService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void passesSqlValuesAndBudgetToTheReadOnlyConnector() {
        CompiledQuery query = new CompiledQuery(
                "SELECT customer_id AS customer_name, SUM(amount) AS net_amount FROM it_query.orders"
                        + " WHERE customer_id = ? LIMIT ?",
                List.of(SqlParameter.string("C001"), SqlParameter.number(10)),
                List.of(new CompiledQuery.ResultColumn("customer_name", "customer_name", "STRING")),
                10,
                5_000,
                91L,
                "a".repeat(64),
                "it_query.orders");
        AiMysqlQueryResultDTO result = new AiMysqlQueryResultDTO().setRowCount(1);
        when(mysqlConnectorService.execute(eq(71L), any())).thenReturn(result);

        assertThat(executor.execute(71L, query)).isSameAs(result);

        ArgumentCaptor<AiMysqlQueryRequest> request = ArgumentCaptor.forClass(AiMysqlQueryRequest.class);
        verify(mysqlConnectorService).execute(eq(71L), request.capture());
        assertThat(request.getValue().sql()).isEqualTo(query.sql());
        assertThat(request.getValue().parameters()).containsExactly("C001", 10L);
        assertThat(request.getValue().effectiveMaxRows()).isEqualTo(10);
        assertThat(request.getValue().effectiveTimeoutMillis()).isEqualTo(5_000);
    }

    @Test
    void refusesMissingCompiledQuery() {
        assertThatThrownBy(() -> executor.execute(71L, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));
    }
}
