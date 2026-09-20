package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.query.compiler.AiCompiledQueryExecutor;
import com.basicframework.module.ai.service.query.compiler.QueryPlanSqlCompiler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D06 编译结果在真实 MySQL 上的正确性（DECIMAL 精度、时区边界、NULL 口径、聚合、只读账号）。
 *
 * <p>编译链路完全走真实依赖：D04 的数据集版本（发布 + 验证）→ D05 的校验器（固定时钟）
 * → D06 编译器 → D03 的只读执行器（真实连接池 + 只读账号）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiMysqlQueryExecutionIT extends AbstractPersistenceIntegrationTest {

    private static final String CONNECTOR_CODE = "it-compile-connector";

    private static final String DATASET_CODE = "it-compile-orders";

    /** 只读账号口令（仅集成容器内的测试值）。 */
    private static final String READ_ONLY_PASSWORD = "d06-it-readonly-password";

    private static final String CATALOG = "d06_catalog";

    /** 固定时钟：时间窗口与边界判定基于它。 */
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private static final String DEFINITION =
            """
            {"grain": "一行一单",
             "time": {"field": "created_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "unit": "COUNT",
                "visibility": "PUBLIC"},
               {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                "visibility": "INTERNAL"},
               {"name": "created_at", "sourceColumn": "created_at", "type": "DATETIME",
                "visibility": "INTERNAL"},
               {"name": "region", "sourceColumn": "region", "type": "STRING", "visibility": "INTERNAL"},
               {"name": "customer", "sourceColumn": "customer_id", "type": "STRING",
                "visibility": "INTERNAL"}],
             "metrics": [
               {"name": "net_amount", "field": "amount", "aggregation": "SUM", "unit": "CURRENCY",
                "visibility": "INTERNAL"},
               {"name": "order_count", "field": "order_id", "aggregation": "COUNT", "unit": "COUNT",
                "visibility": "INTERNAL"},
               {"name": "avg_amount", "field": "amount", "aggregation": "AVG", "unit": "CURRENCY",
                "visibility": "INTERNAL"}],
             "dimensions": [{"name": "customer_name", "field": "customer"}]}
            """;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiDatasetService datasetService;

    @Autowired
    private AiCompiledQueryExecutor compiledQueryExecutor;

    private final QueryPlanSqlCompiler compiler = new QueryPlanSqlCompiler();

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private Long connectorId;

    private Long datasetId;

    private Long publishedVersionId;

    @BeforeEach
    void prepareCatalog() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("CREATE TABLE " + CATALOG + ".orders ("
                + "id BIGINT PRIMARY KEY, amount DECIMAL(18,2) NULL, created_at DATETIME NOT NULL,"
                + " region VARCHAR(16) NOT NULL, customer_id VARCHAR(16) NOT NULL)");
        // 金额含 NULL（验证 NULL 口径）；时间含 8 月 31 日 23:59:59 与 9 月 1 日 00:00:00（验证边界）
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".orders (id, amount, created_at, region, customer_id) VALUES"
                + " (1, 12.50, '2026-08-01 10:00:00', 'EAST', 'C001'),"
                + " (2, 7.00, '2026-08-31 23:59:59', 'EAST', 'C001'),"
                + " (3, NULL, '2026-08-15 10:00:00', 'EAST', 'C001'),"
                + " (4, 100.00, '2026-09-01 00:00:00', 'EAST', 'C001'),"
                + " (5, 300.00, '2026-08-10 10:00:00', 'EAST', 'C002')");
        jdbcTemplate.execute("DROP USER IF EXISTS 'd06_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'd06_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'd06_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 编译连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"d06_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\"" + CATALOG
                        + ".orders\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        datasetId = datasetService.create(new AiDatasetSaveDTO()
                .setCode(DATASET_CODE)
                .setName("IT 编译数据集")
                .setConnectorId(connectorId)
                .setSourceObject(CATALOG + ".orders"));
        publishedVersionId = datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(datasetId).setDefinitionJson(DEFINITION));
        datasetService.verifyVersion(
                publishedVersionId,
                datasetService.getVersion(publishedVersionId).getVersion());
        datasetService.publishVersion(
                publishedVersionId,
                datasetService.getVersion(publishedVersionId).getVersion());
    }

    @AfterEach
    void cleanUp() {
        mysqlConnectorService.closePool(connectorId);
        jdbcTemplate.update(
                "DELETE FROM ai_dataset_version WHERE dataset_id IN" + " (SELECT id FROM ai_dataset WHERE code = ?)",
                DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_dataset WHERE code = ?", DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        jdbcTemplate.execute("DROP USER IF EXISTS 'd06_ro'@'%'");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        connectorId = null;
        datasetId = null;
        publishedVersionId = null;
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    /** 用真实数据集版本解析出的目录（与规划器同一条解析路径）。 */
    private ResolvedDatasetVersion resolved() {
        var version = datasetService.getVersion(publishedVersionId);
        return new ResolvedDatasetVersion(
                datasetId,
                DATASET_CODE,
                publishedVersionId,
                version.getVersionNo(),
                version.getSchemaHash(),
                CATALOG + ".orders",
                AiDatasetDefinition.parse(version.getDefinitionJson()),
                List.of());
    }

    private CompiledQuery compile(String planJson, QueryScope scope) {
        ValidatedQueryPlan plan = validator.validate(planJson, resolved(), NOW);
        return compiler.compile(plan, resolved(), scope);
    }

    /** 计划：8 月（Asia/Shanghai）按客户聚合净额与订单数。 */
    private static String augustPlan() {
        return """
                {"schemaVersion": "1.0",
                 "datasetId": "dset_it-compile-orders",
                 "datasetVersion": 1,
                 "metrics": ["net_amount", "order_count"],
                 "dimensions": ["customer_name"],
                 "filters": [{"field": "region", "operator": "EQ", "value": "EAST"}],
                 "timeRange": {"field": "created_at", "startInclusive": "2026-08-01T00:00:00+08:00",
                               "endExclusive": "2026-09-01T00:00:00+08:00", "timezone": "Asia/Shanghai"},
                 "orderBy": [{"field": "net_amount", "direction": "DESC"}],
                 "limit": 10}
                """;
    }

    @Test
    void executesAggregationWithDecimalPrecisionAndTimezoneBoundary() {
        CompiledQuery query = compile(augustPlan(), QueryScope.in("customer_id", List.of("C001", "C002")));
        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(connectorId, query);

        assertThat(result.getRowCount()).isEqualTo(2);
        // 按逻辑码定位行（排序为 net_amount DESC，C002 在前）
        Map<String, Object> c001 = result.getRows().stream()
                .filter(row -> "C001".equals(row.get("customer_name")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> c002 = result.getRows().stream()
                .filter(row -> "C002".equals(row.get("customer_name")))
                .findFirst()
                .orElseThrow();

        assertThat(new BigDecimal(String.valueOf(c001.get("net_amount"))))
                .as("DECIMAL 精确合计 19.50，且 9 月 1 日 00:00 不计入（时区边界）")
                .isEqualByComparingTo(new BigDecimal("19.50"));
        assertThat(new BigDecimal(String.valueOf(c001.get("order_count"))))
                .as("COUNT(order_id) 统计非空 id：三行都计入（金额为 NULL 的那行也算）")
                .isEqualByComparingTo(new BigDecimal("3"));
        assertThat(new BigDecimal(String.valueOf(c002.get("net_amount"))))
                .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void nullSemanticsFollowSqlWithoutGlobalCoalesce() {
        String planJson = augustPlan()
                .replace(
                        "\"metrics\": [\"net_amount\", \"order_count\"]",
                        "\"metrics\": [\"avg_amount\", \"order_count\"]")
                .replace("\"field\": \"net_amount\", \"direction\"", "\"field\": \"avg_amount\", \"direction\"");
        CompiledQuery query = compile(planJson, QueryScope.eq("customer_id", "C001"));

        assertThat(query.sql()).as("不做全局 COALESCE（NULL 口径由指标定义决定）").doesNotContain("COALESCE");

        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(connectorId, query);
        Map<String, Object> row = result.getRows().get(0);
        assertThat(new BigDecimal(String.valueOf(row.get("avg_amount"))))
                .as("AVG 忽略 NULL：(12.50 + 7.00) / 2 = 9.75")
                .isEqualByComparingTo(new BigDecimal("9.75"));
        assertThat(new BigDecimal(String.valueOf(row.get("order_count"))))
                .as("COUNT 统计非空 id（口径按列，不做全局 COALESCE）")
                .isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void injectionPayloadStaysABoundValueAndMatchesNothing() {
        String planJson = augustPlan()
                .replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"customer\", \"operator\": \"EQ\", \"value\": \"C001' OR '1'='1\"}");
        CompiledQuery query = compile(planJson, QueryScope.in("customer_id", List.of("C001", "C002")));

        assertThat(query.sql()).doesNotContain("OR '1'='1");
        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(connectorId, query);
        assertThat(result.getRowCount()).as("注入串只是取值，匹配不到任何行").isZero();
    }

    @Test
    void rowScopeLimitsResultEvenWhenPlanAsksForMore() {
        // 行范围只给 C001；计划的过滤条件指向 C002，结果仍只含 C001
        String planJson = augustPlan()
                .replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"customer\", \"operator\": \"EQ\", \"value\": \"C002\"}");
        CompiledQuery query = compile(planJson, QueryScope.eq("customer_id", "C001"));

        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(connectorId, query);

        assertThat(result.getRowCount()).as("行范围与用户条件同时生效（AND）").isZero();
    }

    @Test
    void rowScopeWithoutUserFiltersStillReturnsOnlyAuthorizedRows() {
        CompiledQuery query = compile(augustPlan(), QueryScope.eq("customer_id", "C002"));

        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(connectorId, query);

        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getRows().get(0)).containsEntry("customer_name", "C002");
    }

    @Test
    void limitIsBoundAndRespected() {
        String planJson = augustPlan().replace("\"limit\": 10", "\"limit\": 1");
        CompiledQuery query = compile(planJson, QueryScope.in("customer_id", List.of("C001", "C002")));

        assertThat(query.sql()).endsWith("LIMIT ?");
        assertThat(query.parameterValues().get(query.parameterValues().size() - 1))
                .isEqualTo(1L);

        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(connectorId, query);
        assertThat(result.getRowCount()).isEqualTo(1);
    }
}
