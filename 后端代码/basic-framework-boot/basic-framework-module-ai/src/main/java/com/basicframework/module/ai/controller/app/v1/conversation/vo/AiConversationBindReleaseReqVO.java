package com.basicframework.module.ai.controller.app.v1.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 固定发布版本（应用端协议层 VO）：会话版本只固定一次，换版本需显式新建或迁移会话。 */
@Schema(description = "应用端 - 固定 AI 服务发布版本")
@Data
@Accessors(chain = true)
public class AiConversationBindReleaseReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "发布版本编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long releaseId;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}
