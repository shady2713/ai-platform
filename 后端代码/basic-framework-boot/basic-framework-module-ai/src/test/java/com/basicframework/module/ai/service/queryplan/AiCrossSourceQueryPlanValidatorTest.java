package com.basicframework.module.ai.service.queryplan;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_AGGREGATION_ORDER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_FANOUT_UNSAFE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_GAP_CLARIFICATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_PLAN_SELECTION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_PLAN_SOURCE_NOT_DECLARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.domain.semantic.AiMetricSemanticsFixtures;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 跨源查询计划校验（Y03）：显式选择、扇出阻断、币种与缺口。
 *
 * <p>三条 AT-034 专项在这里以"计划 JSON → 稳定错误码"的形式钉住；
 * 真实 MySQL 路径由 {@code AiCrossSourceMetricAcceptanceIT} 覆盖。
 */
class AiCrossSourceQueryPlanValidatorTest {

    private final AiCrossSourceQueryPlanValidator validator = new AiCrossSourceQueryPlanValidator();

    private static AiMetricSemantics caliber(String paymentCurrency) {
        return AiMetricSemantics.parse(AiMetricSemanticsFixtures.caliber(
                "CNY",
                paymentCurrency,
                "dset_orders",
                "dset_invoices",
                1,
                "[\"order\",\"invoice\",\"payment\"]",
                "null",
                false));
    }

    /** AT-034 之二的基准口径：回款是 USD 且没有换算规则。 */
    private static AiMetricSemantics mixedCurrencies() {
        return AiMetricSemantics.parse(AiMetricSemanticsFixtures.mixedCurrencies());
    }

    /** AT-034 之三的基准口径：回款来源时区是 UTC，与口径声明的 Asia/Shanghai 冲突。 */
    private static AiMetricSemantics paymentInUtc() {
        return AiMetricSemantics.parse(AiMetricSemanticsFixtures.paymentInUtc());
    }

    /** 三个来源都显式选择、按各自主键粒度预聚合、按 order→invoice→payment 关联。 */
    private static String plan(String paymentPreAggregated, String paymentGrain, String orderAggregation) {
        return "{\"semanticsRevision\":1,\"sources\":["
                + "{\"role\":\"order\",\"datasetCode\":\"dset_orders\",\"datasetVersion\":2,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"order_id\"],\"preAggregated\":true},"
                + "{\"role\":\"invoice\",\"datasetCode\":\"dset_invoices\",\"datasetVersion\":1,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"invoice_id\"],\"preAggregated\":true},"
                + "{\"role\":\"payment\",\"datasetCode\":\"dset_payments\",\"datasetVersion\":3,\"mappingRevision\":2,"
                + "\"primaryKey\":[" + paymentGrain + "],\"preAggregated\":" + paymentPreAggregated + "}"
                + "],\"aggregationOrder\":" + orderAggregation + "}";
    }

    private static String validPlan() {
        return plan("true", "\"payment_id\"", "[\"order\",\"invoice\",\"payment\"]");
    }

