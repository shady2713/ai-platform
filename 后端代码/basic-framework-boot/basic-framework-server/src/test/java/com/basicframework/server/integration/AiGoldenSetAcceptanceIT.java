package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.query.api.AiApiQueryPlanExecutor;
import com.basicframework.module.ai.service.query.api.AiApiQueryRequestDTO;
import com.basicframework.module.ai.service.query.compiler.AiCompiledQueryExecutor;
import com.basicframework.module.ai.service.query.compiler.QueryPlanSqlCompiler;
import com.basicframework.server.fixtures.ai.AiGoldenSetFixture;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D11 单系统查询黄金集验收（真实 MySQL + 受控出站夹具）：对**同一个合成销售系统**
 * 用 SQL 入口（D04 版本 → D05 校验 → D06 编译 → D03 执行）与 API 入口
 * （D02 已发布 operation → D07 归一化）各跑一遍，断言同一组黄金数字（740.00 / 450.00 / 290.00 / 190.00）。
 *
 * <p>确定性执行与真实模型评测分开：本类只做**确定性执行**（固定时钟、固定计划、固定夹具、固定上游分页），
 * 模型效果由 Q04/Q10 的评测套件负责（见 docs/testing/ai-golden-set-acceptance.md）。
 */
@Import(AiGoldenSetAcceptanceIT.FixtureHttpConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiGoldenSetAcceptanceIT extends AbstractPersistenceIntegrationTest {

    /**
     * 出站夹具：扮演"同一套系统"的 HTTP 上游——按 operation 声明的分页翻页，
     * 并按请求里的 {@code customer_name} 参数过滤条目（行范围真的传到了上游，不是本地过滤）。
     */
    @TestConfiguration
    static class FixtureHttpConfiguration {

        static final AtomicInteger CALLS = new AtomicInteger();

        /** 上游自称的总页数：2 = 两页取完；更大 = 页数上限命中（用于验证 PARTIAL）。 */
        static final AtomicInteger TOTAL_PAGES = new AtomicInteger(2);

        static final AtomicInteger LAST_PAGE = new AtomicInteger(0);

        @Bean
        ExternalHttpClient fixtureHttpClient() {
            return new ExternalHttpClient() {
                @Override
                public ExternalHttpResponse execute(ExternalHttpRequest request) {
                    CALLS.incrementAndGet();
                    int page = LAST_PAGE.incrementAndGet();
                    List<String> items = itemsOf(page, customerOf(request.url()));
                    boolean more = page < TOTAL_PAGES.get();
                    String body = "{\"data\":{\"items\":[" + String.join(",", items) + "]},"
                            + (more ? "\"next\":\"cursor-" + (page + 1) + "\"}" : "\"next\":null}");
                    return new ExternalHttpResponse(
                            200, Map.of(), body.getBytes(StandardCharsets.UTF_8), body.length());
                }

                @Override
                public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
                    return CompletableFuture.completedFuture(execute(request));
                }

                @Override
                public void close() {
                    // 夹具无资源
                }
            };
        }

        private static List<String> itemsOf(int page, String customer) {
            List<String> source = AiGoldenSetFixture.API_PAGES.get(page == 1 ? "page1" : "page2");
            if (customer == null) {
                return source;
            }
            List<String> filtered = new ArrayList<>();
            for (String item : source) {
                if (item.contains("\"customer_name\":\"" + customer + "\"")) {
                    filtered.add(item);
                }
            }
            return filtered;
        }

        /** 从请求 URL 里取出上游声明的 {@code customer_name} 查询参数。 */
        private static String customerOf(String url) {
            int question = url.indexOf('?');
            if (question < 0) {
                return null;
            }
            for (String pair : url.substring(question + 1).split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0
                        && "customer_name"
                                .equals(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8))) {
                    return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    private static final String CONNECTOR_CODE = "it-golden-connector";

    private static final String HTTP_CONNECTOR_CODE = "it-golden-http";

    private static final String PAYMENT_DATASET_CODE = "golden-payments";

    /** 只读账号口令（仅集成容器内的测试值）。 */
    private static final String READ_ONLY_PASSWORD = "d11-it-readonly-password";

    private static final String CATALOG = "golden_catalog";

    /** 固定时钟：时间窗口与边界判定基于它。 */
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private static final String OPEN_API_DOCUMENT =
            """
            {"openapi": "3.0.3",
             "info": {"title": "黄金集接口", "version": "1.0.0"},
             "paths": {"/sales": {"get": {"operationId": "getSales", "summary": "查询净额",
               "parameters": [{"name": "customer_name", "in": "query", "required": false,
                               "schema": {"type": "string"}}],
               "responses": {"200": {"description": "ok"}}}}}}
            """;

    /** 分页声明：游标分页，最多 3 页 → 上游一直说有下一页时结论必须是 PARTIAL。 */
    private static final String PAGINATION_JSON =
            "{\"type\":\"CURSOR\",\"maxPages\":3,\"cursorParam\":\"cursor\",\"cursorPath\":\"next\"}";

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiConnectorOperationService operationService;

    @Autowired
    private AiDatasetService datasetService;

    @Autowired
    private AiCompiledQueryExecutor compiledQueryExecutor;

    @Autowired
    private AiApiQueryPlanExecutor apiQueryPlanExecutor;

    private final QueryPlanSqlCompiler compiler = new QueryPlanSqlCompiler();

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private Long connectorId;

    private Long httpConnectorId;

    private Long salesDatasetId;

    private Long salesVersionId;

    private Long paymentDatasetId;

    private Long paymentVersionId;

    @BeforeEach
    void prepareSyntheticSystem() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_sales_golden");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_payments_golden");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".payments");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        AiGoldenSetFixture.ORDERS_DDL.forEach(jdbcTemplate::execute);
        AiGoldenSetFixture.DATA_DML.forEach(jdbcTemplate::update);

        jdbcTemplate.execute("DROP USER IF EXISTS 'd11_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'd11_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'd11_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("黄金集只读库")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"d11_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\""
                        + AiGoldenSetFixture.SOURCE_OBJECT + "\",\"" + AiGoldenSetFixture.PAYMENT_OBJECT + "\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        salesDatasetId = publishDataset(
                AiGoldenSetFixture.DATASET_CODE, AiGoldenSetFixture.SOURCE_OBJECT, AiGoldenSetFixture.DEFINITION);
        salesVersionId = latestVersionId(salesDatasetId);
        paymentDatasetId = publishDataset(
                PAYMENT_DATASET_CODE, AiGoldenSetFixture.PAYMENT_OBJECT, AiGoldenSetFixture.PAYMENT_DEFINITION);
        paymentVersionId = latestVersionId(paymentDatasetId);

        prepareHttpEntry();
        FixtureHttpConfiguration.CALLS.set(0);
        FixtureHttpConfiguration.LAST_PAGE.set(0);
        FixtureHttpConfiguration.TOTAL_PAGES.set(2);
    }

    /** API 入口：HTTP 连接器 + 导入的 operation（声明参数与分页）→ 发布。 */
    private void prepareHttpEntry() {
        httpConnectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(HTTP_CONNECTOR_CODE)
                .setName("黄金集接口")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson("{\"baseUrl\":\"https://golden.example.com\",\"method\":\"GET\"}"));
        operationService.importOperations(httpConnectorId, OPEN_API_DOCUMENT);
        Long operationId = operationService.listOperations(httpConnectorId).stream()
                .filter(operation -> "getSales".equals(operation.getOperationKey()))
                .findFirst()
                .orElseThrow()
                .getId();
        // 导入只带参数声明：列表路径与分页由配置人员补齐后才发布
        jdbcTemplate.update(
                "UPDATE ai_connector_operation SET pagination_json = ?, response_json = ? WHERE id = ?",
                PAGINATION_JSON,
                "{\"listPath\":\"data.items\"}",
                operationId);
        operationService.publish(
                operationId, operationService.getOperation(operationId).getVersion());
    }

    @AfterEach
    void cleanUp() {
        mysqlConnectorService.closePool(connectorId);
        jdbcTemplate.update(
                "DELETE FROM ai_dataset_version WHERE dataset_id IN (SELECT id FROM ai_dataset WHERE code IN (?, ?))",
                AiGoldenSetFixture.DATASET_CODE,
                PAYMENT_DATASET_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_dataset WHERE code IN (?, ?)", AiGoldenSetFixture.DATASET_CODE, PAYMENT_DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector_operation WHERE connector_id = ?", httpConnectorId);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code IN (?, ?)", CONNECTOR_CODE, HTTP_CONNECTOR_CODE);
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_sales_golden");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_payments_golden");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".payments");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP USER IF EXISTS 'd11_ro'@'%'");
        connectorId = null;
        httpConnectorId = null;
        salesDatasetId = null;
        salesVersionId = null;
        paymentDatasetId = null;
        paymentVersionId = null;
    }

    private static String mysqlPort() {
        return String.valueOf(AbstractPersistenceIntegrationTest.mysqlMappedPort());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    /** 建数据集 → 建版本 → 验证 → 发布（真实 MySQL 上的 D04 链路）。 */
    private Long publishDataset(String code, String sourceObject, String definition) {
        Long id = datasetService.create(new AiDatasetSaveDTO()
                .setCode(code)
                .setName(code)
                .setConnectorId(connectorId)
                .setSourceObject(sourceObject));
        Long versionId = datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(id).setDefinitionJson(definition));
        datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        datasetService.publishVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        return id;
    }

    private Long latestVersionId(Long datasetId) {
        return datasetService
                .getVersionPage(datasetId, new PageParam())
                .getList()
                .get(0)
                .getId();
    }

    /** 已发布版本解析出的目录（与规划器同一条解析路径）。 */
    private ResolvedDatasetVersion resolved(Long datasetId, Long versionId, String code, String sourceObject) {
        AiDatasetVersionDO version = datasetService.getVersion(versionId);
        return new ResolvedDatasetVersion(
                datasetId,
                code,
                versionId,
                version.getVersionNo(),
                version.getSchemaHash(),
                sourceObject,
                AiDatasetDefinition.parse(version.getDefinitionJson()),
                List.of());
    }

    private ResolvedDatasetVersion sales() {
        return resolved(
                salesDatasetId, salesVersionId, AiGoldenSetFixture.DATASET_CODE, AiGoldenSetFixture.SOURCE_OBJECT);
    }

    private ResolvedDatasetVersion payments() {
        return resolved(paymentDatasetId, paymentVersionId, PAYMENT_DATASET_CODE, AiGoldenSetFixture.PAYMENT_OBJECT);
    }

    /** SQL 入口：校验 → 编译 → 只读执行。 */
    private AiNormalizedResult runSqlEntry(String planJson, ResolvedDatasetVersion dataset, QueryScope scope) {
        ValidatedQueryPlan plan = validator.validate(planJson, dataset, NOW);
        CompiledQuery compiled = compiler.compile(plan, dataset, scope);
        var executed = compiledQueryExecutor.execute(connectorId, compiled);
        assertThat(executed.isTruncated()).as("黄金集的数据量远低于行数上限，出现截断说明用例或上限配置有问题").isFalse();
        return new AiNormalizedResult(
                null,
                executed.getRows(),
                AiNormalizedResult.COMPLETE,
                "no-more-rows",
                1,
                executed.getRowCount(),
                "COMPLETE");
    }

    /** API 入口：已校验计划 + 已发布 operation + 行范围（customer_name 由上游过滤）。 */
    private AiNormalizedResult runApiEntry(String planJson, String customer) {
        ValidatedQueryPlan plan = validator.validate(planJson, sales(), NOW);
        return apiQueryPlanExecutor.execute(new AiApiQueryRequestDTO(
                httpConnectorId, "getSales", plan, QueryScope.eq("customer_name", customer), null, null, null));
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal sumOf(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(row -> decimal(row.get(column))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void sqlEntryReturnsGoldenNumbersForEastScope() {
        AiNormalizedResult result =
                runSqlEntry(AiGoldenSetFixture.EAST_AUGUST_PLAN, sales(), QueryScope.in("region", List.of("杭州", "上海")));

        // AT-031：450.00 在前、290.00 在后（降序），合计 740.00
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsEntry("customer_name", "bob");
        assertThat(decimal(result.rows().get(0).get("total_net_amount")))
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C002_NET));
        assertThat(result.rows().get(1)).containsEntry("customer_name", "alice");
        assertThat(decimal(result.rows().get(1).get("total_net_amount")))
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C001_NET));
        assertThat(sumOf(result.rows(), "total_net_amount"))
                .as("AT-031：华东 8 月净额合计")
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_EAST_TOTAL));
    }

    @Test
    void sqlEntryHonoursRowScopeAndTimeBoundary() {
        // AT-032：Alice 个人范围（C001）只返回 290.00——行范围真的进了 WHERE，不是取全量后再过滤
        AiNormalizedResult alice =
                runSqlEntry(AiGoldenSetFixture.EAST_AUGUST_PLAN, sales(), QueryScope.eq("customer_id", "C001"));
        assertThat(alice.rows()).hasSize(1);
        assertThat(decimal(alice.rows().get(0).get("total_net_amount")))
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C001_NET));

        // AT-033：9 月 1 日 00:00:00（+08:00）的 999.00 订单被 8 月窗口排除
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + CATALOG + ".orders WHERE order_time >= '2026-09-01 00:00:00'",
                        Integer.class))
                .as("夹具里确实存在一条 9 月订单（否则边界断言没有意义）")
                .isEqualTo(1);
        assertThat(sumOf(alice.rows(), "total_net_amount")).as("9 月订单不参与 8 月聚合").isLessThan(new BigDecimal("999.00"));
    }

    @Test
    void sqlEntryKeepsPaymentsAsASeparateMetricWithoutDoubleCounting() {
        // AT-034：订单与回款分别聚合——净额 290.00 与回款 190.00 不得相加成 480.00
        AiNormalizedResult net =
                runSqlEntry(AiGoldenSetFixture.EAST_AUGUST_PLAN, sales(), QueryScope.eq("customer_id", "C001"));
        AiNormalizedResult payment =
                runSqlEntry(AiGoldenSetFixture.C001_PAYMENT_PLAN, payments(), QueryScope.eq("customer_id", "C001"));

        assertThat(decimal(net.rows().get(0).get("total_net_amount")))
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C001_NET));
        assertThat(payment.rows()).hasSize(1);
        assertThat(decimal(payment.rows().get(0).get("total_payment")))
                .as("AT-034：回款是独立指标（含 0.00 与 NULL 两条，不放大订单行）")
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C001_PAYMENT));
    }

    @Test
    void apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry() {
        // AT-038：两页都参与统计 → COMPLETE
        AiNormalizedResult bob = runApiEntry(AiGoldenSetFixture.API_PLAN, "bob");
        assertThat(bob.completeness()).isEqualTo(AiNormalizedResult.COMPLETE);
        assertThat(FixtureHttpConfiguration.CALLS.get()).as("两页都被取到").isEqualTo(2);
        assertThat(bob.rows()).hasSize(1);
        assertThat(decimal(bob.rows().get(0).get("total_net_amount")))
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C002_NET));

        FixtureHttpConfiguration.CALLS.set(0);
        FixtureHttpConfiguration.LAST_PAGE.set(0);
        AiNormalizedResult alice = runApiEntry(AiGoldenSetFixture.API_PLAN, "alice");
        assertThat(alice.completeness()).isEqualTo(AiNormalizedResult.COMPLETE);
        assertThat(alice.rows()).hasSize(1);
        assertThat(decimal(alice.rows().get(0).get("total_net_amount")))
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_C001_NET));

        // 两个入口必须给出同一组数字：API 的分客户结果之和 = SQL 的华东合计
        assertThat(sumOf(bob.rows(), "total_net_amount").add(sumOf(alice.rows(), "total_net_amount")))
                .as("AT-031/AT-038：API 入口与 SQL 入口的合计一致")
                .isEqualByComparingTo(new BigDecimal(AiGoldenSetFixture.EXPECTED_EAST_TOTAL));
    }

    @Test
    void apiEntryReportsPartialWhenPaginationIsTruncated() {
        FixtureHttpConfiguration.TOTAL_PAGES.set(99);
        AiNormalizedResult result = runApiEntry(AiGoldenSetFixture.API_PLAN, "bob");

        // AT-039：命中页数上限 → PARTIAL + 原因，绝不宣称完整统计
        assertThat(result.completeness()).isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(result.completeStatistics()).isFalse();
        assertThat(result.reason()).isEqualTo("page-limit");
        assertThat(FixtureHttpConfiguration.CALLS.get()).isEqualTo(3);
    }

    @Test
    void unauthorizedAccessIsBlockedBeforeAnyQuery() {
        // 行范围列不在数据集目录内：拒绝编译（不退回全库）
        ValidatedQueryPlan plan = validator.validate(AiGoldenSetFixture.EAST_AUGUST_PLAN, sales(), NOW);
        assertThatThrownBy(() -> compiler.compile(plan, sales(), QueryScope.eq("secret_owner", "x")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));
        // 没有行范围授权：拒绝生成查询
        assertThatThrownBy(() -> compiler.compile(plan, sales(), new QueryScope(List.of())))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));
        // 未授权来源（不在连接器白名单内）
        assertThatThrownBy(() -> datasetService.create(new AiDatasetSaveDTO()
                        .setCode("golden-unauthorized")
                        .setName("未授权来源")
                        .setConnectorId(connectorId)
                        .setSourceObject(CATALOG + ".payments")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_SOURCE_NOT_AUTHORIZED));
        // 行范围参数未在 operation 声明：API 入口拒绝执行（不会"没有行约束就查全量"）
        assertThatThrownBy(() -> apiQueryPlanExecutor.execute(new AiApiQueryRequestDTO(
                        httpConnectorId, "getSales", plan, QueryScope.eq("customer_id", "C001"), null, null, null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));
    }

    @Test
    void schemaDriftMarksTheNewVersionUnpublishable() {
        // AT-042：上游真的变了（net_amount 列消失）→ 新版本验证为 DRIFTED 且不可发布
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_sales_golden");
        jdbcTemplate.execute("CREATE VIEW " + CATALOG + ".v_sales_golden AS"
                + " SELECT o.id AS order_id, o.customer_id AS customer_id, o.customer_name AS customer_name,"
                + " o.region AS region, o.order_time AS order_time, o.amount AS gross_amount"
                + " FROM " + CATALOG + ".orders o");
        Long driftedVersion = datasetService.createVersion(new AiDatasetVersionSaveDTO()
                .setDatasetId(salesDatasetId)
                .setDefinitionJson(AiGoldenSetFixture.DEFINITION));
        var verify = datasetService.verifyVersion(
                driftedVersion, datasetService.getVersion(driftedVersion).getVersion());

        assertThat(verify.getVerificationStatus()).isEqualTo(AiDatasetVersionDO.VERIFICATION_DRIFTED);
        assertThat(verify.getMissingColumns()).containsExactly("net_amount");
        assertThat(verify.isPublishable()).isFalse();
        assertThat(verify.getSourceSchemaHash())
                .as("上游结构哈希确实变了（不是定义写错）")
                .isNotEqualTo(datasetService.getVersion(salesVersionId).getSourceSchemaHash());
        assertThatThrownBy(() -> datasetService.publishVersion(
                        driftedVersion,
                        datasetService.getVersion(driftedVersion).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED));
    }

    @Test
    void dangerousPlanShapesAreRejectedByTheDeterministicChain() {
        // AT-036：SQL 片段、危险函数与脚本式指标在计划校验处就被拒绝（不进入编译与执行）
        for (String planJson : List.of(
                AiGoldenSetFixture.EAST_AUGUST_PLAN.replace("\"total_net_amount\"", "\"SUM(net_amount)\""),
                AiGoldenSetFixture.EAST_AUGUST_PLAN.replace(
                        "\"total_net_amount\"", "\"total_net_amount'; DROP TABLE orders\""),
                AiGoldenSetFixture.EAST_AUGUST_PLAN.replace("\"total_net_amount\"", "\"load_file\""))) {
            assertThatThrownBy(() -> validator.validate(planJson, sales(), NOW))
                    .as("危险计划必须被拒绝：%s", planJson.substring(0, 60))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SQL_REJECTED));
        }
        // 越权数据集（模型提到别的数据集）同样在计划校验处被拒绝
        assertThatThrownBy(() -> validator.validate(
                        AiGoldenSetFixture.EAST_AUGUST_PLAN.replace("dset_golden-sales", "dset_other-dataset"),
                        sales(),
                        NOW))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED));
    }

    @Test
    void ambiguousOrUnknownMetricIsNeverGuessed() {
        // AT-035：模型用业务别名（"销售额"）时要求澄清；引用不存在的指标时直接拒绝——都不猜着执行
        assertThatThrownBy(() -> validator.validate(
                        AiGoldenSetFixture.EAST_AUGUST_PLAN.replace("\"total_net_amount\"", "\"销售额\""), sales(), NOW))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_CLARIFICATION_REQUIRED));
        assertThatThrownBy(() -> validator.validate(
                        AiGoldenSetFixture.EAST_AUGUST_PLAN.replace("\"total_net_amount\"", "\"unknown_metric\""),
                        sales(),
                        NOW))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
    }
}
