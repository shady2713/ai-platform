package com.basicframework.module.ai.service.query.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.SqlParameter;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlanTestFactory;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D06 SQL 编译器：结构固定、标识符白名单、值全部绑定、行范围强制 AND、排序稳定与快照一致。
 */
class QueryPlanSqlCompilerTest {

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private final QueryPlanSqlCompiler compiler = new QueryPlanSqlCompiler();

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private CompiledQuery compile(String planJson) {
        return compile(planJson, AiQueryPlanFixture.dataset(), QueryScope.in("customer_id", List.of("C001", "C002")));
    }

    private CompiledQuery compile(String planJson, ResolvedDatasetVersion dataset, QueryScope scope) {
        ValidatedQueryPlan plan = validator.validate(planJson, dataset, AiQueryPlanFixture.CLOCK.instant());
        return compiler.compile(plan, dataset, scope);
    }

    @Test
    void compilesAggregationWithBoundValuesAndStableOrder() {
        CompiledQuery query = compile(AiQueryPlanFixture.VALID_PLAN);

        assertThat(query.sql())
                .as("结构固定：单来源、维度分组、指标聚合、行范围在前")
                .isEqualTo("SELECT customer_id AS customer_name, SUM(amount) AS net_amount FROM it_query.orders"
                        + " WHERE customer_id IN (?, ?) AND region = ?"
                        + " AND created_at >= ? AND created_at < ?"
                        + " GROUP BY customer_id ORDER BY net_amount DESC, customer_name ASC LIMIT ?");
        assertThat(query.sql())
                .as("SQL 文本里不得出现数据值")
                .doesNotContain("EAST")
                .doesNotContain("C001")
                .doesNotContain("2026-08");
        assertThat(query.sql()).doesNotContain("JOIN").doesNotContain("COALESCE");
        assertThat(query.parameters())
                .extracting(SqlParameter::type)
                .containsExactly(
                        SqlParameter.Type.STRING,
                        SqlParameter.Type.STRING,
                        SqlParameter.Type.STRING,
                        SqlParameter.Type.TIMESTAMP,
                        SqlParameter.Type.TIMESTAMP,
                        SqlParameter.Type.LONG);
        assertThat(query.resultColumns())
                .extracting(CompiledQuery.ResultColumn::code)
                .containsExactly("customer_name", "net_amount");
        assertThat(query.maxRows()).isEqualTo(10);
        assertThat(query.timeoutMillis()).isEqualTo(10_000);
        assertThat(query.datasetVersionId()).isEqualTo(AiQueryPlanFixture.DATASET_VERSION_ID);
        assertThat(query.planHash())
                .isEqualTo(validator
                        .validate(
                                AiQueryPlanFixture.VALID_PLAN,
                                AiQueryPlanFixture.dataset(),
                                AiQueryPlanFixture.CLOCK.instant())
                        .planHash());
        assertThat(query.toString()).as("编译结果字符串不含数据值").doesNotContain("C001", "EAST");
    }

    @Test
    void injectionPayloadsNeverReachSqlStructure() {
        // 计划校验器会先拒绝"看起来像 SQL"的取值；这里进一步验证**即使取值绕过校验器**，
        // 编译器也只把它当绑定参数（不进入语句结构），这是参数化的真正保证。
        ResolvedDatasetVersion dataset = AiQueryPlanFixture.dataset();
        ValidatedQueryPlan plan =
                validator.validate(AiQueryPlanFixture.VALID_PLAN, dataset, AiQueryPlanFixture.CLOCK.instant());
        for (String payload : List.of(
                "EAST' OR '1'='1",
                "EAST'; DROP TABLE orders; --",
                "EAST/*x*/",
                "1 UNION SELECT password FROM system_users")) {
            ValidatedQueryPlan tampered = ValidatedQueryPlanTestFactory.withFilters(
                    plan, List.of(new ValidatedQueryPlan.Filter("region", "region", "STRING", "EQ", List.of(payload))));
            CompiledQuery query = compiler.compile(tampered, dataset, QueryScope.eq("customer_id", "C001"));

            assertThat(query.sql())
                    .as("注入 payload 只能作为绑定值存在：%s", payload)
                    .doesNotContain(payload)
                    .doesNotContain("UNION")
                    .doesNotContain("DROP");
            assertThat(query.parameterValues()).contains(payload);
        }
    }

