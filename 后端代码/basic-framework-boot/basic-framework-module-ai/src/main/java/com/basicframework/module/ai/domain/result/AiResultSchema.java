package com.basicframework.module.ai.domain.result;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结果列 Schema（D07）：归一化结果对外的**唯一**列集合。
 *
 * <p>为什么结果要有自己的 Schema 而不是直接透传上游字段：上游响应通常带一堆平台不需要的字段
 * （内部 ID、审计列、甚至敏感列）。归一化的第一步就是**投影**到本 Schema：
 * 不在 Schema 里的字段在进入平台（更不用说进入模型）之前就被丢掉。
 */
public record AiResultSchema(List<Column> columns) {

    /** 结果列：逻辑码 + 展示标签 + 语义类型 + 单位（金额等）。 */
    public record Column(String code, String label, String type, String unit) {}

    public AiResultSchema {
        columns = columns == null ? List.of() : List.copyOf(columns);
        Set<String> codes = new LinkedHashSet<>();
        for (Column column : columns) {
            if (column.code() == null || column.code().isBlank() || !codes.add(column.code())) {
                throw new IllegalArgumentException("结果列码必须非空且唯一");
            }
        }
    }

    /** 全部逻辑码（投影白名单）。 */
    public Set<String> codes() {
        return columns.stream().map(Column::code).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
