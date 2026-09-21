package com.basicframework.module.ai.controller.app.v1.action.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 工具动作（协议层 VO）：**不回显参数正文与挑战**（挑战只在创建时回给发起主体一次）。
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

    @Schema(description = "状态（PENDING/CONFIRMED/EXECUTED/CANCELLED/EXPIRED/FAILED）")
    private String status;

    @Schema(description = "过期时间")
    private LocalDateTime expiresAt;

    @Schema(description = "确认/拒绝时间")
    private LocalDateTime decidedAt;

    @Schema(description = "执行时间")
    private LocalDateTime executedAt;

    @Schema(description = "执行结论（稳定原因码）")
    private String resultCode;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