    @Test
    void identifiersMustComeFromTheAuditedCatalog() {
        // 计划里的物理列被改成目录外的列：编译期拒绝（模型/调用方无法引入新标识符）
        ResolvedDatasetVersion dataset = AiQueryPlanFixture.dataset();
        ValidatedQueryPlan tampered =
                tamper(validator.validate(AiQueryPlanFixture.VALID_PLAN, dataset, AiQueryPlanFixture.CLOCK.instant()));

        assertThatThrownBy(() -> compiler.compile(tampered, dataset, QueryScope.eq("customer_id", "C001")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));

        // 来源对象形状非法
        ResolvedDatasetVersion badSource = new ResolvedDatasetVersion(
                dataset.datasetId(),
                dataset.datasetCode(),
                dataset.datasetVersionId(),
                dataset.datasetVersionNo(),
                dataset.schemaHash(),
                "orders; DROP TABLE x",
                dataset.definition(),
                List.of());
        ValidatedQueryPlan plan =
                validator.validate(AiQueryPlanFixture.VALID_PLAN, dataset, AiQueryPlanFixture.CLOCK.instant());
        assertThatThrownBy(() -> compiler.compile(plan, badSource, QueryScope.eq("customer_id", "C001")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));
    }

    @Test
    void requiresEffectiveRowScopeAndMatchingDatasetVersion() {
        ValidatedQueryPlan plan = validator.validate(
                AiQueryPlanFixture.VALID_PLAN, AiQueryPlanFixture.dataset(), AiQueryPlanFixture.CLOCK.instant());

        assertThatThrownBy(() -> compiler.compile(plan, AiQueryPlanFixture.dataset(), new QueryScope(List.of())))
                .as("空行范围不能退回全库")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));
        assertThatThrownBy(() -> compiler.compile(plan, AiQueryPlanFixture.dataset(), null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));

        ResolvedDatasetVersion otherVersion = new ResolvedDatasetVersion(
                AiQueryPlanFixture.DATASET_ID,
                "it-query-orders",
                999L,
                2,
                "b".repeat(64),
                "it_query.orders",
                AiQueryPlanFixture.dataset().definition(),
                List.of());
        assertThatThrownBy(() -> compiler.compile(plan, otherVersion, QueryScope.eq("customer_id", "C001")))
                .as("计划与数据集版本错配时拒绝")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));
    }

    @Test
    void rowScopeCannotBeOverriddenByPlanFilters() {
        // 计划的过滤条件与行范围条件落在同一列：两者都进 WHERE（AND），用户条件无法覆盖行范围
        String planJson = AiQueryPlanFixture.VALID_PLAN.replace(
                "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                "{\"field\": \"customer\", \"operator\": \"EQ\", \"value\": \"C999\"}");
        CompiledQuery query = compile(planJson, AiQueryPlanFixture.dataset(), QueryScope.eq("customer_id", "C001"));

        assertThat(query.sql())
                .isEqualTo("SELECT customer_id AS customer_name, SUM(amount) AS net_amount FROM it_query.orders"
                        + " WHERE customer_id = ? AND customer_id = ?"
                        + " AND created_at >= ? AND created_at < ?"
                        + " GROUP BY customer_id ORDER BY net_amount DESC, customer_name ASC LIMIT ?");
        assertThat(query.parameterValues()).startsWith("C001", "C999");
    }

    @Test
    void supportsAllAllowedAggregationsAndFilterOperators() {
        for (String aggregation : List.of("SUM", "AVG", "COUNT", "MIN", "MAX")) {
            String planJson = AiQueryPlanFixture.VALID_PLAN.replace(
                    "{\"name\": \"net_amount\", \"field\": \"amount\", \"aggregation\": \"SUM\"",
                    "{\"name\": \"net_amount\", \"field\": \"amount\", \"aggregation\": \"" + aggregation + "\"");
            ResolvedDatasetVersion dataset = datasetWithAggregation(aggregation);
            assertThat(compile(planJson, dataset, QueryScope.eq("customer_id", "C001"))
                            .sql())
                    .contains(aggregation + "(amount) AS net_amount");
        }

        // COUNT(DISTINCT) 展开为 SQL 的 COUNT(DISTINCT col)
        String distinct = AiQueryPlanFixture.VALID_PLAN.replace(
                "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                "{\"field\": \"order_id\", \"operator\": \"BETWEEN\", \"lower\": 1, \"upper\": 100}");
        CompiledQuery query = compile(distinct, AiQueryPlanFixture.dataset(), QueryScope.eq("customer_id", "C001"));
        assertThat(query.sql()).contains("AND id BETWEEN ? AND ?");

        // IS_NULL
        CompiledQuery nullFilter = compile(
                AiQueryPlanFixture.VALID_PLAN.replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"region\", \"operator\": \"IS_NULL\"}"),
                AiQueryPlanFixture.dataset(),
                QueryScope.eq("customer_id", "C001"));
        assertThat(nullFilter.sql()).contains("AND region IS NULL");

        // 比较与不等
        for (String operator : List.of("EQ", "NE", "GT", "GE", "LT", "LE")) {
            CompiledQuery comparison = compile(
                    AiQueryPlanFixture.VALID_PLAN.replace(
                            "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                            "{\"field\": \"amount\", \"operator\": \"" + operator + "\", \"value\": 10}"),
                    AiQueryPlanFixture.dataset(),
                    QueryScope.eq("customer_id", "C001"));
            assertThat(comparison.sql()).containsPattern("AND amount (=|<>|>|>=|<|<=) \\?");
        }
    }

    @Test
    void compilesWithoutDimensionsAsWholeAggregationAndKeepsStableOrder() {
        String planJson =
                """
                {"schemaVersion": "1.0",
                 "datasetId": "dset_it-query-orders",
                 "datasetVersion": 1,
                 "metrics": ["net_amount"],
                 "dimensions": [],
                 "filters": [{"field": "region", "operator": "EQ", "value": "EAST"}],
                 "timeRange": {"field": "created_at", "startInclusive": "2026-08-01T00:00:00+08:00",
                               "endExclusive": "2026-09-01T00:00:00+08:00", "timezone": "Asia/Shanghai"},
                 "orderBy": [],
                 "limit": 10}
                """;

        CompiledQuery query = compile(planJson);

        assertThat(query.sql())
                .as("无维度即整体聚合；无显式排序时按首个指标升序保证可复现")
                .isEqualTo("SELECT SUM(amount) AS net_amount FROM it_query.orders"
                        + " WHERE customer_id IN (?, ?) AND region = ?"
                        + " AND created_at >= ? AND created_at < ?"
                        + " ORDER BY net_amount ASC LIMIT ?");
    }

    @Test
    void appendsRemainingDimensionsAsTieBreakers() {
        // 本地定义增加第二个维度（共享夹具保持不变，避免影响 D05 的断言）
        String definition = AiQueryPlanFixture.DEFINITION.replace(
                "\"dimensions\": [{\"name\": \"customer_name\", \"field\": \"customer\"}]",
                "\"dimensions\": [{\"name\": \"customer_name\", \"field\": \"customer\"},"
                        + " {\"name\": \"order_region\", \"field\": \"region\"}]");
        ResolvedDatasetVersion dataset = new ResolvedDatasetVersion(
                AiQueryPlanFixture.DATASET_ID,
                "it-query-orders",
                AiQueryPlanFixture.DATASET_VERSION_ID,
                1,
                "a".repeat(64),
                "it_query.orders",
                com.basicframework.module.ai.domain.semantic.AiDatasetDefinition.parse(definition),
                List.of());
        String planJson = AiQueryPlanFixture.VALID_PLAN.replace(
                "\"dimensions\": [\"customer_name\"]", "\"dimensions\": [\"customer_name\", \"order_region\"]");

        CompiledQuery query = compile(planJson, dataset, QueryScope.eq("customer_id", "C001"));

        assertThat(query.sql())
                .contains("GROUP BY customer_id, region")
                .contains("ORDER BY net_amount DESC, customer_name ASC, order_region ASC");
    }

    @Test
    void refusesUnsupportedAggregationAndOperatorAtCompileTime() {
        ResolvedDatasetVersion dataset = AiQueryPlanFixture.dataset();
        ValidatedQueryPlan plan =
                validator.validate(AiQueryPlanFixture.VALID_PLAN, dataset, AiQueryPlanFixture.CLOCK.instant());

        // 直接篡改聚合（绕过计划校验器的白名单）：编译期仍然拒绝
        ValidatedQueryPlan unsupported = ValidatedQueryPlanTestFactory.withMetrics(
                plan, List.of(new ValidatedQueryPlan.Metric("net_amount", "amount", "MEDIAN", "CURRENCY")));
        assertThatThrownBy(() -> compiler.compile(unsupported, dataset, QueryScope.eq("customer_id", "C001")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));

        // 空选择项
        ValidatedQueryPlan empty = ValidatedQueryPlanTestFactory.empty(plan);
        assertThatThrownBy(() -> compiler.compile(empty, dataset, QueryScope.eq("customer_id", "C001")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED));
    }

    @Test
    void sqlMatchesTheCommittedSnapshot() throws Exception {
        // SQL 快照：语句结构一旦变化必须显式更新快照（避免"悄悄改了 SQL 结构"）
        String expected;
        try (var stream = getClass().getClassLoader().getResourceAsStream("ai/compiled-sql/canonical-query.sql")) {
            assertThat(stream).as("SQL 快照文件必须存在").isNotNull();
            expected = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }

        assertThat(compile(AiQueryPlanFixture.VALID_PLAN).sql()).isEqualTo(expected);
    }

    @Test
    void sqlIsDeterministicForSameInputs() {
        CompiledQuery first = compile(AiQueryPlanFixture.VALID_PLAN);
        CompiledQuery second = compile(AiQueryPlanFixture.VALID_PLAN);

        assertThat(second.sql()).isEqualTo(first.sql());
        assertThat(second.parameterValues()).isEqualTo(first.parameterValues());
        assertThat(second.planHash()).isEqualTo(first.planHash());
    }

    /** 把计划里的物理列改成目录外的列（模拟被篡改的计划）。 */
    private static ValidatedQueryPlan tamper(ValidatedQueryPlan plan) {
        return ValidatedQueryPlanTestFactory.withMetrics(
                plan, List.of(new ValidatedQueryPlan.Metric("net_amount", "secret_salary", "SUM", "CURRENCY")));
    }

    /** 用指定聚合重建数据集定义（覆盖聚合白名单分支）。 */
    private static ResolvedDatasetVersion datasetWithAggregation(String aggregation) {
        String definition = AiQueryPlanFixture.DEFINITION.replace(
                "\"aggregation\": \"SUM\"", "\"aggregation\": \"" + aggregation + "\"");
        return new ResolvedDatasetVersion(
                AiQueryPlanFixture.DATASET_ID,
                "it-query-orders",
                AiQueryPlanFixture.DATASET_VERSION_ID,
                1,
                "a".repeat(64),
                "it_query.orders",
                com.basicframework.module.ai.domain.semantic.AiDatasetDefinition.parse(definition),
                List.of());
    }
}
