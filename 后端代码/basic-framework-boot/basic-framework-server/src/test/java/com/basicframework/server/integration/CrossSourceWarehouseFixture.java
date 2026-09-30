package com.basicframework.server.integration;

import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.query.compiler.QueryPlanSqlCompiler;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceBudget;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Y04 验收 IT 的真实数仓夹具：三个跨源表、一个只读账号、一个多对象连接器与三个已发布数据集版本。
 *
 * <p>单独成类是为了让 IT 本体只留验收断言。这里的每个动作都走真实服务
 * （{@code AiDatasetService} 的建版本/验证/发布、{@code AiConnectorService} 的连接器登记），
 * 不直接插表伪造"已发布版本"——那样验证的会是测试夹具而不是平台的发布链路。
 *
 * <p>三个来源的数据时间刻意不同：订单更新到 10:00、发票只到 08:00、回款 09:00。
 * 跨源合计因此只能解释到 08:00，这个差值是专项二要断言的对象。
 */
final class CrossSourceWarehouseFixture {

    static final String CONNECTOR_CODE = "it-crosssource-connector";

    static final String CATALOG = "y04_catalog";

    static final String READ_ONLY_USER = "y04_ro";

    static final String READ_ONLY_PASSWORD = "y04-it-readonly-password";

    static final LocalDateTime AS_OF = LocalDateTime.of(2026, 9, 20, 12, 0);

    /** 订单源最后更新时刻。 */
    static final LocalDateTime ORDER_TIME = LocalDateTime.of(2026, 9, 20, 10, 0);

    /** 发票源最后更新时刻（比订单早 2 小时）。 */
    static final LocalDateTime INVOICE_TIME = LocalDateTime.of(2026, 9, 20, 8, 0);

    /** 各来源的客户编号（订单 5 个：扇出足够大，行预算用例靠它触发）。 */
    static final List<String> ORDER_CUSTOMERS = List.of("C-001", "C-002", "C-003", "C-004", "C-005");

    static final List<String> INVOICE_CUSTOMERS = List.of("C-001", "C-002");

    static final List<String> PAYMENT_CUSTOMERS = List.of("C-001");

    /**
     * 来源定义：客户编号、金额、数据时间。
     *
     * <p>{@code source_as_of} 用 {@code MAX(updated_at)}——每个来源报自己的数据时间点，
     * 而不是执行时刻。
     */
    static final String DEFINITION =
            """
            {"grain": "一行一单",
             "time": {"field": "updated_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
             "fields": [
               {"name": "customer_id", "sourceColumn": "customer_id", "type": "STRING",
                "visibility": "INTERNAL"},
               {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                "visibility": "INTERNAL"},
               {"name": "updated_at", "sourceColumn": "updated_at", "type": "DATETIME",
                "visibility": "INTERNAL"}],
             "metrics": [
               {"name": "net_amount", "field": "amount", "aggregation": "SUM", "unit": "CURRENCY",
                "visibility": "INTERNAL"},
               {"name": "source_as_of", "field": "updated_at", "aggregation": "MAX", "unit": "NONE",
                "visibility": "INTERNAL"}],
             "dimensions": [{"name": "customer_name", "field": "customer_id"}]
            }
            """;

    private final JdbcTemplate jdbcTemplate;

    private final AiConnectorService connectorService;

    private final AiMysqlConnectorService mysqlConnectorService;

    private final AiDatasetService datasetService;

    private final QueryPlanSqlCompiler compiler = new QueryPlanSqlCompiler();

    private final AiQueryPlanValidator planValidator = new AiQueryPlanValidator();

    private Long connectorId;

    private Long orderDatasetId;

    private Long invoiceDatasetId;

    CrossSourceWarehouseFixture(
            JdbcTemplate jdbcTemplate,
            AiConnectorService connectorService,
            AiMysqlConnectorService mysqlConnectorService,
            AiDatasetService datasetService) {
        this.jdbcTemplate = jdbcTemplate;
        this.connectorService = connectorService;
        this.mysqlConnectorService = mysqlConnectorService;
        this.datasetService = datasetService;
    }

