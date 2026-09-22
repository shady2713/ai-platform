package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 文档入库（协议层 VO）：sourceKey 幂等键 + 已上传的私有文件 + 文件指纹。 */
@Schema(description = "管理后台 - AI 知识文档入库")
@Data
public class AiKnowledgeDocumentIngestReqVO {

    @Schema(description = "知识库编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long knowledgeBaseId;

    @Schema(description = "来源幂等键（库内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 128)
    private String sourceKey;

    @Schema(description = "文档标题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 256)
    private String title;

    @Schema(description = "来源类型（UPLOAD/API_SYNC）")
    @Pattern(regexp = "^(UPLOAD|API_SYNC)$", message = "来源类型只能是 UPLOAD 或 API_SYNC")
    private String sourceType;

    @Schema(description = "来源位置（受控标识，不抓取第三方站点）")
    @Size(max = 512)
    private String sourceRef;

    @Schema(description = "私有文件编号（A07 业务文件上传结果）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long fileId;

    @Schema(description = "文件指纹（sha256 hex）", requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "指纹必须是 sha256 十六进制")
    private String contentHash;
}
