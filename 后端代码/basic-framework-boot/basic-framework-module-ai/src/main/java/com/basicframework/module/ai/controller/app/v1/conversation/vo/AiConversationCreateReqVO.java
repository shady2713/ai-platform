package com.basicframework.module.ai.controller.app.v1.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 创建会话（应用端协议层 VO）：归属由服务端会话身份决定，请求体不能自报。 */
@Schema(description = "应用端 - 创建 AI 会话")
@Data
@Accessors(chain = true)
@ToString(exclude = {"businessContext"})
public class AiConversationCreateReqVO {

    @Schema(description = "会话业务键（conv_ 前缀，同一应用+主体内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 40)
    private String conversationKey;

    @Schema(description = "会话标题")
    @Size(max = 128)
    private String title;

    @Schema(description = "绑定的服务编号")
    @Positive
    private Long serviceId;

    @Schema(description = "业务上下文（已注册字段的 JSON 对象文本）")
    @Size(max = 4000)
    private String businessContext;
}
