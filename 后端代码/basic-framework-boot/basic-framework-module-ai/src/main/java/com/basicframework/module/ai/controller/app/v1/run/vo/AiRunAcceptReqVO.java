package com.basicframework.module.ai.controller.app.v1.run.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 受理运行（应用端协议层 VO）。
 *
 * <p>幂等键必填：同键同请求复用原运行，同键不同请求返回 409。正文与上下文不进日志。
 */
@Schema(description = "应用端 - 受理 AI 运行")
@Data
@Accessors(chain = true)
@ToString(exclude = {"message", "businessContext", "attachmentKeys"})
public class AiRunAcceptReqVO {

    @Schema(description = "服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long serviceId;

    @Schema(description = "会话编号（可空：无会话的一次性运行）")
    @Positive
    private Long conversationId;

    @Schema(description = "幂等键（16-128 位；同一主体内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(min = 16, max = 128)
    private String idempotencyKey;

    @Schema(description = "用户消息", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16000)
    private String message;

    @Schema(description = "附件标识（业务侧文件标识；顺序无关）")
    @Size(max = 20)
    private List<@Size(max = 128) String> attachmentKeys;

    @Schema(description = "业务上下文（已注册字段的 JSON 对象文本）")
    @Size(max = 4000)
    private String businessContext;

    @Schema(
            description = "本次输入的数据分级（L1_PUBLIC/L2_INTERNAL/L3_PERSONAL/L4_SECRET）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String dataLevel;
}