    /** 建库建表、建只读账号、登记连接器与三个已发布数据集版本。 */
    void prepare(int mysqlPort) {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        for (String table : List.of("orders", "invoices", "payments")) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + "." + table);
            jdbcTemplate.execute("CREATE TABLE " + CATALOG + "." + table + " ("
                    + "customer_id VARCHAR(32) NOT NULL, amount DECIMAL(18,2) NOT NULL, updated_at DATETIME NOT NULL)");
        }
        jdbcTemplate.execute("DROP USER IF EXISTS '" + READ_ONLY_USER + "'@'%'");
        jdbcTemplate.execute("CREATE USER '" + READ_ONLY_USER + "'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        grantReadOnAll();
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 跨源连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"" + READ_ONLY_USER + "\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\""
                        + CATALOG
                        + ".orders\",\"" + CATALOG + ".invoices\",\"" + CATALOG + ".payments\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        orderDatasetId = publishDataset("y04_orders", "orders");
        invoiceDatasetId = publishDataset("y04_invoices", "invoices");
        publishDataset("y04_payments", "payments");
    }

    /** 三个来源的真实数据（金额合计 100.00 / 25.00 / 5.00，数据时间各不相同）。 */
    void seedData() {
        seedOrdersAndInvoices();
        seedPayments();
    }

    /** 订单与发票：订单 5 个客户合计 100.00（更新到 10:00），发票 2 个客户合计 25.00（只到 08:00）。 */
    void seedOrdersAndInvoices() {
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".orders (customer_id, amount, updated_at) VALUES"
                + " ('C-001', 40.00, '2026-09-20 10:00:00'),"
                + " ('C-002', 30.00, '2026-09-20 10:00:00'),"
                + " ('C-003', 15.00, '2026-09-20 10:00:00'),"
                + " ('C-004', 10.00, '2026-09-20 10:00:00'),"
                + " ('C-005', 5.00, '2026-09-20 10:00:00')");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".invoices (customer_id, amount, updated_at) VALUES"
                + " ('C-001', 15.00, '2026-09-20 08:00:00'),"
                + " ('C-002', 10.00, '2026-09-20 08:00:00')");
    }

    /** 回款：1 个客户合计 5.00（更新到 09:00）。单独成方法是为了重试用例能只补回款。 */
    void seedPayments() {
        jdbcTemplate.update("DELETE FROM " + CATALOG + ".payments");
        jdbcTemplate.update("INSERT INTO " + CATALOG + ".payments (customer_id, amount, updated_at) VALUES"
                + " ('C-001', 5.00, '2026-09-20 09:00:00')");
    }

    /**
     * 撤回某个对象的只读授权，制造"该来源无权访问"的真实故障。
     *
     * <p>用的是数据库授权而不是测试替身：AT-071 与专项三要验证的正是
     * "来源真的取不到时平台怎么表现，以及修好后重试会不会重复汇总"。
     */
    void revokeReadOn(String table) {
        jdbcTemplate.execute("REVOKE SELECT ON " + CATALOG + "." + table + " FROM '" + READ_ONLY_USER + "'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");
    }

    /** 恢复某个对象的只读授权（重试前修复来源可用性）。 */
    void grantReadOn(String table) {
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + "." + table + " TO '" + READ_ONLY_USER + "'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");
    }

    Long connectorId() {
        return connectorId;
    }

    Long orderDatasetId() {
        return orderDatasetId;
    }

    Long invoiceDatasetId() {
        return invoiceDatasetId;
    }

    Long datasetIdOf(String datasetCode) {
        return jdbcTemplate.queryForObject("SELECT id FROM ai_dataset WHERE code = ?", Long.class, datasetCode);
    }

    /**
     * 用真实的 D06 编译器编译来源侧的预聚合 SQL（源内先聚合，再拉有界中间结果）。
     *
     * <p>维度是客户编号、指标是 {@code SUM(金额)} 与 {@code MAX(数据时间)}：
     * 跨源执行拿到的是"每个客户一行"，而不是展开后的明细——这正是扇出不重复的第一步。
     */
    CompiledQuery compiled(String datasetCode, List<String> customers, CrossSourceBudget budget) {
        Long datasetId = datasetIdOf(datasetCode);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_dataset_version WHERE dataset_id = ? AND status = 'PUBLISHED'"
                        + " ORDER BY version_no DESC LIMIT 1",
                Long.class,
                datasetId);
        var version = datasetService.getVersion(versionId);
        ResolvedDatasetVersion resolved = new ResolvedDatasetVersion(
                datasetId,
                datasetCode,
                versionId,
                version.getVersionNo(),
                version.getSchemaHash(),
                CATALOG + "." + tableOf(datasetCode),
                AiDatasetDefinition.parse(version.getDefinitionJson()),
                List.of());
        String planJson = "{\"schemaVersion\":\"1.0\",\"datasetId\":\"dset_" + datasetCode + "\",\"datasetVersion\":"
                + version.getVersionNo() + ",\"metrics\":[\"net_amount\",\"source_as_of\"],"
                + "\"dimensions\":[\"customer_name\"],\"filters\":[],\"timeRange\":null,\"orderBy\":[],\"limit\":"
                // 多编译一行作为探测位：D06 把 LIMIT 编译进 SQL，不留探测位就无法判定是否超预算
                + probeLimit(budget) + "}";
        var validated =
                planValidator.validate(planJson, resolved, Instant.ofEpochSecond(AS_OF.toEpochSecond(ZoneOffset.UTC)));
        return compiler.compile(
                validated, resolved, QueryScope.in("customer_id", new java.util.ArrayList<Object>(customers)));
    }

    private static String tableOf(String datasetCode) {
        if (datasetCode.endsWith("orders")) {
            return "orders";
        }
        return datasetCode.endsWith("invoices") ? "invoices" : "payments";
    }

    /**
     * 编译行数上限 = 预算 + 1 探测位。
     *
     * <p>少这一行，来源会"刚好"返回预算那么多行且 {@code truncated=false}，
     * 跨源层就会把一份被静默截断的预聚合当成完整结果。多一行让跨源层能看见"还有更多"，
     * 从而按受控结束拒绝，而不是交出一个偏小的合计。
     */
    static int probeLimit(CrossSourceBudget budget) {
        return Math.min(
                budget.maxSourceRows() + 1,
                com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest.MAX_ROWS);
    }

    private void grantReadOnAll() {
        for (String table : List.of("orders", "invoices", "payments")) {
            jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + "." + table + " TO '" + READ_ONLY_USER + "'@'%'");
        }
    }

    /** 建数据集并走真实的"建版本 → 验证 → 发布"链路。 */
    private Long publishDataset(String code, String table) {
        Long datasetId = datasetService.create(new AiDatasetSaveDTO()
                .setCode(code)
                .setName(code)
                .setConnectorId(connectorId)
                .setSourceObject(CATALOG + "." + table));
        Long versionId = datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(datasetId).setDefinitionJson(DEFINITION));
        datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        datasetService.publishVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        return datasetId;
    }

    /** 清理所有 IT 数据与外部对象（连接池、只读账号、表与库）。 */
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_dataset_version WHERE dataset_id IN"
                + " (SELECT id FROM ai_dataset WHERE code IN ('y04_orders','y04_invoices','y04_payments'))");
        jdbcTemplate.update("DELETE FROM ai_dataset WHERE code IN ('y04_orders','y04_invoices','y04_payments')");
        if (connectorId != null) {
            mysqlConnectorService.closePool(connectorId);
        }
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        jdbcTemplate.execute("DROP USER IF EXISTS '" + READ_ONLY_USER + "'@'%'");
        for (String table : List.of("orders", "invoices", "payments")) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + "." + table);
        }
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        connectorId = null;
        orderDatasetId = null;
        invoiceDatasetId = null;
    }
}
