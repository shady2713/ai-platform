package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.query.compiler.QueryPlanSqlCompiler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D06 编译期权限与结构约束（真实数据集版本）：行范围必须存在且不可被用户条件覆盖、
 * 标识符只能来自审核目录、语句结构固定（无 JOIN/UNION/子查询）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiQueryPermissionIT extends AbstractPersistenceIntegrationTest {

    private static final String CONNECTOR_CODE = "it-permission-connector";

    private static final String DATASET_CODE = "it-permission-orders";

    /** 只读账号口令（仅集成容器内的测试值）。 */
    private static final String READ_ONLY_PASSWORD = "d06-it-permission-password";

    private static final String CATALOG = "d06_permission";

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
               {"name": "customer", "sourceColumn": "customer_id", "type": "STRING",
                "visibility": "INTERNAL"}],
             "metrics": [{"name": "net_amount", "field": "amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": [{"name": "customer_name", "field": "customer"}]}
            """;

    private static final String PLAN =
            """
            {"schemaVersion": "1.0",
             "datasetId": "dset_it-permission-orders",
             "datasetVersion": 1,
             "metrics": ["net_amount"],
             "dimensions": ["customer_name"],
             "filters": [],
             "timeRange": null,
             "orderBy": [],
             "limit": 50}
            """;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiDatasetService datasetService;

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
                + "id BIGINT PRIMARY KEY, amount DECIMAL(18,2) NOT NULL, created_at DATETIME NOT NULL,"
                + " customer_id VARCHAR(16) NOT NULL)");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".orders (id, amount, created_at, customer_id) VALUES"
                + " (1, 10.00, '2026-09-01 10:00:00', 'C001'), (2, 20.00, '2026-09-02 10:00:00', 'C002')");
        jdbcTemplate.execute("DROP USER IF EXISTS 'd06_perm'@'%'");
        jdbcTemplate.execute("CREATE USER 'd06_perm'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'd06_perm'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 权限连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"d06_perm\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\"" + CATALOG
                        + ".orders\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        datasetId = datasetService.create(new AiDatasetSaveDTO()
                .setCode(DATASET_CODE)
                .setName("IT 权限数据集")
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
        jdbcTemplate.execute("DROP USER IF EXISTS 'd06_perm'@'%'");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        connectorId = null;
        datasetId = null;
        publishedVersionId = null;
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

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

    private CompiledQuery compile(QueryScope scope) {
        return compile(PLAN, scope);
    }

    private CompiledQuery compile(String planJson, QueryScope scope) {
        ValidatedQueryPlan plan = validator.validate(planJson, resolved(), NOW);
        return compiler.compile(plan, resolved(), scope);
    }

    @Test
    void refusesToCompileWithoutRowScope() {
        assertThatThrownBy(() -> compile(new QueryScope(List.of())))
                .as("没有行范围就不生成可执行 SQL（不退回全库）")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));
        assertThatThrownBy(() -> compile(null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));
    }

    @Test
    void compiledStructureIsSingleSourceWithoutJoinsOrSubqueries() {
        String sql =
                compile(QueryScope.in("customer_id", List.of("C001", "C002"))).sql();

        assertThat(sql).startsWith("SELECT ").contains(" FROM " + CATALOG + ".orders WHERE ");
        assertThat(sql.toUpperCase(java.util.Locale.ROOT))
                .as("编译期结构固定：无 JOIN/UNION/子查询/函数注入面")
                .doesNotContain(" JOIN ")
                .doesNotContain("UNION")
                .doesNotContain("SELECT (")
                .doesNotContain("(" + CATALOG);
        assertThat(sql.split(" FROM ", -1)).as("只有一个来源").hasSize(2);
    }

    @Test
    void scopePredicateIsForcedAndNotRemovableByPlan() {
        assertThat(compile(QueryScope.in("customer_id", List.of("C001"))).sql()).contains("customer_id IN (?)");

        // 计划自身的过滤条件与行范围落在同一列：两者 AND 组合，用户条件无法替换行范围
        String planWithFilter = PLAN.replace(
                "\"filters\": []",
                "\"filters\": [{\"field\": \"customer\", \"operator\": \"EQ\", \"value\": \"C002\"}]");
        String both = compile(planWithFilter, QueryScope.in("customer_id", List.of("C001")))
                .sql();
        assertThat(both).contains("customer_id IN (?) AND customer_id = ?");
    }

    @Test
    void refusesRowScopeColumnsOutsideTheAuditedCatalog() {
        // 行范围里的列同样必须在目录内（授权层配置错误不能变成"任意列过滤"）
        assertThatThrownBy(() -> compile(QueryScope.eq("secret_salary", "x")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));
        // 计划层面的"目录外列"由 D05 校验器先行拒绝（见 AiQueryPlanValidatorTest），
        // 编译期还有一道同源校验（见 QueryPlanSqlCompilerTest.identifiersMustComeFromTheAuditedCatalog）
    }
}
