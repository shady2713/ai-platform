package com.basicframework.module.ai.domain.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D05 查询计划校验器：结构（SQL 片段/未知键/越界）、授权与版本、字段与类型、
 * 时间（固定时钟）、参数（枚举/类型/排序/limit）与别名歧义转澄清。
 */
class AiQueryPlanValidatorTest {

    private static final Clock CLOCK = AiQueryPlanFixture.CLOCK;

    private static final String VALID_PLAN = AiQueryPlanFixture.VALID_PLAN;

    private final AiQueryPlanValidator validator = new AiQueryPlanValidator();

    static ResolvedDatasetVersion dataset() {
        return AiQueryPlanFixture.dataset();
    }

    static ResolvedDatasetVersion dataset(List<String> allowedFieldCodes) {
        return AiQueryPlanFixture.dataset(allowedFieldCodes);
    }

    private ValidatedQueryPlan validate(String planJson) {
        return validator.validate(planJson, dataset(), CLOCK.instant());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void acceptsValidPlanAndResolvesPhysicalMappings() {
        ValidatedQueryPlan plan = validate(VALID_PLAN);

        assertThat(plan.planDatasetId()).isEqualTo("dset_it-query-orders");
        assertThat(plan.datasetVersionNo()).isEqualTo(1);
        assertThat(plan.schemaHash()).isEqualTo("a".repeat(64));
        assertThat(plan.metrics())
                .containsExactly(new ValidatedQueryPlan.Metric("net_amount", "amount", "SUM", "CURRENCY"));
        assertThat(plan.dimensions()).containsExactly(new ValidatedQueryPlan.Dimension("customer_name", "customer_id"));
        assertThat(plan.filters())
                .containsExactly(new ValidatedQueryPlan.Filter("region", "region", "STRING", "EQ", List.of("EAST")));
        assertThat(plan.timeWindow())
                .isEqualTo(new ValidatedQueryPlan.TimeWindow(
                        "created_at",
                        "created_at",
                        "2026-08-01T00:00+08:00",
                        "2026-09-01T00:00+08:00",
                        "Asia/Shanghai",
                        "DAY"));
        assertThat(plan.orderBy()).containsExactly(new ValidatedQueryPlan.Order("net_amount", "METRIC", "DESC"));
        assertThat(plan.limit()).isEqualTo(10);
        assertThat(plan.planHash()).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(validate(VALID_PLAN).planHash()).as("同输入同哈希").isEqualTo(plan.planHash());
    }

    @Test
    void rejectsSqlFragmentsAndUnknownKeys() {
        for (String plan : List.of(
                VALID_PLAN.replace("\"net_amount\"", "\"SUM(amount)\""),
                VALID_PLAN.replace("\"region\"", "\"region'; DROP TABLE orders\""),
                VALID_PLAN.replace("{\"field\": \"region\"", "{\"field\": \"region /* x */\""),
                VALID_PLAN.replace("\"EAST\"", "\"EAST\" /* comment */"),
                VALID_PLAN.replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\", \"sql\": \"select 1\"}"))) {
            assertThatThrownBy(() -> validate(plan))
                    .as("SQL 片段必须被拒绝：%s", plan.substring(0, Math.min(60, plan.length())))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SQL_REJECTED));
        }
    }

    @Test
    void rejectsOtherDatasetAndVersion() {
        assertThatThrownBy(() -> validate(VALID_PLAN.replace("dset_it-query-orders", "dset_other-dataset")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED));
        assertThatThrownBy(() -> validate(VALID_PLAN.replace("\"datasetVersion\": 1", "\"datasetVersion\": 2")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        assertThatThrownBy(
                        () -> validate(VALID_PLAN.replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        assertThatThrownBy(() -> validate("{\"datasetId\": \"dset_it-query-orders\"}"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        assertThatThrownBy(() -> validate("not-json"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
    }

    @Test
    void unknownCodesAreRejectedAndAliasHitsAskForClarification() {
        // 未知指标码（没有对应别名）：非法字段
        assertThatThrownBy(() -> validate(
                        VALID_PLAN.replace("\"metrics\": [\"net_amount\"]", "\"metrics\": [\"gross_margin\"]")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 命中别名（"销售额"是 amount 的别名）：措辞歧义 → 澄清
        assertThatThrownBy(
                        () -> validate(VALID_PLAN.replace("\"metrics\": [\"net_amount\"]", "\"metrics\": [\"销售额\"]")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_CLARIFICATION_REQUIRED));
        // 过滤字段命中别名 → 澄清
        assertThatThrownBy(() -> validate(VALID_PLAN.replace("\"field\": \"region\"", "\"field\": \"区域\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_CLARIFICATION_REQUIRED));
        // 未选中的字段不能排序（可能泄漏未选中的列）
        assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                        "\"field\": \"net_amount\", \"direction\"", "\"field\": \"order_id\", \"direction\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
    }

    @Test
    void validatesFilterValuesAgainstFieldTypesAndEnums() {
        // 数值字段收到字符串
        assertThatThrownBy(() -> validate(VALID_PLAN
                        .replace("\"value\": \"EAST\"", "\"value\": \"EAST\"")
                        .replace(
                                "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                                "{\"field\": \"amount\", \"operator\": \"EQ\", \"value\": \"12.5\"}")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 枚举字段取未声明值
        assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"status\", \"operator\": \"EQ\", \"value\": \"UNKNOWN\"}")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 枚举字段取声明值：通过
        assertThatCode(() -> validate(VALID_PLAN.replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"status\", \"operator\": \"IN\", \"values\": [\"PAID\", \"REFUNDED\"]}")))
                .doesNotThrowAnyException();
        // IN 空集合 / 超限 / 重复
        for (String invalid : List.of(
                "{\"field\": \"status\", \"operator\": \"IN\", \"values\": []}",
                "{\"field\": \"status\", \"operator\": \"IN\", \"values\": [\"PAID\", \"PAID\"]}",
                "{\"field\": \"status\", \"operator\": \"IN\", \"values\": ["
                        + "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,"
                        + "36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,"
                        + "68,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100,101]}")) {
            assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                            "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}", invalid)))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        }
        // BETWEEN 倒序 / IS_NULL 带值 / 非法操作符
        for (String invalid : List.of(
                "{\"field\": \"amount\", \"operator\": \"BETWEEN\", \"lower\": 20, \"upper\": 10}",
                "{\"field\": \"region\", \"operator\": \"IS_NULL\", \"value\": \"x\"}",
                "{\"field\": \"region\", \"operator\": \"LIKE\", \"value\": \"E%\"}")) {
            assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                            "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}", invalid)))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        }
        // BETWEEN 正常
        assertThatCode(() -> validate(VALID_PLAN.replace(
                        "{\"field\": \"region\", \"operator\": \"EQ\", \"value\": \"EAST\"}",
                        "{\"field\": \"amount\", \"operator\": \"BETWEEN\", \"lower\": 10, \"upper\": 20}")))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesTimeWindowAgainstFixedClock() {
        // 倒序区间
        assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                        "\"startInclusive\": \"2026-08-01T00:00:00+08:00\"",
                        "\"startInclusive\": \"2026-09-01T00:00:00+08:00\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 过宽（>366 天）
        assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                        "\"startInclusive\": \"2026-08-01T00:00:00+08:00\"",
                        "\"startInclusive\": \"2024-01-01T00:00:00+08:00\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 时区与数据集声明不一致：口径不同 → 澄清
        assertThatThrownBy(
                        () -> validate(VALID_PLAN.replace("\"timezone\": \"Asia/Shanghai\"", "\"timezone\": \"UTC\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_CLARIFICATION_REQUIRED));
        // 缺少偏移量的时间戳
        assertThatThrownBy(
                        () -> validate(VALID_PLAN.replace("\"2026-08-01T00:00:00+08:00\"", "\"2026-08-01T00:00:00\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 时间字段不是日期/时间类型
        assertThatThrownBy(() -> validate(VALID_PLAN.replace(
                        "\"field\": \"created_at\", \"startInclusive\"", "\"field\": \"region\", \"startInclusive\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        // 固定时钟之外的窗口（远未来）
        assertThatThrownBy(() ->
                        validate(VALID_PLAN.replace("\"2026-09-01T00:00:00+08:00\"", "\"2030-09-01T00:00:00+08:00\"")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
    }

    @Test
    void validatesLimitsAndStructure() {
        for (String invalid : List.of(
                VALID_PLAN.replace("\"limit\": 10", "\"limit\": 0"),
                VALID_PLAN.replace("\"limit\": 10", "\"limit\": 1001"),
                VALID_PLAN.replace("\"limit\": 10", "\"limit\": \"10\""),
                VALID_PLAN.replace("\"metrics\": [\"net_amount\"]", "\"metrics\": []"),
                VALID_PLAN.replace("\"metrics\": [\"net_amount\"]", "\"metrics\": [\"net_amount\", \"net_amount\"]"),
                VALID_PLAN.replace("\"dimensions\": [\"customer_name\"]", "\"dimensions\": [\"Customer_Name\"]"))) {
            assertThatThrownBy(() -> validate(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
        }
        // 指标依赖的字段不在授权范围内 → 该指标不可用（数据集范围错误）
        ResolvedDatasetVersion metricRestricted = dataset(List.of("net_amount", "created_at"));
        assertThatThrownBy(() -> validator.validate(VALID_PLAN, metricRestricted, CLOCK.instant()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED));
        // 过滤字段不在授权范围内 → 等价于未知字段（不允许用计划探知未授权字段）
        ResolvedDatasetVersion fieldRestricted =
                dataset(List.of("net_amount", "amount", "created_at", "customer_name", "customer"));
        assertThatThrownBy(() -> validator.validate(VALID_PLAN, fieldRestricted, CLOCK.instant()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_PLAN_INVALID));
    }

    @Test
    void planHashChangesWithContent() {
        String base = validate(VALID_PLAN).planHash();
        String changed =
                validate(VALID_PLAN.replace("\"limit\": 10", "\"limit\": 20")).planHash();
        String otherTime = validate(
                        VALID_PLAN.replace("\"2026-09-01T00:00:00+08:00\"", "\"2026-08-15T00:00:00+08:00\""))
                .planHash();

        assertThat(base).isNotEqualTo(changed).isNotEqualTo(otherTime);
        assertThat(AiQueryPlanValidator.normalizeCode("  NET_AMOUNT ")).isEqualTo("net_amount");
        assertThat(AiQueryPlanValidator.normalizeCode(null)).isNull();
    }
}
