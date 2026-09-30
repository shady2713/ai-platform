package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 会话内工具调用状态（协议层 VO，X05）：状态与稳定结论，不含参数正文与上游报文。 */
@Schema(description = "会话内工具调用状态")
@Data
@Accessors(chain = true)
public class AiRealtimeToolCallRespVO {

    @Schema(description = "工具调用编号（执行入口用它）")
    private Long id;

    @Schema(description = "提出该调用的回合")
    private Long turnNo;

    @Schema(description = "上游工具调用标识")
    private String callId;

    @Schema(description = "工具标识")
    private String toolCode;

    @Schema(description = "状态（PROPOSED/EXECUTING/EXECUTED/REJECTED/FAILED）")
    private String status;

    @Schema(description = "结论稳定码（不含上游正文）")
    private String resultCode;

    @Schema(description = "执行完成时间")
    private LocalDateTime executedTime;
}
