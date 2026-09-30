package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.SqlParameter;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 统一结果结构与取数请求的形状校验（Y04）。
 *
 * <p>统一结果必须把"各源时间点、偏移、预算用量、缺失来源"作为**一等字段**带出来——
 * 它们一旦只进日志，调用方就再也无法判断这个跨源数字能不能用。
 */
class CrossSourceExecutionResultTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 9, 20, 10, 0);

    @Test
    void exposesEverySourceTimePointAndTheBudgetUsageAsFirstClassFields() {
        CrossSourceExecutionResult result = result(List.of("invoice"), true);

        assertThat(result.sources()).hasSize(1);
        assertThat(result.sourceOf("order").asOf()).isEqualTo(AS_OF);
        assertThat(result.sourceOf("order").counted()).isTrue();
        assertThat(result.sourceOf("missing")).isNull();
        assertThat(result.budgetUsage().totalBytes()).isEqualTo(2_048);
        assertThat(result.budgetUsage().totalRows()).isEqualTo(2);
        assertThat(result.budgetUsage().maxConcurrentUsed()).isEqualTo(1);
        assertThat(result.skew()).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(result.describe())
                .contains("metric=net_revenue", "total=125.00 CNY", "missing=1", "skew=300000ms", "bytes=2048");
    }

    @Test
    void aResultWithMissingSourcesIsNotUsable() {
        // 缺来源的结果不能被当成完整结果：缺口必须由调用方显式接受
        assertThat(result(List.of("payment"), false).usable()).isFalse();
        assertThat(result(List.of(), true).usable()).isTrue();
    }

    @Test
    void normalisesNullCollectionsAndAmounts() {
        CrossSourceExecutionResult result =
                new CrossSourceExecutionResult("k", "p", "m", 1, "CNY", null, null, null, 0, null, null, true);

        assertThat(result.sources()).isEmpty();
        assertThat(result.missingRoles()).isEmpty();
        assertThat(result.usable()).isFalse();
        assertThat(new CrossSourceExecutionResult.SourceResult("order", "d", 1, 1L, null, 0, 0, AS_OF, 0, true)
                        .amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void budgetUsageNeverGoesNegative() {
        // 负用量说明计量被破坏；夹成 0 会让"超预算"看起来像"没超"
        assertThat(new CrossSourceExecutionResult.BudgetUsage(-1, -1, -1).totalBytes())
                .isZero();
        assertThat(new CrossSourceExecutionResult.BudgetUsage(-1, -1, -1).totalRows())
                .isZero();
        assertThat(new CrossSourceExecutionResult.BudgetUsage(-1, -1, -1).maxConcurrentUsed())
                .isZero();
    }

    // ---- 取数请求的形状校验 ----

    @Test
    void acceptsAWellFormedSourceRequest() {
        CrossSourceSourceRequest request = request(1L);

        assertThat(request.entityKeyCount()).isEqualTo(1);
        assertThat(request.describe()).contains("role=order", "revision=1", "optional=false");
    }

    @Test
    void refusesRequestsWithoutAnEntityKeyOrWithoutACompiledQuery() {
        assertCode(
                () -> new CrossSourceSourceRequest(
                        "order",
                        "dset_orders",
                        1,
                        9L,
                        compiledQuery(),
                        List.of(),
                        1L,
                        "entity_key",
                        "net_amount",
                        timeColumn(),
                        false),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> new CrossSourceSourceRequest(
                        "order",
                        "dset_orders",
                        1,
                        9L,
                        null,
                        keys(1L),
                        1L,
                        "entity_key",
                        "net_amount",
                        timeColumn(),
                        false),
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
    }

    @Test
    void refusesMixedMappingRevisionsInsideOneSource() {
        // 一个来源内部的键就跨版本：还没跨源就已经会把两份事实并成一份
        assertCode(
                () -> new CrossSourceSourceRequest(
                        "order",
                        "dset_orders",
                        1,
                        9L,
                        compiledQuery(),
                        List.of(new CrossSourceEntityKey("C-1", 1L), new CrossSourceEntityKey("C-2", 2L)),
                        1L,
                        "entity_key",
                        "net_amount",
                        timeColumn(),
                        false),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
        assertCode(
                () -> new CrossSourceSourceRequest(
                        "order",
                        "dset_orders",
                        1,
                        9L,
                        compiledQuery(),
                        keys(2L),
                        1L,
                        "entity_key",
                        "net_amount",
                        timeColumn(),
                        false),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
    }

    @Test
    void refusesMalformedRolesColumnsAndIdentifiers() {
        assertCode(() -> withRole("Order"), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(() -> withRole("1order"), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(() -> withDatasetCode(" "), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(() -> withDatasetVersion(0), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(() -> withConnectorId(0L), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(() -> withColumn("EntityKey"), AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(() -> withAmountColumn("NetAmount"), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(() -> withTimeColumn("Source_As_Of"), AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
    }

    // ---- 夹具 ----

    private static CrossSourceExecutionResult result(List<String> missing, boolean complete) {
        return new CrossSourceExecutionResult(
                "exec-1",
                "plan-1",
                "net_revenue",
                1,
                "CNY",
                new BigDecimal("125.00"),
                List.of(new CrossSourceExecutionResult.SourceResult(
                        "order", "dset_orders", 2, 1L, new BigDecimal("125.00"), 2, 2_048, AS_OF, 30, true)),
                AS_OF.minusMinutes(5),
                300_000,
                missing,
                new CrossSourceExecutionResult.BudgetUsage(2_048, 2, 1),
                complete);
    }

    private static CrossSourceSourceRequest request(long revision) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                1,
                9L,
                compiledQuery(),
                keys(revision),
                revision,
                "entity_key",
                "net_amount",
                timeColumn(),
                false);
    }

    private static CrossSourceSourceRequest withRole(String role) {
        return new CrossSourceSourceRequest(
                role,
                "dset_orders",
                1,
                9L,
                compiledQuery(),
                keys(1L),
                1L,
                "entity_key",
                "net_amount",
                timeColumn(),
                false);
    }

    private static CrossSourceSourceRequest withDatasetCode(String code) {
        return new CrossSourceSourceRequest(
                "order", code, 1, 9L, compiledQuery(), keys(1L), 1L, "entity_key", "net_amount", timeColumn(), false);
    }

    private static CrossSourceSourceRequest withDatasetVersion(int version) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                version,
                9L,
                compiledQuery(),
                keys(1L),
                1L,
                "entity_key",
                "net_amount",
                timeColumn(),
                false);
    }

    private static CrossSourceSourceRequest withConnectorId(long connectorId) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                1,
                connectorId,
                compiledQuery(),
                keys(1L),
                1L,
                "entity_key",
                "net_amount",
                timeColumn(),
                false);
    }

    private static CrossSourceSourceRequest withColumn(String column) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                1,
                9L,
                compiledQuery(),
                keys(1L),
                1L,
                column,
                "net_amount",
                timeColumn(),
                false);
    }

    private static CrossSourceSourceRequest withAmountColumn(String column) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                1,
                9L,
                compiledQuery(),
                keys(1L),
                1L,
                "entity_key",
                column,
                timeColumn(),
                false);
    }

    private static CrossSourceSourceRequest withTimeColumn(String column) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                1,
                9L,
                compiledQuery(),
                keys(1L),
                1L,
                "entity_key",
                "net_amount",
                new CrossSourceSourceRequest.LocalDateTimeColumn(column),
                false);
    }

    private static List<CrossSourceEntityKey> keys(long revision) {
        return List.of(new CrossSourceEntityKey("C-1", revision));
    }

    private static CrossSourceSourceRequest.LocalDateTimeColumn timeColumn() {
        return new CrossSourceSourceRequest.LocalDateTimeColumn("source_as_of");
    }

    private static CompiledQuery compiledQuery() {
        return new CompiledQuery(
                "SELECT customer AS entity_key, SUM(amount) AS net_amount, MAX(updated_at) AS source_as_of"
                        + " FROM catalog.orders WHERE customer IN (?) GROUP BY customer LIMIT ?",
                List.of(SqlParameter.string("C-1"), SqlParameter.number(10L)),
                List.of(new CompiledQuery.ResultColumn("entity_key", "客户", "STRING")),
                10,
                5_000,
                1L,
                "plan-1",
                "catalog.orders");
    }

    private static void assertCode(
            Runnable operation, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expected.getCode()));
    }
}
