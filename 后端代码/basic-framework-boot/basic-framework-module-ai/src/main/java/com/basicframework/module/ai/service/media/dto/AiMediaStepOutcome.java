package com.basicframework.module.ai.service.media.dto;

import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 媒体执行步骤的结论（X03）：只包含"任务该怎么落终态"的稳定事实。
 *
 * <p>执行器不写任务终态（写终态由服务层的租约栅栏负责），只回报状态、失败原因码、已落库产物数量与用量；
 * 用量缺失记 {@code UNKNOWN} 且数值为空——不写 0 冒充真实计量（AT-060）。
 */
@Data
@Accessors(chain = true)
public class AiMediaStepOutcome {

    /** 成功：产物已落平台私有文件。 */
    public static AiMediaStepOutcome succeeded(
            int resultCount, String usageUnit, Long usageQuantity, String usageSource) {
        return new AiMediaStepOutcome()
                .setStatus(AiMediaTaskDO.STATUS_SUCCEEDED)
                .setResultCount(resultCount)
                .setUsageUnit(usageUnit)
                .setUsageQuantity(usageQuantity)
                .setUsageSource(usageSource == null ? AiMediaTaskDO.USAGE_SOURCE_UNKNOWN : usageSource);
    }

    /** 失败：只带稳定原因码（不含上游正文）。 */
    public static AiMediaStepOutcome failed(String failureCode) {
        return new AiMediaStepOutcome()
                .setStatus(AiMediaTaskDO.STATUS_FAILED)
                .setFailureCode(failureCode)
                .setResultCount(0)
                .setUsageUnit(null)
                .setUsageQuantity(null)
                .setUsageSource(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
    }

    /** 终态（SUCCEEDED/FAILED） */
    private String status;

    /** 失败原因码（成功为空） */
    private String failureCode;

    /** 已落库产物数量 */
    private int resultCount;

    /** 计量单位（未知为空） */
    private String usageUnit;

    /** 计量数值（未知为空） */
    private Long usageQuantity;

    /** 计量来源（REPORTED/UNKNOWN） */
    private String usageSource;
}
