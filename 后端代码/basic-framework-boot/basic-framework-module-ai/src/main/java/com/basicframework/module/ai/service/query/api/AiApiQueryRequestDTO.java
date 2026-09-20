package com.basicframework.module.ai.service.query.api;

import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;

/**
 * API 计划执行请求（D07）：已校验计划 + 已发布 operation + 可信行范围。
 *
 * <p>请求里**没有** header 字段：HTTP 请求头只能来自连接器配置（D02 的结构性约束），
 * 模型与调用方都无法提供或覆盖。
 */
public record AiApiQueryRequestDTO(
        Long connectorId,
        String operationKey,
        ValidatedQueryPlan plan,
        QueryScope scope,
        Integer maxItems,
        Integer maxMillis,
        Integer maxBytes) {

    /** 条目上限的硬上限（与 D02 执行器一致）。 */
    public static final int MAX_ITEMS_LIMIT = 1_000;

    /** 总耗时上限（毫秒）的硬上限。 */
    public static final int MAX_MILLIS_LIMIT = 30_000;

    /** 响应字节上限的硬上限（约 2MB）。 */
    public static final int MAX_BYTES_LIMIT = 2_000_000;

    public static final int DEFAULT_MAX_ITEMS = MAX_ITEMS_LIMIT;

    public static final int DEFAULT_MAX_MILLIS = 15_000;

    public static final int DEFAULT_MAX_BYTES = 512_000;

    /** 生效条目上限（不得超过硬上限）。 */
    public int effectiveMaxItems() {
        return maxItems == null ? DEFAULT_MAX_ITEMS : Math.max(1, Math.min(MAX_ITEMS_LIMIT, maxItems));
    }

    /** 生效耗时上限（不得超过硬上限）。 */
    public int effectiveMaxMillis() {
        return maxMillis == null ? DEFAULT_MAX_MILLIS : Math.max(1, Math.min(MAX_MILLIS_LIMIT, maxMillis));
    }

    /** 生效响应字节上限（不得超过硬上限）。 */
    public int effectiveMaxBytes() {
        return maxBytes == null ? DEFAULT_MAX_BYTES : Math.max(1, Math.min(MAX_BYTES_LIMIT, maxBytes));
    }
}
