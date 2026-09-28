package com.basicframework.module.ai.service.vision.dto;

import com.basicframework.framework.ai.core.model.ModelUsage;

/**
 * 媒体用量（X02 协议层）：上游没给就报 `UNKNOWN` + `quantity=null`，**不得用 0 冒充实测**（AT-060）。
 *
 * <p>与前端契约 `multimodalUsageSchema` 同字段同语义；本类只做映射，不产生估算值。
 *
 * @param source   用量来源：`REPORTED`（上游给出）/ `ESTIMATED`（平台估算）/ `UNKNOWN`（上游未给）
 * @param quantity 数量；`UNKNOWN` 时为 null
 * @param unit     计量单位（媒体文本类统一按 token 报告）
 */
public record AiVisionUsageDTO(String source, Integer quantity, String unit) {

    /** 用量单位：媒体文本调用按 token 计量（与既有文本链路一致）。 */
    public static final String UNIT_TOKEN = "TOKEN";

    /** 上游未提供用量。 */
    public static AiVisionUsageDTO unknown() {
        return new AiVisionUsageDTO("UNKNOWN", null, UNIT_TOKEN);
    }

    /** 按平台用量记录映射；完全未知时返回 UNKNOWN，不把缺失当 0。 */
    public static AiVisionUsageDTO from(ModelUsage usage) {
        if (usage == null || !usage.isKnown()) {
            return unknown();
        }
        return new AiVisionUsageDTO(usage.estimated() ? "ESTIMATED" : "REPORTED", usage.totalTokens(), UNIT_TOKEN);
    }
}
