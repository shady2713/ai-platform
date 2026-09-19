package com.basicframework.module.ai.controller.app.v1.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 绑定服务（应用端协议层 VO）。 */
@Schema(description = "应用端 - 绑定 AI 服务")
@Data
@Accessors(chain = true)
public class AiConversationBindReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long serviceId;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}