    @Test
    void aFullyPinnedPlanIsAcceptedAndCarriesTheCaliberVersionAndHash() {
        CrossSourceQueryPlan plan = validator.validate(validPlan(), caliber("CNY"), Set.of());

        assertThat(plan.metricCode()).isEqualTo("net_revenue");
        assertThat(plan.semanticsRevision()).isEqualTo(1);
        assertThat(plan.semanticsDefinitionHash()).hasSize(64);
        assertThat(plan.currency()).isEqualTo("CNY");
        assertThat(plan.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(plan.timeWindow()).isEqualTo("CALENDAR_MONTH");
        assertThat(plan.aggregationOrder()).containsExactly("order", "invoice", "payment");
        assertThat(plan.sources()).hasSize(3);
        assertThat(plan.planHash()).isNotBlank();
        // 同一份计划永远得到同一个哈希
        assertThat(validator.validate(validPlan(), caliber("CNY"), Set.of()).planHash())
                .isEqualTo(plan.planHash());
    }

    @Test
    void manyToManyJoinWithoutPreAggregationIsRefused() {
        // AT-034 之一：多对多不重复计算——没声明先按主键粒度聚合就关联，同一事实会被算两次
        String unsafe = plan("false", "\"payment_id\"", "[\"order\",\"invoice\",\"payment\"]");
        assertThatThrownBy(() -> validator.validate(unsafe, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_FANOUT_UNSAFE_CONFLICT.getCode());
    }

    @Test
    void planClaimingAGrainTheCaliberNeverDeclaredIsRefused() {
        // 计划自称按 customer_id 预聚合，但口径登记的主键粒度是 payment_id：不能按计划自称放行
        String forgedGrain = plan("true", "\"customer_id\"", "[\"order\",\"invoice\",\"payment\"]");
        assertThatThrownBy(() -> validator.validate(forgedGrain, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_FANOUT_UNSAFE_CONFLICT.getCode());
    }

    @Test
    void differentCurrenciesWithoutConversionRuleAreRefusedRatherThanSummed() {
        // AT-034 之二：不同币种无换算规则不能求和（不是静默相加）
        assertThatThrownBy(() -> validator.validate(validPlan(), mixedCurrencies(), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT.getCode());
    }

    @Test
    void caliberConflictIsRefusedExplicitlyInsteadOfLettingTheModelGuess() {
        // AT-034 之三：口径冲突不能靠模型猜测——时区/单位不一致一律拒绝
        AiMetricSemantics mixedTimezone = AiMetricSemantics.parse(AiMetricSemanticsFixtures.caliber(
                        "CNY",
                        "CNY",
                        "dset_orders",
                        "dset_invoices",
                        1,
                        "[\"order\",\"invoice\",\"payment\"]",
                        "null",
                        false)
                .replace("\"primaryKey\":[\"payment_id\"]", "\"primaryKey\":[\"payment_id\"]")
                .replace(
                        "\"timezone\":\"Asia/Shanghai\",\"primaryKey\":[\"payment_id\"]",
                        "\"timezone\":\"UTC\",\"primaryKey\":[\"payment_id\"]"));
        assertThatThrownBy(() -> validator.validate(validPlan(), mixedTimezone, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CALIBER_CONFLICT.getCode());
    }

    @Test
    void everySourceMustPinBothDatasetVersionAndMappingVersion() {
        // AT-034 专项：查询计划必须显式选择数据集与映射版本
        String noMappingRevision = validPlan().replace(",\"mappingRevision\":2", "");
        assertThatThrownBy(() -> validator.validate(noMappingRevision, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());

        String noDatasetVersion = validPlan().replace("\"datasetVersion\":3", "\"datasetVersion\":0");
        assertThatThrownBy(() -> validator.validate(noDatasetVersion, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());

        String noSemanticsRevision = validPlan().replace("\"semanticsRevision\":1,", "");
        assertThatThrownBy(() -> validator.validate(noSemanticsRevision, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());
    }

    @Test
    void aMappingVersionThatContradictsTheCaliberIsRefused() {
        String wrongMappingRevision = validPlan().replace("\"mappingRevision\":2", "\"mappingRevision\":9");
        assertThatThrownBy(() -> validator.validate(wrongMappingRevision, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());
    }

    @Test
    void aSourceTheCaliberNeverDeclaredIsRefused() {
        String unknownSource = validPlan().replace("\"dset_payments\"", "\"dset_unknown\"");
        assertThatThrownBy(() -> validator.validate(unknownSource, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SOURCE_NOT_DECLARED.getCode());
    }

    @Test
    void aggregationOrderMustCoverExactlyTheSelectedSources() {
        String dropped = plan("true", "\"payment_id\"", "[\"order\",\"invoice\"]");
        assertThatThrownBy(() -> validator.validate(dropped, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_AGGREGATION_ORDER_CONFLICT.getCode());

        String duplicated = plan("true", "\"payment_id\"", "[\"order\",\"invoice\",\"payment\",\"payment\"]");
        assertThatThrownBy(() -> validator.validate(duplicated, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_AGGREGATION_ORDER_CONFLICT.getCode());
    }

    @Test
    void gapsInNonOptionalSourcesRequireClarificationInsteadOfSilentZero() {
        // 逐步实施 3：缺口有澄清与完整性策略——order 是必需来源，缺它必须追问
        assertThatThrownBy(() -> validator.validate(validPlan(), caliber("CNY"), Set.of("order")))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_GAP_CLARIFICATION_REQUIRED.getCode());

        // invoice/payment 在口径里标了 optional，缺它们可以按缺省继续
        assertThat(validator
                        .validate(validPlan(), caliber("CNY"), Set.of("invoice", "payment"))
                        .sources())
                .hasSize(3);
        // 缺口里出现本次没选的来源同样按澄清处理，而不是忽略
        assertThatThrownBy(() -> validator.validate(validPlan(), caliber("CNY"), Set.of("refund")))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_GAP_CLARIFICATION_REQUIRED.getCode());
    }

    @Test
    void malformedPlansAndNullCaliberAreRefused() {
        assertThatThrownBy(() -> validator.validate(null, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> validator.validate("not-json", caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> validator.validate("[]", caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> validator.validate(validPlan(), null, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CALIBER_MISSING_CONFLICT.getCode());
        // 空缺口集合与 null 缺口集合都表示"无缺口"
        assertThat(validator.validate(validPlan(), caliber("CNY"), null).sources())
                .hasSize(3);
        assertThat(validator.validate(validPlan(), caliber("CNY"), Set.of()).sources())
                .hasSize(3);
    }

    @Test
    void unknownTopLevelOrSourceKeysAreRefused() {
        String extraTopLevel =
                validPlan().replace("\"semanticsRevision\":1,", "\"semanticsRevision\":1,\"joinHint\":\"x\",");
        assertThatThrownBy(() -> validator.validate(extraTopLevel, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());

        String extraSourceKey = validPlan().replace("\"preAggregated\":true}", "\"preAggregated\":true,\"hint\":1}");
        assertThatThrownBy(() -> validator.validate(extraSourceKey, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());
    }

    @Test
    void theSameDatasetVersionCannotBeSelectedTwiceInOnePlan() {
        // 回款来源改指向订单已选的数据集版本 → 同一 (数据集, 版本) 选了两次
        String duplicatedSource = validPlan()
                .replace(
                        "{\"role\":\"payment\",\"datasetCode\":\"dset_payments\",\"datasetVersion\":3",
                        "{\"role\":\"payment\",\"datasetCode\":\"dset_orders\",\"datasetVersion\":2");
        assertThatThrownBy(() -> validator.validate(duplicatedSource, caliber("CNY"), Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());
    }
}
