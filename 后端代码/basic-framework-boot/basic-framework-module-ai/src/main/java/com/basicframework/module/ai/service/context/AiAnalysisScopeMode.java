package com.basicframework.module.ai.service.context;

import java.util.Locale;
import java.util.Optional;

/** 跨系统分析的显式范围选择模式（Y01）。 */
public enum AiAnalysisScopeMode {

    /** 只分析当前系统（单系统回归路径：不涉及任何跨系统事实）。 */
    CURRENT_SYSTEM,

    /** 跨系统分析：必须显式列出目标系统，且必须包含当前系统。 */
    CROSS_SYSTEM;

    /** 解析模式；未知/空返回空（调用方按拒绝处理，不猜默认值）。 */
    public static Optional<AiAnalysisScopeMode> parse(String value) {
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
