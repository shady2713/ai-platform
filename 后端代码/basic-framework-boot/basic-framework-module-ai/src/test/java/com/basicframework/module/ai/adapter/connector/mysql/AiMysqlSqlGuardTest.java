package com.basicframework.module.ai.adapter.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D03 只读守卫：单条只读语句、关键字黑名单、对象白名单（默认拒绝）。 */
class AiMysqlSqlGuardTest {

    private static final AiMysqlConnectionTarget TARGET = new AiMysqlConnectionTarget(
            9L,
            "jdbc:mysql://db.internal:3306/crm?useSSL=true",
            "crm",
            "readonly",
            "secret-value",
            List.of("crm.orders", "crm.order_view"),
            1);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void acceptsSingleReadOnlyQueriesOnAuthorizedObjects() {
        for (String sql : List.of(
                "SELECT id, name FROM orders WHERE id = ?",
                "SELECT * FROM crm.orders",
                "SELECT o.id FROM crm.orders o JOIN crm.order_view v ON o.id = v.id",
                "SELECT * FROM crm.orders, crm.order_view",
                "SELECT * FROM (SELECT id FROM crm.orders) t",
                "WITH t AS (SELECT id FROM crm.orders) SELECT * FROM t",
                "SELECT 'a;b' AS label FROM crm.orders",
                "SELECT COUNT(*) FROM crm.orders WHERE status = '--not-a-comment'")) {
            assertThatCode(() -> AiMysqlSqlGuard.validate(sql, TARGET))
                    .as("应放行的只读查询：%s", sql)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsUnauthorizedAndCrossDatabaseObjects() {
        for (String sql : List.of(
                "SELECT * FROM customers",
                "SELECT * FROM crm.customers",
                "SELECT * FROM other.orders",
                "SELECT * FROM crm.orders, crm.customers",
                "SELECT * FROM crm.orders o JOIN crm.customers c ON o.id = c.id",
                "SELECT * FROM (SELECT * FROM crm.customers) t",
                "SELECT table_name FROM information_schema.tables",
                "SELECT * FROM mysql.user")) {
            assertThatThrownBy(() -> AiMysqlSqlGuard.validate(sql, TARGET))
                    .as("越权对象必须被拒绝：%s", sql)
                    .satisfies(throwable ->
                            assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OBJECT_NOT_AUTHORIZED));
        }
    }

    @Test
    void rejectsWritesFileFunctionsAndStatementSmuggling() {
        for (String sql : List.of(
                "UPDATE crm.orders SET name = 'x'",
                "DELETE FROM crm.orders",
                "INSERT INTO crm.orders (id) VALUES (1)",
                "DROP TABLE crm.orders",
                "TRUNCATE TABLE crm.orders",
                "SELECT * FROM crm.orders INTO OUTFILE '/tmp/x'",
                "SELECT LOAD_FILE('/etc/passwd') FROM crm.orders",
                "SELECT * FROM crm.orders FOR UPDATE",
                "SELECT * FROM crm.orders LOCK IN SHARE MODE",
                "SELECT SLEEP(10) FROM crm.orders",
                "SELECT BENCHMARK(1000000, MD5('x')) FROM crm.orders",
                "SELECT @x := 1 FROM crm.orders",
                "SELECT * FROM crm.orders; DROP TABLE crm.orders",
                "SELECT * FROM crm.orders -- comment",
                "SELECT * FROM crm.orders /* comment */",
                "SELECT * FROM crm.orders # comment",
                "SELECT `id` FROM crm.orders",
                "CALL refresh_orders()",
                "SELECT * FROM crm.orders UNION SELECT * FROM crm.orders INTO OUTFILE '/tmp/y'")) {
            assertThatThrownBy(() -> AiMysqlSqlGuard.validate(sql, TARGET))
                    .as("非只读语句必须被拒绝：%s", sql)
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY));
        }
    }

    @Test
    void rejectsEmptyOversizedAndNonSelectStatements() {
        assertThatThrownBy(() -> AiMysqlSqlGuard.validate("  ", TARGET))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY));
        String oversized = "SELECT * FROM crm.orders WHERE name = '" + "x".repeat(9_000) + "'";
        assertThatThrownBy(() -> AiMysqlSqlGuard.validate(oversized, TARGET))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY));
    }

    @Test
    void rejectsCteThatHidesUnauthorizedObjects() {
        assertThatCode(() -> AiMysqlSqlGuard.validate(
                        "WITH a AS (SELECT id FROM crm.orders), b AS (SELECT id FROM crm.order_view)"
                                + " SELECT * FROM a JOIN b ON a.id = b.id",
                        TARGET))
                .as("CTE 名不是真实对象，内部引用仍逐个检查")
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                        AiMysqlSqlGuard.validate("WITH a AS (SELECT id FROM crm.customers) SELECT * FROM a", TARGET))
                .as("用 CTE 包一层不能绕过白名单")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OBJECT_NOT_AUTHORIZED));
    }

    @Test
    void authorizesOnlyDeclaredObjectsAndNormalizesCase() {
        assertThat(TARGET.authorizes("crm", "orders")).isTrue();
        assertThat(TARGET.authorizes("CRM", "ORDERS")).isTrue();
        assertThat(TARGET.authorizes("crm", "customers")).isFalse();
        assertThat(TARGET.authorizes("", "orders")).isFalse();
        assertThat(AiMysqlSqlGuard.normalized(List.of("CRM.Orders"))).containsExactly("crm.orders");
        assertThat(AiMysqlSqlGuard.normalized(null)).isEmpty();
    }
}
