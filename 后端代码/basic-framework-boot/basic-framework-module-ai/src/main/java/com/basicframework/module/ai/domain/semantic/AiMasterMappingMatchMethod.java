package com.basicframework.module.ai.domain.semantic;

import java.util.Locale;
import java.util.Optional;

/**
 * 源键映射的**匹配方式**（Y02）：映射是怎么被建立的。
 *
 * <p>只有两种方式，且两种都要求登记方给出**来源系统的业务主键**：
 * <ul>
 *   <li>{@link #MANUAL} 人工登记：有权操作员在管理端逐条登记（默认路径，也适用于一次性迁移）；</li>
 *   <li>{@link #TRUSTED_FEED} 可信主数据接口导入：来源系统的权威主数据接口给出对应关系，
 *       平台仍按源键落库（**不**按名称相似度自动合并，接口给不出源键就不登记）。</li>
 * </ul>
 *
 * <p>词表封闭：未知匹配方式一律拒绝（{@link Optional#empty()}），不做"默认人工"的兜底——
 * 兜底会让"这条映射是怎么来的"失去可核验性。
 */
public enum AiMasterMappingMatchMethod {

    /** 人工登记。 */
    MANUAL,

    /** 可信主数据接口导入。 */
    TRUSTED_FEED;

    /** 解析匹配方式；未知（含 null、空串）返回空，调用方按拒绝处理。 */
    public static Optional<AiMasterMappingMatchMethod> parse(String value) {
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
