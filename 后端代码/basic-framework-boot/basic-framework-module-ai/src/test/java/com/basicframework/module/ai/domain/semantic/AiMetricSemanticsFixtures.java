package com.basicframework.module.ai.domain.semantic;

/**
 * 跨源口径测试夹具（Y03）。
 *
 * <p>夹具用单行 JSON 拼装而不是多行文本块：多行文本块的缩进会被格式化工具重排，
 * 导致基于 {@code replace} 的负向夹具悄悄变成"什么都没替换"——这类夹具失败过一次，
 * 而且失败方式是断言不到本该触发的错误码，看起来像"生产代码漏判"。
 *
 * <p>抽成独立类而不是挂在某个 {@code *Test} 上：校验器测试在另一个包，需要共用同一份口径，
 * 两边各写一套期望值就失去了"同一口径在所有路径下结论相同"这个断言的意义。
 */
public final class AiMetricSemanticsFixtures {

    private AiMetricSemanticsFixtures() {}

    /**
     * 拼装一份跨源口径定义。
     *
     * @param currency          口径币种
     * @param paymentCurrency   回款来源币种
     * @param orderDataset      订单来源数据集标识
     * @param invoiceDataset    发票来源数据集标识（与订单相同即制造重复声明）
     * @param invoiceVersion    发票来源数据集版本
     * @param orderAggregation  聚合顺序 JSON
     * @param conversion        换算规则 JSON（{@code null} 表示无换算规则）
     * @param omitPaymentCurrency 是否省略回款来源的币种（验证"缺失不被默认补全"）
     */
    public static String caliber(
            String currency,
            String paymentCurrency,
            String orderDataset,
            String invoiceDataset,
            int invoiceVersion,
            String orderAggregation,
            String conversion,
            boolean omitPaymentCurrency) {
        String paymentCurrencyField = omitPaymentCurrency ? "" : "\"currency\":\"" + paymentCurrency + "\",";
        return "{\"metricCode\":\"net_revenue\",\"unit\":\"CURRENCY\",\"currency\":\"" + currency
                + "\",\"timezone\":\"Asia/Shanghai\",\"timeWindow\":\"CALENDAR_MONTH\",\"sources\":["
                + "{\"role\":\"order\",\"datasetCode\":\"" + orderDataset
                + "\",\"datasetVersion\":2,\"mappingRevision\":1,\"unit\":\"CURRENCY\",\"currency\":\"CNY\","
                + "\"timezone\":\"Asia/Shanghai\",\"primaryKey\":[\"order_id\"],\"optional\":false},"
                + "{\"role\":\"invoice\",\"datasetCode\":\"" + invoiceDataset + "\",\"datasetVersion\":"
                + invoiceVersion
                + ",\"mappingRevision\":1,\"unit\":\"CURRENCY\",\"currency\":\"CNY\","
                + "\"timezone\":\"Asia/Shanghai\",\"primaryKey\":[\"invoice_id\"],\"optional\":true},"
                + "{\"role\":\"payment\",\"datasetCode\":\"dset_payments\",\"datasetVersion\":3,\"mappingRevision\":2,"
                + "\"unit\":\"CURRENCY\"," + paymentCurrencyField
                + "\"timezone\":\"Asia/Shanghai\",\"primaryKey\":[\"payment_id\"],\"optional\":true}"
                + "],\"aggregationOrder\":" + orderAggregation + ",\"conversion\":" + conversion + "}";
    }

    /** 三来源（订单/发票/回款）同币种口径：Y03 的基准可用形态。 */
    public static String threeSources() {
        return caliber(
                "CNY", "CNY", "dset_orders", "dset_invoices", 1, "[\"order\",\"invoice\",\"payment\"]", "null", false);
    }

    /** 三来源，但回款是另一种币种且无换算规则：AT-034"不能求和"的基准形态。 */
    public static String mixedCurrencies() {
        return caliber(
                "CNY", "USD", "dset_orders", "dset_invoices", 1, "[\"order\",\"invoice\",\"payment\"]", "null", false);
    }

    /** 把回款来源的时区改成 UTC：AT-034"口径冲突不能猜"的基准形态。 */
    public static String paymentInUtc() {
        return threeSources()
                .replace(
                        "\"timezone\":\"Asia/Shanghai\",\"primaryKey\":[\"payment_id\"]",
                        "\"timezone\":\"UTC\",\"primaryKey\":[\"payment_id\"]");
    }
}
