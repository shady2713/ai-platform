package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_AGGREGATION_ORDER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CONVERSION_RULE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SOURCE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SOURCE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

/** 跨源指标口径定义的解析与规范化（Y03）。 */
class AiMetricSemanticsTest {

    @Test
    void parsesSixCaliberFacetsAndKeepsSourceOrder() {
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());

        assertThat(semantics.metricCode()).isEqualTo("net_revenue");
        assertThat(semantics.unit()).isEqualTo("CURRENCY");
        assertThat(semantics.currency()).isEqualTo("CNY");
        assertThat(semantics.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(semantics.timeWindow()).isEqualTo("CALENDAR_MONTH");
        assertThat(semantics.sources()).hasSize(3);
        assertThat(semantics.aggregationOrder()).containsExactly("order", "invoice", "payment");
        assertThat(semantics.sourceByRole("payment").grainText()).isEqualTo("payment_id");
        assertThat(semantics.sourceByRole("invoice").optional()).isTrue();
        assertThat(semantics.sourceByRole("order").optional()).isFalse();
        assertThat(semantics.requiresCurrency()).isTrue();
    }

    @Test
    void definitionHashIsStableAndContentSensitive() {
        AiMetricSemantics base = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());
        // 同一份内容重复解析 → 同一指纹（版本可核验的前提）
        assertThat(AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources())
                        .definitionHash())
                .isEqualTo(base.definitionHash());
        // 真正改变内容时指纹必须变化（发布后据此发现"版本被改过"）
        assertThat(AiMetricSemantics.parse(AiMetricSemanticsFixtures.caliber(
                                "USD",
                                "USD",
                                "dset_orders",
                                "dset_invoices",
                                1,
                                "[\"order\",\"invoice\",\"payment\"]",
                                "null",
                                false))
                        .definitionHash())
                .isNotEqualTo(base.definitionHash());
    }

    @Test
    void canonicalJsonRoundTripsToTheSameHash() {
        AiMetricSemantics first = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());
        AiMetricSemantics second = AiMetricSemantics.parse(first.canonicalJson());
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.definitionHash()).isEqualTo(first.definitionHash());
    }

    @Test
    void duplicateDatasetVersionInOneRevisionIsRejected() {
        // 发票来源与订单来源声明同一个数据集版本 → 唯一性冲突
        String duplicated = AiMetricSemanticsFixtures.caliber(
                "CNY", "CNY", "dset_orders", "dset_orders", 2, "[\"order\",\"invoice\",\"payment\"]", "null", false);
        assertThatThrownBy(() -> AiMetricSemantics.parse(duplicated))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SOURCE_DUPLICATE.getCode());
    }

    @Test
    void aggregationOrderMustCoverEverySourceRole() {
        String partial = AiMetricSemanticsFixtures.caliber(
                "CNY", "CNY", "dset_orders", "dset_invoices", 1, "[\"order\",\"invoice\"]", "null", false);
        assertThatThrownBy(() -> AiMetricSemantics.parse(partial))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_AGGREGATION_ORDER_CONFLICT.getCode());
    }

    @Test
    void missingCurrencyOnASourceIsNeverDefaulted() {
        String noCurrency = AiMetricSemanticsFixtures.caliber(
                "CNY", "CNY", "dset_orders", "dset_invoices", 1, "[\"order\",\"invoice\",\"payment\"]", "null", true);
        assertThatThrownBy(() -> AiMetricSemantics.parse(noCurrency))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CALIBER_MISSING_CONFLICT.getCode());
    }

    @Test
    void conversionTargetCurrencyMustMatchTheCaliberCurrency() {
        String mismatched = AiMetricSemanticsFixtures.caliber(
                "CNY",
                "CNY",
                "dset_orders",
                "dset_invoices",
                1,
                "[\"order\",\"invoice\",\"payment\"]",
                "{\"rule\":\"fx_monthly\",\"targetCurrency\":\"USD\"}",
                false);
        assertThatThrownBy(() -> AiMetricSemantics.parse(mismatched))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CONVERSION_RULE_CONFLICT.getCode());
    }

    @Test
    void unknownKeysAndUnknownEnumsAreRejected() {
        String extraKey = AiMetricSemanticsFixtures.threeSources()
                .replace("\"timeWindow\":\"CALENDAR_MONTH\"", "\"timeWindow\":\"CALENDAR_MONTH\",\"extra\":1");
        assertThatThrownBy(() -> AiMetricSemantics.parse(extraKey))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SOURCE_INVALID.getCode());

        String badWindow = AiMetricSemanticsFixtures.threeSources().replace("CALENDAR_MONTH", "FISCAL_QUARTER");
        assertThatThrownBy(() -> AiMetricSemantics.parse(badWindow))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SOURCE_INVALID.getCode());

        // 小写币种码按 ISO 4217 归一为大写（"cny" 与 "CNY" 是同一个币种，不是两个）
        assertThat(AiMetricSemantics.parse(
                                AiMetricSemanticsFixtures.threeSources().replace("\"CNY\"", "\"cny\""))
                        .currency())
                .isEqualTo("CNY");
        // 真正不合法的币种码（非三字母）一律拒绝
        String badCurrency = AiMetricSemanticsFixtures.threeSources().replace("\"CNY\"", "\"YUAN\"");
        assertThatThrownBy(() -> AiMetricSemantics.parse(badCurrency))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SOURCE_INVALID.getCode());
    }

    @Test
    void emptyOrMalformedDefinitionsAreRejected() {
        assertThatThrownBy(() -> AiMetricSemantics.parse(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiMetricSemantics.parse("[]")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiMetricSemantics.parse("not-json")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> AiMetricSemantics.parse(
                        AiMetricSemanticsFixtures.threeSources().replace("\"sources\":[", "\"sources\":[],")))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void sourceLookupIsExactAndNeverFallsBackToAnotherVersion() {
        AiMetricSemantics semantics = AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources());
        assertThat(semantics.sourceOf("dset_orders", 2)).isNotNull();
        // 版本不匹配必须返回 null（"就近取一个版本"就是口径漂移的起点）
        assertThat(semantics.sourceOf("dset_orders", 9)).isNull();
        assertThat(semantics.sourceOf("dset_unknown", 1)).isNull();
        assertThat(semantics.sourceByRole("unknown")).isNull();
    }

    @Test
    void errorCodeConstantsAreStable() {
        // 断言用常量而不是裸数字：编号是长期协议，改名/改号必须先改这里
        assertThat(AI_METRIC_SOURCE_INVALID.getCode()).isEqualTo(1_003_016_008);
        assertThat(AI_METRIC_SOURCE_DUPLICATE.getCode()).isEqualTo(1_003_016_009);
        assertThat(exception(AI_METRIC_SOURCE_INVALID).getCode()).isEqualTo(1_003_016_008);
    }
}
