package com.basicframework.module.ai.controller.app.v1.vision.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 应用端 - 单张图片 OCR 结果（X02）：文本 + 页码 + 识别范围 + 置信度来源 + 需人工复核标记。
 *
 * <p>`confidenceSource=UNKNOWN` 时 `confidence` 必须为空：识别质量没有被上游量化，
 * 平台不填任何数字；`reviewRequired=true` 表示机器识别、未经人工核验。
 */
@Schema(description = "应用端 - 单张图片 OCR 结果")
@Data
@Accessors(chain = true)
public class AiVisionOcrRespVO {

    @Schema(description = "结果引用的私有图片编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long fileId;

    @Schema(description = "页码（从 1 开始；单张图片为 1）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer page;

    @Schema(description = "识别文本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String text;

    @Schema(description = "识别范围（相对图片的归一化坐标）")
    private Region region;

    @Schema(description = "识别范围来源：PROVIDER（上游逐块）/ WHOLE_PAGE（整页归因）")
    private String regionSource;

    @Schema(description = "置信度来源：PROVIDER（上游给出）/ UNKNOWN（上游未提供）")
    private String confidenceSource;

    @Schema(description = "置信度（0..1）；仅 confidenceSource=PROVIDER 时有值")
    private Double confidence;

    @Schema(description = "是否需要人工复核（机器识别未核验时为 true）")
    private boolean reviewRequired;

    @Schema(description = "用量；上游未提供时为 UNKNOWN 且 quantity 为空")
    private AiVisionTextRespVO.Usage usage;

    /** 识别范围（归一化坐标）。 */
    @Schema(description = "识别范围（归一化坐标）")
    @Data
    @Accessors(chain = true)
    public static class Region {

        @Schema(description = "左上角横坐标（0..1）")
        private Double x;

        @Schema(description = "左上角纵坐标（0..1）")
        private Double y;

        @Schema(description = "宽度（0..1）")
        private Double width;

        @Schema(description = "高度（0..1）")
        private Double height;
    }
}
