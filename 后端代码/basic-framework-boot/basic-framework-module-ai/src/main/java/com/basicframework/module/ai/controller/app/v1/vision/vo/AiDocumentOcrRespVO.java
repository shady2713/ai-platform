package com.basicframework.module.ai.controller.app.v1.vision.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 应用端 - 扫描件 OCR 重索引结果（X02）：新版本 + 任务 + 识别稿元信息。 */
@Schema(description = "应用端 - 扫描件 OCR 重索引结果")
@Data
@Accessors(chain = true)
public class AiDocumentOcrRespVO {

    @Schema(description = "文档编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long documentId;

    @Schema(description = "版本编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long versionId;

    @Schema(description = "版本号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer versionNo;

    @Schema(description = "入库任务编号（由既有入库流水线处理）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @Schema(description = "是否产生了新版本")
    private boolean createdVersion;

    @Schema(description = "是否复用了既有版本（同指纹重复提交不会产生第二个版本）")
    private boolean reused;

    @Schema(description = "识别稿页数（只统计有正文的页）")
    private Integer pageCount;

    @Schema(description = "识别稿字符数")
    private Integer characterCount;

    @Schema(description = "识别稿置信度来源：PROVIDER/UNKNOWN（有一页未提供即 UNKNOWN）")
    private String confidenceSource;

    @Schema(description = "是否需要人工复核（识别稿恒为 true）")
    private boolean reviewRequired;

    @Schema(description = "识别依据的原始私有文件编号")
    private Long sourceFileId;

    @Schema(description = "派生识别稿的私有文件编号")
    private Long derivedFileId;

    @Schema(description = "版本来源位置（受控标识，如 ocr:sourceFileId=1024）")
    private String sourceRef;
}
