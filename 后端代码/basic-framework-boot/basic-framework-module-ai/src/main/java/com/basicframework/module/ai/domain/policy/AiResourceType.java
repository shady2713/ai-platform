package com.basicframework.module.ai.domain.policy;

import java.util.Locale;
import java.util.Optional;

/** 资源类型（A03）：不同类型之间即使 resource_key 相同也互不串权。 */
public enum AiResourceType {

    /** 报表。 */
    REPORT,

    /** 知识库。 */
    KNOWLEDGE_BASE,

    /** 文件。 */
    FILE,

    /** 工具。 */
    TOOL,

    /** 数据集。 */
    DATASET;

    /** 解析资源类型；未知类型返回空（调用方按拒绝处理）。 */
    public static Optional<AiResourceType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
