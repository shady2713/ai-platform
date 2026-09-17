package com.basicframework.module.ai.api.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ChartSpec v1 的 Java 映射（对应 docs/contracts/ai/chart-spec.schema.json）。
 *
 * <p>数值一致性要求（与 TS 侧 {@code chart-spec.ts} 相同）：每个系列的 data 长度必须等于 categories 长度。
 */
public record AiChartSpecDTO(String type, String title, List<String> categories, List<AiChartSeriesDTO> series) {

    private static final Set<String> SUPPORTED_TYPES = Set.of("line", "bar", "pie");

    /**
     * 协议级校验：未知图表类型、空类目、空系列、长度不一致都拒绝。
     */
    public void validate() {
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("未知图表类型：" + type);
        }
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("图表类目不能为空");
        }
        if (series == null || series.isEmpty()) {
            throw new IllegalArgumentException("图表系列不能为空");
        }
        for (AiChartSeriesDTO one : series) {
            if (one.name() == null || one.name().isBlank()) {
                throw new IllegalArgumentException("图表系列名称不能为空");
            }
            if (one.data() == null || one.data().size() != categories.size()) {
                throw new IllegalArgumentException("系列 " + one.name() + " 的数据长度必须与类目长度一致");
            }
            if (one.data().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("系列 " + one.name() + " 存在空数值");
            }
        }
    }
}
