package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 执行会话内工具调用请求（X05）。
 *
 * <p>只带**会话内工具调用编号**：工具标识与参数来自会话事件里冻结的事实，客户端改不了；
 * 执行走 D08/X06 的受控闸门，且每个调用最多执行一次（重连重发返回既有结论）。
 */
@Schema(description = "执行会话内工具调用请求")
@Data
public class AiRealtimeToolExecuteReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sessionId;

    @Schema(description = "会话内工具调用编号（不是上游调用标识）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long toolCallId;
}
