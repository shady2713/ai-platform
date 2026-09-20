package com.basicframework.module.ai.service.query.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.query.AiQueryPlanValidator;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D07 结果归一化：投影白名单、类型/金额归一、格式漂移、聚合口径与完整性结论。 */
class AiApiResultNormalizerTest {

    private final AiApiResultNormalizer normalizer = new AiApiResultNormalizer();

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private ValidatedQueryPlan plan() {
        return validator.validate(
                AiQueryPlanFixture.VALID_PLAN, AiQueryPlanFixture.dataset(), AiQueryPlanFixture.CLOCK.instant());
    }

    private static String item(String customer, String amount) {
        return "{\"customer_id\":\"" + customer + "\",\"amount\":" + amount + ",\"internal_note\":\"不应出现\"}";
    }

    @Test
    void projectsToPlanColumnsAndAggregatesExactDecimals() {
        AiNormalizedResult result = normalizer.normalize(
                plan(),
                List.of(item("C001", "12.50"), item("C001", "7.00"), item("C002", "300.00")),
                "COMPLETE",
                "no-more-pages",
                1);

        assertThat(result.completeStatistics()).isTrue();
        assertThat(result.schema().codes()).containsExactly("customer_name", "net_amount");
        assertThat(result.rows()).hasSize(2);
        Map<String, Object> c001 = result.rows().get(0);
        assertThat(c001.keySet()).as("上游多余字段在进入平台前就被投影丢弃").containsExactly("customer_name", "net_amount");
        assertThat((BigDecimal) c001.get("net_amount"))
                .as("SUM 用十进制精确相加")
                .isEqualByComparingTo(new BigDecimal("19.50"));
        assertThat(result.toString()).as("归一化结果字符串不含行数据").doesNotContain("C001", "19.50");
    }

    @Test
    void acceptsPhysicalColumnNamesAsFallbackKeys() {
        AiNormalizedResult result = normalizer.normalize(
                plan(), List.of("{\"customer_id\":\"C009\",\"net_amount\":\"5.25\"}"), "COMPLETE", null, 1);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsEntry("customer_name", "C009");
        assertThat((BigDecimal) result.rows().get(0).get("net_amount")).isEqualByComparingTo(new BigDecimal("5.25"));
    }

    @Test
    void refusesFormatDriftInsteadOfSilentlyReturningEmpty() {
        // 条目不是 JSON 对象
        assertThatThrownBy(() -> normalizer.normalize(plan(), List.of("not-json"), "COMPLETE", null, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_RESULT_FORMAT_DRIFT));

        // 条目里一个期望列都没有（上游字段名变了）
        assertThatThrownBy(() -> normalizer.normalize(plan(), List.of("{\"other_field\":1}"), "COMPLETE", null, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_RESULT_FORMAT_DRIFT));

        // 金额不是十进制数
        assertThatThrownBy(
                        () -> normalizer.normalize(plan(), List.of(item("C001", "\"1,234.00\"")), "COMPLETE", null, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_RESULT_INVALID));

        // 空结果（没有任何条目）是合法结果，不是漂移
        AiNormalizedResult empty = normalizer.normalize(plan(), List.of(), "COMPLETE", "no-more-pages", 1);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.completeStatistics()).isTrue();
    }

    @Test
    void completenessNeverUpgradesPartialOrFailedSources() {
        AiNormalizedResult partial =
                normalizer.normalize(plan(), List.of(item("C001", "1.00")), "PARTIAL", "page-limit", 20);
        assertThat(partial.completeness()).isEqualTo(AiNormalizedResult.PARTIAL);
        assertThat(partial.completeStatistics()).as("截断结果不得宣称完整统计").isFalse();
        assertThat(partial.reason()).isEqualTo("page-limit");

        AiNormalizedResult failed =
                normalizer.normalize(plan(), List.of(item("C001", "1.00")), "FAILED", "HTTP_503", 2);
        assertThat(failed.completeness()).isEqualTo(AiNormalizedResult.FAILED);
        assertThat(failed.completeStatistics()).isFalse();
        assertThat(failed.reason()).isEqualTo("HTTP_503");

        AiNormalizedResult repeated =
                normalizer.normalize(plan(), List.of(item("C001", "1.00")), "COMPLETE", "repeated-cursor", 3);
        assertThat(repeated.completeStatistics())
                .as("上游虽报 COMPLETE，但停止原因是重复游标：保留原因")
                .isTrue();
        assertThat(repeated.reason()).isEqualTo("repeated-cursor");
    }

    @Test
    void aggregatesAllAllowedAggregationsWithNullSemantics() {
        for (String aggregation : List.of("SUM", "AVG", "COUNT", "COUNT_DISTINCT", "MIN", "MAX")) {
            ValidatedQueryPlan plan = planWithAggregation(aggregation);
            AiNormalizedResult result = normalizer.normalize(
                    plan,
                    List.of(
                            item("C001", "12.50"),
                            item("C001", "7.00"),
                            item("C001", "12.50"),
                            "{\"customer_id\":\"C001\",\"amount\":null}"),
                    "COMPLETE",
                    null,
                    1);

            Object value = result.rows().get(0).get("net_amount");
            BigDecimal actual = (BigDecimal) value;
            BigDecimal expected =
                    switch (aggregation) {
                        case "SUM" -> new BigDecimal("32.00");
                        case "AVG" -> new BigDecimal("10.666667");
                        case "COUNT" -> new BigDecimal("3");
                        case "COUNT_DISTINCT" -> new BigDecimal("2");
                        case "MIN" -> new BigDecimal("7.00");
                        default -> new BigDecimal("12.50");
                    };
            assertThat(actual).as("聚合 %s 的结论", aggregation).isEqualByComparingTo(expected);
        }
    }

    @Test
    void rowsAreOrderedDeterministicallyByDimensions() {
        AiNormalizedResult result = normalizer.normalize(
                plan(), List.of(item("C002", "1.00"), item("C001", "2.00"), item("C003", "3.00")), "COMPLETE", null, 1);

        assertThat(result.rows().stream().map(row -> row.get("customer_name")).toList())
                .as("按维度升序，分页与快照比对可复现")
                .containsExactly("C001", "C002", "C003");
    }

    private ValidatedQueryPlan planWithAggregation(String aggregation) {
        String definition = AiQueryPlanFixture.DEFINITION.replace(
                "\"aggregation\": \"SUM\"", "\"aggregation\": \"" + aggregation + "\"");
        return validator.validate(
                AiQueryPlanFixture.VALID_PLAN,
                new com.basicframework.module.ai.domain.query.ResolvedDatasetVersion(
                        AiQueryPlanFixture.DATASET_ID,
                        "it-query-orders",
                        AiQueryPlanFixture.DATASET_VERSION_ID,
                        1,
                        "a".repeat(64),
                        "it_query.orders",
                        com.basicframework.module.ai.domain.semantic.AiDatasetDefinition.parse(definition),
                        List.of()),
                AiQueryPlanFixture.CLOCK.instant());
    }
}
