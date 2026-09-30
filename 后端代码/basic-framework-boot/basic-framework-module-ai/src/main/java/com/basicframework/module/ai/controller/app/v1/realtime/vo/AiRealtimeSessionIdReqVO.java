package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 会话编号请求（X05）：查询、断线、关闭等只需会话编号的操作共用。 */
@Schema(description = "实时会话编号请求")
@Data
@Accessors(chain = true)
public class AiRealtimeSessionIdReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sessionId;

    /** 便捷构造（控制器与测试共用）。 */
    public static AiRealtimeSessionIdReqVO of(Long sessionId) {
        return new AiRealtimeSessionIdReqVO().setSessionId(sessionId);
    }
}
