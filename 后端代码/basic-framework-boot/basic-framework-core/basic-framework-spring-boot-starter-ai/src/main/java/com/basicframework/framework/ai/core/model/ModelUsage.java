package com.basicframework.framework.ai.core.model;

/**
 * 平台自有用量词表（M03 冻结）：把"上游没给"与"真实为零"分开表达。
 *
 * <p>AT-060 要求上游缺失 usage 时展示 UNKNOWN/ESTIMATED，而不是用假 0 冒充真实计量。
 * 因此两个计数字段都可空，语义如下：
 * <ul>
 *   <li>{@code null} 且 {@code estimated=false}：UNKNOWN，上游没有给出该数值；</li>
 *   <li>{@code null} 且 {@code estimated=true}：上游缺失但已知存在无法计量的部分；</li>
 *   <li>非空：{@code estimated=false} 为上游真实计量，{@code estimated=true} 为平台估算值。</li>
 * </ul>
 * 计量、配额与预算护栏以真实值优先；估算值只用于展示与护栏，不写成真实计量。
 */
public record ModelUsage(Integer promptTokens, Integer completionTokens, boolean estimated) {

    /** 上游未提供任何用量信息。 */
    public static final ModelUsage UNKNOWN = new ModelUsage(null, null, false);

    /** 平台按估算补齐的用量（上游缺失时使用）。 */
    public static ModelUsage estimated(Integer promptTokens, Integer completionTokens) {
        return new ModelUsage(promptTokens, completionTokens, true);
    }

    /** 上游给出的真实用量。 */
    public static ModelUsage of(Integer promptTokens, Integer completionTokens) {
        return new ModelUsage(promptTokens, completionTokens, false);
    }

    /** 是否至少有一个可用计数（false 表示完全未知）。 */
    public boolean isKnown() {
        return promptTokens != null || completionTokens != null;
    }

    /** 总 token 数；任一计数缺失时返回 {@code null}，不得用假 0 代替。 */
    public Integer totalTokens() {
        if (promptTokens == null || completionTokens == null) {
            return null;
        }
        return promptTokens + completionTokens;
    }
}
