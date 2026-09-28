package com.basicframework.module.ai.controller.app.v1.vision.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 应用端 - 图片理解结果（X02）：只有私有文件标识与文本，没有上游地址。 */
@Schema(description = "应用端 - 图片理解结果")
@Data
@Accessors(chain = true)
public class AiVisionTextRespVO {

    @Schema(description = "结果引用的私有图片编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long fileId;

    @Schema(description = "模型返回的文本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String text;

    @Schema(description = "用量；上游未提供时为 UNKNOWN 且 quantity 为空，不用 0 冒充")
    private Usage usage;

    @Schema(description = "结束原因（上游稳定映射，可为空）")
    private String finishReason;

    /** 用量（与前端契约 multimodalUsage 同字段）。 */
    @Schema(description = "媒体用量")
    @Data
    @Accessors(chain = true)
    public static class Usage {

        @Schema(description = "用量来源：REPORTED/ESTIMATED/UNKNOWN", requiredMode = Schema.RequiredMode.REQUIRED)
        private String source;

        @Schema(description = "数量；UNKNOWN 时为空")
        private Integer quantity;

        @Schema(description = "计量单位（媒体文本按 TOKEN 计量）")
        private String unit;
    }
}
