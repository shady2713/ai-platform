package com.basicframework.module.ai.service.tool.action.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 分析步骤结果（D09）：已执行、待确认或失败（失败以稳定错误码抛出）。 */
@Data
@Accessors(chain = true)
public class AiAnalysisStepResultDTO {

    /** 步骤结论（EXECUTED / AWAITING_CONFIRMATION） */
    private String outcome;

    /** 步骤结论：已执行 */
    public static final String OUTCOME_EXECUTED = "EXECUTED";

    /** 步骤结论：等待人工确认（已生成动作与挑战） */
    public static final String OUTCOME_AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";

    /** 已用步数（含本次） */
    private Integer stepsUsed;

    /** 动作编号（待确认时存在） */
    private Long actionId;

    /** 一次性确认挑战（待确认时存在；只回给发起主体） */
    private String challenge;

    /** 动作过期时间（待确认时存在） */
    private LocalDateTime expiresAt;

    /** 上游执行状态（已执行时存在；COMPLETE/PARTIAL/FAILED） */
    private String sourceStatus;

    /** 停止/失败原因（稳定原因码） */
    private String reason;
}
