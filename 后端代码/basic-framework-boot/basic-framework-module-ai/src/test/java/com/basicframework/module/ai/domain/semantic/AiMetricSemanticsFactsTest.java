package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 跨源指标口径的判定算法（Y03）：币种、单位/时区、扇出与缺口。
 *
 * <p>这一层是纯函数，因此负向分支可以在这里穷举，而不必为每种错误码都起一个数据库用例。
 */
class AiMetricSemanticsFactsTest {

    private static AiMetricSemantics.Source source(
            String currency, String unit, String timezone, boolean optional, String... grain) {
        return new AiMetricSemantics.Source(
                "order", "dset_orders", 1, 1L, unit, currency, timezone, List.of(grain), optional);
    }

    @Test
    void sameCurrencySourcesAreSummableWithoutAnyConversionRule() {
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());
        List<AiMetricSemantics.Source> sources = semantics.sources();

        assertThat(AiMetricSemanticsFacts.distinctCurrencies(sources)).containsExactly("CNY");
        assertThat(AiMetricSemanticsFacts.summable(semantics, sources)).isTrue();
        assertThatCode(() -> AiMetricSemanticsFacts.requireSummable(semantics, sources))
                .doesNotThrowAnyException();
    }

    @Test
    void differentCurrenciesWithoutConversionRuleAreRefusedNotSilentlyAdded() {
        // AT-034：不同币种无换算规则不能求和
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.caliber(
                "CNY", "USD", "dset_orders", "dset_invoices", 1, "[\"order\",\"invoice\",\"payment\"]", "null", false));
        List<AiMetricSemantics.Source> sources = semantics.sources();

        assertThat(AiMetricSemanticsFacts.distinctCurrencies(sources)).containsExactly("CNY", "USD");
        assertThat(AiMetricSemanticsFacts.summable(semantics, sources)).isFalse();
        assertThatThrownBy(() -> AiMetricSemanticsFacts.requireSummable(semantics, sources))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT.getCode());
    }

    @Test
    void declaringAConversionRuleWhoseTargetIsTheCaliberCurrencyMakesTheSetSummable() {
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.caliber(
                "CNY",
                "USD",
                "dset_orders",
                "dset_invoices",
                1,
                "[\"order\",\"invoice\",\"payment\"]",
                "{\"rule\":\"fx_monthly\",\"targetCurrency\":\"CNY\"}",
                false));
        // 换算规则已声明：交由换算环节处理，本层不再以"多币种"阻断
        assertThat(semantics.conversion().targetCurrency()).isEqualTo("CNY");
        assertThatCode(() -> AiMetricSemanticsFacts.requireConsistentCaliber(semantics, semantics.sources()))
                .doesNotThrowAnyException();
    }

    @Test
    void unitOrTimezoneMismatchIsRefusedInsteadOfPickingAWinner() {
        // AT-034：口径冲突不能靠模型猜测——没有"挑主来源时区"的兜底
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());
        List<AiMetricSemantics.Source> mixed = List.of(
                source("CNY", "CURRENCY", "Asia/Shanghai", false, "order_id"),
                source("CNY", "PERCENT", "Asia/Shanghai", false, "ratio_id"),
                source("CNY", "CURRENCY", "UTC", false, "payment_id"));

        assertThatThrownBy(() -> AiMetricSemanticsFacts.requireConsistentCaliber(semantics, mixed))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CALIBER_CONFLICT.getCode());
    }

    @Test
    void nullOrEmptyInputsAreTreatedAsUndecidableRatherThanAsPassing() {
        assertThat(AiMetricSemanticsFacts.summable(null, List.of())).isFalse();
        assertThat(AiMetricSemanticsFacts.complete(null)).isFalse();
        assertThat(AiMetricSemanticsFacts.complete(List.of())).isFalse();
        assertThat(AiMetricSemanticsFacts.distinctCurrencies(null)).isEmpty();
        assertThat(AiMetricSemanticsFacts.describeGaps(null)).isEmpty();
        assertThat(AiMetricSemanticsFacts.fanoutSafe(null, null, true)).isFalse();
        assertThat(AiMetricSemanticsFacts.gapTolerable(null)).isFalse();
        assertThat(AiMetricSemanticsFacts.digest(null)).isEmpty();
        assertThatThrownBy(() -> AiMetricSemanticsFacts.requireSummable(null, List.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiMetricSemanticsFacts.requireConsistentCaliber(null, List.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void missingGrainMakesTheJoinUnsafeEvenWhenPreAggregatedIsClaimed() {
        AiMetricSemantics.Source noGrain = source("CNY", "CURRENCY", "Asia/Shanghai", false);
        AiMetricSemantics.Source withGrain = source("CNY", "CURRENCY", "Asia/Shanghai", false, "order_id");

        assertThat(AiMetricSemanticsFacts.fanoutSafe(noGrain, withGrain, true)).isFalse();
        assertThat(AiMetricSemanticsFacts.complete(List.of(noGrain))).isFalse();
        assertThat(AiMetricSemanticsFacts.complete(List.of(withGrain))).isTrue();
    }

    @Test
    void fanoutIsUnsafeUnlessEverySideIsPreAggregatedOnItsOwnGrain() {
        AiMetricSemantics.Source left = source("CNY", "CURRENCY", "Asia/Shanghai", false, "order_id");
        AiMetricSemantics.Source right = source("CNY", "CURRENCY", "Asia/Shanghai", false, "payment_id");

        // AT-034：多对多不重复计算——未预聚合就关联会放大行数
        assertThat(AiMetricSemanticsFacts.fanoutSafe(left, right, false)).isFalse();
        assertThat(AiMetricSemanticsFacts.fanoutSafe(left, right, true)).isTrue();
    }

    @Test
    void onlySourcesExplicitlyMarkedOptionalTolerateGaps() {
        AiMetricSemantics.Source required = source("CNY", "CURRENCY", "Asia/Shanghai", false, "order_id");
        AiMetricSemantics.Source optional = source("CNY", "CURRENCY", "Asia/Shanghai", true, "payment_id");

        assertThat(AiMetricSemanticsFacts.gapTolerable(optional)).isTrue();
        assertThat(AiMetricSemanticsFacts.gapTolerable(required)).isFalse();
        assertThat(AiMetricSemanticsFacts.describeGaps(List.of(optional, required)))
                .isEqualTo("order");
    }

    @Test
    void digestIsTheFrozenFingerprintOfTheCaliberDefinition() {
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());
        assertThat(AiMetricSemanticsFacts.digest(semantics)).isEqualTo(semantics.definitionHash());
        assertThat(AiMetricSemanticsFacts.digest(semantics)).hasSize(64);
    }
}
