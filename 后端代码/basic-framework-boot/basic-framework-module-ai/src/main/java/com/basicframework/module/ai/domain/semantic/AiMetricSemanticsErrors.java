package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_AGGREGATION_ORDER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CONVERSION_RULE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SOURCE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SOURCE_INVALID;

/**
 * 跨源口径的稳定错误出口（Y03）。
 *
 * <p>把这些转换集中在一处，是为了让"形状非法"与"口径冲突"在所有解析路径上
 * 得到同一个错误码：登记、读取与聚合三条路径都调用这里，任何一条走偏都会造成
 * "同一份口径在不同路径下结论不同"（与 Y02 {@code AiMasterMappingFacts} 同样的动机）。
 */
final class AiMetricSemanticsErrors {

    private AiMetricSemanticsErrors() {}

    /** 来源声明形状非法（键白名单、名称、枚举、数组形状、数值范围）。 */
    static RuntimeException invalidSource() {
        return exception(AI_METRIC_SOURCE_INVALID);
    }

    /** 同一版本内重复声明同一数据集版本。 */
    static RuntimeException duplicateSource() {
        return exception(AI_METRIC_SOURCE_DUPLICATE);
    }

    /** 来源缺少必需口径项（单位 / 币种 / 时区 / 粒度），不允许推断补全。 */
    static RuntimeException missingCaliber() {
        return exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
    }

    /** 换算规则与目标口径冲突（规则非法或目标币种与口径币种不一致）。 */
    static RuntimeException conversionRuleConflict() {
        return exception(AI_METRIC_CONVERSION_RULE_CONFLICT);
    }

    /** 聚合顺序未恰好覆盖每个来源角色。 */
    static RuntimeException aggregationOrderConflict() {
        return exception(AI_METRIC_AGGREGATION_ORDER_CONFLICT);
    }
}
