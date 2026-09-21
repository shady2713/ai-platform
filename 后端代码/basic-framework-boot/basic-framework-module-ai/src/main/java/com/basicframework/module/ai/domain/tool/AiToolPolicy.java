package com.basicframework.module.ai.domain.tool;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具执行政策（D08）：AUTO / CONFIRM / DENY，**默认 DENY**。
 *
 * <p>政策是版本快照的一部分（不是可变开关）：发布后不可修改，改政策必须新建版本并重新发布。
 * 这样"政策被悄悄放宽"在数据层就不可表达。
 *
 * <p>政策矩阵（模型 tool-call 的处理）：
 * <pre>
 *   AUTO    → 允许直接执行（仍要过参数校验与来源绑定）
 *   CONFIRM → 不执行，返回"需要人工确认"（确认流程由 D09 编排）
 *   DENY    → 拒绝执行（403）
 * </pre>
 */
public enum AiToolPolicy {

    /** 自动执行。 */
    AUTO,

    /** 需要人工确认后执行。 */
    CONFIRM,

    /** 禁止执行（默认值）。 */
    DENY;

    /** 允许的取值（落库前校验）。 */
    public static final Set<String> NAMES = Set.of("AUTO", "CONFIRM", "DENY");

    /** 解析政策（未知取值一律按 DENY 处理，绝不"猜成更宽松的"）。 */
    public static AiToolPolicy parse(String value) {
        if (value == null) {
            return DENY;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return NAMES.contains(normalized) ? valueOf(normalized) : DENY;
    }

    /** 工具类型：首期只允许 READ。 */
    public enum ToolType {
        /** 只读工具。 */
        READ,

        /** 写工具（首期不允许发布）。 */
        WRITE;

        /** 解析类型（未知按 WRITE 处理：更严格）。 */
        public static ToolType parse(String value) {
            return value != null && "READ".equalsIgnoreCase(value.trim()) ? READ : WRITE;
        }
    }

    /** 工具可用的执行政策取值列表（供协议层与文档引用）。 */
    public static List<String> names() {
        return List.of(AUTO.name(), CONFIRM.name(), DENY.name());
    }
}
