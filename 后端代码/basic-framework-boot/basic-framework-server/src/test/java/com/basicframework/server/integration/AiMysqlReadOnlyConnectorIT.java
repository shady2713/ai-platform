package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlConnectionTarget;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlObjectMetadata;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlPoolRegistry;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlPoolStateDTO;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D03 独立 MySQL 只读连接器端到端（真实 MySQL 8 容器 + **真实只读账号**）。
 *
 * <p>覆盖卡片验收：只读账号 + 单库白名单（未授权表与平台库不可读）、DML/文件函数不可执行、
 * 超时与取消的稳定结论、取消后连接可复用或关闭、池资源有界与可关闭。
 *
 * <p>本类关闭测试事务（{@link Propagation#NOT_SUPPORTED}）：取消场景在**另一个线程**里查询，
 * 它必须看到已提交的连接器行（测试事务里的写入对其他连接不可见）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiMysqlReadOnlyConnectorIT extends AbstractPersistenceIntegrationTest {

    private static final String CODE = "it-mysql-readonly";

    /** 只读账号口令（仅集成容器内的测试值）。 */
    private static final String READ_ONLY_PASSWORD = "d03-it-readonly-password";

    private static final String CATALOG = "d03_catalog";

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiMysqlPoolRegistry poolRegistry;

    private Long connectorId;

    @BeforeEach
    void prepareCatalog() {
        // 独立库 + 只读账号：账号只有 d03_catalog 的 SELECT，没有任何平台库授权
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".customers");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".order_view");
        jdbcTemplate.execute("CREATE TABLE " + CATALOG + ".orders ("
                + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, amount DECIMAL(12,2) NOT NULL,"
                + " note VARCHAR(255), payload VARBINARY(64))");
        jdbcTemplate.execute("CREATE TABLE " + CATALOG + ".customers (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        jdbcTemplate.execute("CREATE VIEW " + CATALOG + ".order_view AS SELECT id, amount FROM " + CATALOG + ".orders");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".orders (id, customer_id, amount, note) VALUES"
                + " (1, 10, 12.50, 'first'), (2, 10, 7.00, 'second'), (3, 11, 99.99, 'third')");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".customers (id, name) VALUES (10, 'acme'), (11, 'globex')");
        jdbcTemplate.execute("DROP USER IF EXISTS 'd03_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'd03_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'd03_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CODE)
                .setName("IT 只读连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"d03_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\""
                        + CATALOG + ".orders\",\"" + CATALOG + ".order_view\"]}")
                .setCredential(READ_ONLY_PASSWORD));
    }

    @AfterEach
    void cleanUp() {
        // 用例会直接建池（池有界/LRU 场景），收尾必须把注册表清空，避免残留连接影响后续用例
        poolRegistry.closeAll();
        if (connectorId != null) {
            mysqlConnectorService.closePool(connectorId);
            jdbcTemplate.update("DELETE FROM ai_connector WHERE id = ?", connectorId);
            connectorId = null;
        }
        jdbcTemplate.execute("DROP USER IF EXISTS 'd03_ro'@'%'");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".order_view");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".customers");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void discoversOnlyAuthorizedObjectsEvenWhenTheAccountCanSeeMore() {
        List<AiMysqlObjectMetadata> objects = mysqlConnectorService.discoverObjects(connectorId);

        assertThat(objects)
                .as("只返回白名单内对象：customers 对账号可见但未授权，必须被平台层过滤掉")
                .extracting(AiMysqlObjectMetadata::qualifiedName)
                .containsExactlyInAnyOrder(CATALOG + ".orders", CATALOG + ".order_view");
        AiMysqlObjectMetadata orders = objects.stream()
                .filter(object -> object.name().equals("orders"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.type()).isEqualTo(AiMysqlObjectMetadata.TYPE_TABLE);
        assertThat(orders.columns())
                .extracting(AiMysqlObjectMetadata.Column::name)
                .contains("id", "customer_id", "amount", "note");
        assertThat(objects.stream()
                        .filter(object -> object.name().equals("order_view"))
                        .findFirst()
                        .orElseThrow()
                        .type())
                .isEqualTo(AiMysqlObjectMetadata.TYPE_VIEW);
    }

    @Test
    void executesParameterizedReadOnlyQueriesWithBoundedResults() {
        AiMysqlQueryResultDTO result = mysqlConnectorService.execute(
                connectorId,
                new AiMysqlQueryRequest(
                        "SELECT id, amount, note, payload FROM " + CATALOG + ".orders WHERE customer_id = ?"
                                + " ORDER BY id",
                        List.of(10L),
                        10,
                        3_000));

        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getRows().get(0)).containsEntry("id", 1L).containsEntry("amount", "12.50");
        assertThat(result.getHandleId()).isNotBlank();

        // 参数绑定而非拼接：把注入串当取值传入，只会匹配不到行，不会改变查询语义
        AiMysqlQueryResultDTO injected = mysqlConnectorService.execute(
                connectorId,
                new AiMysqlQueryRequest(
                        "SELECT id FROM " + CATALOG + ".orders WHERE note = ?", List.of("first' OR '1'='1"), 10, null));
        assertThat(injected.getRowCount()).isZero();

        // 行数上限：多取一行判定截断
        AiMysqlQueryResultDTO truncated = mysqlConnectorService.execute(
                connectorId, new AiMysqlQueryRequest("SELECT id FROM " + CATALOG + ".orders", List.of(), 1, null));
        assertThat(truncated.getRowCount()).isEqualTo(1);
        assertThat(truncated.isTruncated()).isTrue();

        assertThat(mysqlConnectorService.poolState(connectorId).activeConnections())
                .as("执行结束连接已归还池")
                .isZero();
    }

    @Test
    void refusesUnauthorizedObjectsCrossDatabaseAndNonReadOnlyStatements() {
        // 账号可见但未授权（平台层白名单拒绝）
        assertThatThrownBy(() -> mysqlConnectorService.execute(
                        connectorId,
                        new AiMysqlQueryRequest("SELECT id FROM " + CATALOG + ".customers", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OBJECT_NOT_AUTHORIZED));

        // 平台库：跨库引用在守卫处就被拒
        assertThatThrownBy(() -> mysqlConnectorService.execute(
                        connectorId,
                        new AiMysqlQueryRequest("SELECT id FROM basic_framework.system_users", List.of(), null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OBJECT_NOT_AUTHORIZED));

        // DML 与文件函数：守卫拒绝（连接层另有只读会话兜底，见下一个用例）
        for (String sql : List.of(
                "UPDATE " + CATALOG + ".orders SET amount = 0",
                "DELETE FROM " + CATALOG + ".orders",
                "SELECT LOAD_FILE('/etc/passwd') FROM " + CATALOG + ".orders",
                "SELECT id FROM " + CATALOG + ".orders INTO OUTFILE '/tmp/d03.txt'")) {
            assertThatThrownBy(() -> mysqlConnectorService.execute(
                            connectorId, new AiMysqlQueryRequest(sql, List.of(), null, null)))
                    .as("非只读语句必须被拒绝：%s", sql)
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY));
        }
    }

    @Test
    void connectionItselfIsReadOnlyAndCannotReachThePlatformDatabase() throws SQLException {
        AiMysqlConnectionTarget target = target();
        HikariDataSource dataSource = poolRegistry.pool(target);

        assertThat(dataSource.isReadOnly()).as("池级只读（驱动层）").isTrue();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isReadOnly()).isTrue();
            try (Statement statement = connection.createStatement()) {
                // 驱动层只读：DML 直接被拒绝，不需要依赖守卫
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM " + CATALOG + ".orders WHERE id = 1"))
                        .isInstanceOf(SQLException.class);
                // 账号层：只读账号没有任何平台库授权，即使绕过白名单也读不到平台数据
                assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM basic_framework.system_users"))
                        .isInstanceOf(SQLException.class);
                // 文件函数：无 FILE 权限，返回 NULL 而不是文件内容
                try (var resultSet = statement.executeQuery("SELECT LOAD_FILE('/etc/passwd')")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isNull();
                }
                try (var resultSet = statement.executeQuery("SELECT VERSION()")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).startsWith("8.");
                }
            }
        }
        assertThat(mysqlConnectorService.poolState(connectorId).activeConnections())
                .isZero();
    }

    @Test
    void timesOutAndCancelsBlockedQueriesThenReusesTheConnection() throws Exception {
        // 场景一：上游表锁 → 客户端语句超时（超时是客户端中断 + 服务端 max_execution_time 兜底）
        try (Connection locker = tableLock();
                Statement blockerStatement = locker.createStatement()) {
            blockerStatement.execute("LOCK TABLES " + CATALOG + ".orders WRITE");
            long startedAt = System.currentTimeMillis();
            assertThatThrownBy(() -> mysqlConnectorService.execute(
                            connectorId,
                            new AiMysqlQueryRequest(
                                    "SELECT id FROM " + CATALOG + ".orders WHERE id = 1", List.of(), null, 1_000)))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_QUERY_TIMEOUT));
            assertThat(System.currentTimeMillis() - startedAt).isLessThan(10_000L);
            blockerStatement.execute("UNLOCK TABLES");
        }

        // 场景二：同一阻塞条件下的显式取消（返回取消码而不是超时码）
        assertThat(connectorService.getConnector(connectorId))
                .as("场景二开始前连接器仍应存在（id=%s）", connectorId)
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_connector WHERE id = ?", Long.class, connectorId))
                .isEqualTo(1L);
        try (Connection locker = tableLock();
                Statement blockerStatement = locker.createStatement()) {
            blockerStatement.execute("LOCK TABLES " + CATALOG + ".orders WRITE");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Object> running = executor.submit(() -> {
                    try {
                        return mysqlConnectorService.execute(
                                connectorId,
                                new AiMysqlQueryRequest(
                                        "SELECT id FROM " + CATALOG + ".orders WHERE id = 1", List.of(), null, 20_000));
                    } catch (Throwable throwable) {
                        return throwable;
                    }
                });
                String handle = awaitInFlightHandle(running);
                assertThat(mysqlConnectorService.cancel(handle)).isTrue();
                Object outcome = running.get(15, TimeUnit.SECONDS);
                assertThat(outcome).isInstanceOf(ServiceException.class);
                assertCode((Throwable) outcome, AiErrorCodeConstants.AI_CONNECTOR_QUERY_CANCELLED);
                assertThat(mysqlConnectorService.inFlightHandles())
                        .as("取消后不留句柄")
                        .isEmpty();
            } finally {
                executor.shutdownNow();
            }
            blockerStatement.execute("UNLOCK TABLES");
        }

        // 取消/超时后连接可复用：同一连接器继续查询成功
        AiMysqlQueryResultDTO after = mysqlConnectorService.execute(
                connectorId,
                new AiMysqlQueryRequest("SELECT COUNT(*) AS total FROM " + CATALOG + ".orders", List.of(), 10, 5_000));
        assertThat(after.getRowCount()).isEqualTo(1);
        assertThat(after.getRows().get(0)).containsEntry("total", 3L);
        assertThat(mysqlConnectorService.poolState(connectorId).activeConnections())
                .isZero();
    }

    /**
     * 上游表锁连接：必须用**独立连接**并随连接关闭释放。
     *
     * <p>借用平台池连接会把表锁留在池里（后续用例的建表/删表全部卡在 metadata lock 上）；
     * 独立连接在 try-with-resources 结束时必然释放锁，即使断言失败也不会污染后续用例。
     */
    private static Connection tableLock() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://localhost:" + mysqlPort() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                "root",
                mysqlRootPassword());
    }

    @Test
    void keepsPoolsBoundedAndClosesThemOnDemand() {
        HikariDataSource first = poolRegistry.pool(target());
        assertThat(poolRegistry.pool(target())).as("同键复用同一池").isSameAs(first);

        // 池有界：超出上限时按 LRU 关闭最久未用的池
        for (long index = 1; index <= AiMysqlPoolRegistry.MAX_POOLS + 1; index++) {
            poolRegistry.pool(new AiMysqlConnectionTarget(
                    -index, target().jdbcUrl(), CATALOG, "d03_ro", READ_ONLY_PASSWORD, List.of(), 1));
        }
        assertThat(poolRegistry.poolCount()).isLessThanOrEqualTo(AiMysqlPoolRegistry.MAX_POOLS);
        poolRegistry.pool(target());
        assertThat(poolRegistry.closePool(connectorId)).isTrue();
        assertThat(mysqlConnectorService.poolState(connectorId).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_ABSENT);

        // 关闭后再次查询会重建池：关闭不是"永久失效"
        assertThat(mysqlConnectorService
                        .execute(
                                connectorId,
                                new AiMysqlQueryRequest(
                                        "SELECT COUNT(*) AS total FROM " + CATALOG + ".orders", List.of(), 10, null))
                        .getRowCount())
                .isEqualTo(1);
        assertThat(mysqlConnectorService.poolState(connectorId).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_OPEN);
    }

    @Test
    void disablingTheConnectorClosesThePoolAndBlocksFurtherQueries() {
        assertThat(mysqlConnectorService
                        .execute(
                                connectorId,
                                new AiMysqlQueryRequest("SELECT id FROM " + CATALOG + ".orders", List.of(), 5, null))
                        .getRowCount())
                .isEqualTo(3);
        assertThat(mysqlConnectorService.poolState(connectorId).state()).isEqualTo(AiMysqlPoolStateDTO.STATE_OPEN);

        connectorService.updateStatus(
                connectorId, connectorService.getConnector(connectorId).getVersion(), false);

        assertThat(mysqlConnectorService.poolState(connectorId).state())
                .as("停用即释放外部连接")
                .isEqualTo(AiMysqlPoolStateDTO.STATE_ABSENT);
        assertThatThrownBy(() -> mysqlConnectorService.execute(
                        connectorId,
                        new AiMysqlQueryRequest("SELECT id FROM " + CATALOG + ".orders", List.of(), 5, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_DISABLED));
    }

    /** 用真实凭据解密后的目标（不经过连接器行）：验证池与连接层语义。 */
    private AiMysqlConnectionTarget target() {
        AiConnectorDO connector = connectorService.getConnector(connectorId);
        return new AiMysqlConnectionTarget(
                connectorId,
                "jdbc:mysql://localhost:" + mysqlPort() + "/" + CATALOG
                        + "?useSSL=true&connectTimeout=5000&socketTimeout=5000&allowLoadLocalInfile=false"
                        + "&autoDeserialize=false",
                CATALOG,
                "d03_ro",
                READ_ONLY_PASSWORD,
                List.of(CATALOG + ".orders", CATALOG + ".order_view"),
                connector.getCredentialRevision());
    }

    /** 等待在途句柄出现；若查询提前结束则直接报出它的结论（用于定位"没有阻塞"这类编排问题）。 */
    private String awaitInFlightHandle(Future<Object> running) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (running.isDone()) {
                throw new AssertionError("查询未阻塞就结束了：" + running.get());
            }
            List<String> handles = mysqlConnectorService.inFlightHandles();
            if (!handles.isEmpty()) {
                return handles.get(0);
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("等待在途查询句柄超时");
    }
}
