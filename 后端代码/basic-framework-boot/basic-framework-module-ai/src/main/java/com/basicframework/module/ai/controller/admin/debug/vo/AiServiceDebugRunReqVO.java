package com.basicframework.module.ai.controller.admin.debug.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 调试运行请求（协议层 VO）：必须显式给出测试主体与数据分级。 */
@Schema(description = "管理后台 - AI 服务调试运行请求")
@Data
@Accessors(chain = true)
@ToString(exclude = {"userMessage", "history", "businessContext", "maxTokens"})
public class AiServiceDebugRunReqVO {

    @Schema(description = "服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long serviceId;

    @Schema(description = "测试主体类型（USER/APP）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String testSubjectType;

    @Schema(description = "测试主体标识（USER 为可信外部用户标识）")
    @Size(max = 64)
    private String testSubjectId;

    @Schema(description = "本次消息", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16000)
    private String userMessage;

    @Schema(description = "历史消息（越新越靠后）")
    private List<HistoryItem> history;

    @Schema(description = "业务上下文（已注册字段的 JSON 对象文本）")
    @Size(max = 4000)
    private String businessContext;

    @Schema(
            description = "调试输入的数据分级（L1_PUBLIC/L2_INTERNAL/L3_PERSONAL/L4_SECRET）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String dataLevel;

    @Schema(description = "模型调用超时（毫秒，缺省 30000，上限 120000）")
    @Min(1)
    @Max(120000)
    private Integer timeoutMillis;

    @Schema(description = "历史消息条数上限（缺省 20）")
    @Min(1)
    private Integer maxMessages;

    @Schema(description = "输入 token 预算上限（缺省 8000）")
    @Min(1)
    private Integer maxTokens;

    /** 历史消息条目。 */
    @Schema(description = "管理后台 - AI 服务调试历史消息")
    @Data
    @Accessors(chain = true)
    public static class HistoryItem {

        @Schema(description = "角色（user/assistant/system）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String role;

        @Schema(description = "消息正文", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 16000)
        private String content;
    }
}
