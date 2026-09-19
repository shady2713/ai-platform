package com.basicframework.module.ai.controller.app.v1.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 追加会话消息（应用端协议层 VO）：正文属于受控业务数据，不进日志。 */
@Schema(description = "应用端 - 追加 AI 会话消息")
@Data
@Accessors(chain = true)
@ToString(exclude = {"content"})
public class AiConversationMessageReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long conversationId;

    @Schema(description = "角色（user/assistant/system）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String role;

    @Schema(description = "消息正文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16000)
    private String content;

    @Schema(description = "产生该消息的运行编号（O02 起使用）")
    @Positive
    private Long sourceRunId;
}
