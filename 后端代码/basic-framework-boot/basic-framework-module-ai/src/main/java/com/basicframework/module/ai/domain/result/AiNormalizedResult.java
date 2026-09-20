package com.basicframework.module.ai.domain.result;

import java.util.List;
import java.util.Map;

/**
 * 归一化查询结果（D07）：结果 Schema + 行 + **完整性结论**。
 *
 * <p>完整性是结果的一部分而不是附注：只有上游分页**确认取完**、且没有触达任何上限（页数/条数/耗时/字节）
 * 时，统计才是"完整统计"（{@link #completeStatistics()}）。任何截断或失败都必须如实标注，
 * 调用方（模型/报表）不能把 PARTIAL 当成全量。
 *
 * <p>行值只允许：{@code String}、{@code java.math.BigDecimal}（金额等十进制，不用 double）、
 * {@code Long}、{@code Boolean}、{@code null}。金额一律十进制字符串语义，避免二进制浮点误差。
 * {@code toString()} 不输出行数据。
 */
public record AiNormalizedResult(
        AiResultSchema schema,
        List<Map<String, Object>> rows,
        String completeness,
        String reason,
        int pages,
        int sourceItems,
        String sourceStatus) {

    /** 完整性：上游确认取完且未触达任何上限。 */
    public static final String COMPLETE = "COMPLETE";

    /** 完整性：有数据但未取完（截断）。 */
    public static final String PARTIAL = "PARTIAL";

    /** 完整性：上游失败，结果不可用于统计。 */
    public static final String FAILED = "FAILED";

    public AiNormalizedResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** 是否可宣称"完整统计"（只有 COMPLETE 才行）。 */
    public boolean completeStatistics() {
        return COMPLETE.equals(completeness);
    }

    @Override
    public String toString() {
        return "AiNormalizedResult[columns="
                + (schema == null ? 0 : schema.columns().size()) + ", rows="
                + rows.size() + ", completeness=" + completeness + ", reason=" + reason + ", pages=" + pages
                + ", sourceItems=" + sourceItems + ", sourceStatus=" + sourceStatus + "]";
    }
}
