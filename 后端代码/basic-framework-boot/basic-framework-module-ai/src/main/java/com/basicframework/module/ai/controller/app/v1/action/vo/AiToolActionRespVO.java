package com.basicframework.module.ai.controller.app.v1.action.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 工具动作（协议层 VO）：**不回显参数正文与挑战**（挑战只在创建时回给发起主体一次）。
 *
 * <p>写动作（X06）额外给出业务幂等键与核对结论：幂等键是操作员核对业务系统所需的最小业务标识
 * （由调用方自己提交，不是凭据）；核对证据只含稳定结论（登记操作键与条目数，或人工说明）。
 */
@Schema(description = "AI 应用端 - 工具动作")
@Data
@Accessors(chain = true)
public class AiToolActionRespVO {

    @Schema(description = "动作编号")
    private Long id;

    @Schema(description = "运行编号")
    private Long runId;

    @Schema(description = "工具编号")
    private Long toolId;

    @Schema(description = "工具版本编号")
    private Long toolVersionId;

    @Schema(description = "工具类型（READ/WRITE）")
    private String toolType;

    @Schema(description = "状态（PENDING/CONFIRMED/EXECUTING/EXECUTED/CANCELLED/EXPIRED/FAILED/UNKNOWN）")
    private String status;

    @Schema(description = "业务幂等键（写动作；调用方自己提交的业务标识，不是凭据）")
    private String idempotencyKey;

    @Schema(description = "过期时间")
    private LocalDateTime expiresAt;

    @Schema(description = "确认/拒绝时间")
    private LocalDateTime decidedAt;

    @Schema(description = "执行尝试时间")
    private LocalDateTime executedAt;

    @Schema(description = "执行尝试代数（确认只被消费一次；重放不会推进）")
    private Integer attemptEpoch;

    @Schema(description = "执行结论（稳定原因码）")
    private String resultCode;

    @Schema(description = "核对时间（结果未定的动作经核对收敛后给出）")
    private LocalDateTime verifiedAt;

    @Schema(description = "核对方式（PROGRAM/MANUAL）")
    private String verifiedBy;

    @Schema(description = "核对结论（APPLIED 已生效/NOT_APPLIED 未生效）")
    private String verifyResult;

    @Schema(description = "核对证据（登记操作键与条目数，或人工说明）")
    private String verifyEvidence;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
