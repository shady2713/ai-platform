package com.basicframework.module.ai.controller.app.v1.image.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 媒体产物（协议层）：平台私有文件编号 + 服务端核验过的事实，没有地址字段。 */
@Data
@Accessors(chain = true)
@Schema(description = "媒体产物（私有文件引用）")
public class AiMediaAssetRespVO {

    @Schema(description = "平台私有文件编号（读取走受控文件接口，按当前授权判定）", example = "2048")
    private Long fileId;

    @Schema(description = "产物序号（同一任务内从 1 开始，决定展示顺序）", example = "1")
    private Integer ordinal;

    @Schema(description = "产物 MIME", example = "image/png")
    private String mimeType;

    @Schema(description = "产物字节数", example = "20480")
    private Long sizeBytes;

    @Schema(description = "产物内容摘要（服务端计算）")
    private String sha256;

    @Schema(description = "图片宽度（非图片或未知为空，不写 0）")
    private Integer width;

    @Schema(description = "图片高度（非图片或未知为空，不写 0）")
    private Integer height;

    @Schema(description = "音频时长（毫秒；非音频或未知为空）")
    private Long durationMillis;
}
