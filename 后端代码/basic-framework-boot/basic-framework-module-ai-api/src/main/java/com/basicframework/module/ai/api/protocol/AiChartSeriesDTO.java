package com.basicframework.module.ai.api.protocol;

import java.math.BigDecimal;
import java.util.List;

/**
 * ChartSpec v1 的 Java 映射（对应 docs/contracts/ai/chart-spec.schema.json）。
 *
 * <p>内部用 {@link BigDecimal} 保存数值：金额以十进制字符串传输，反序列化后精度与标度不变；
 * 写出协议时使用 {@link BigDecimal#toPlainString()} 保留原精度，不做浮点转换。
 */
public record AiChartSeriesDTO(String name, List<BigDecimal> data) {

    /** 协议写出形式：金额必须是十进制字符串，避免 JSON number 的浮点转换。 */
    public List<String> toProtocolData() {
        return data.stream().map(BigDecimal::toPlainString).toList();
    }
}
