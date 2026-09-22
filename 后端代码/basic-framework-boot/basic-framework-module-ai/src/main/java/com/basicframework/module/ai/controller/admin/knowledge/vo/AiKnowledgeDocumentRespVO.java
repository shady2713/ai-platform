package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 知识文档响应（协议层 VO）：只含状态与指针，不含文件正文。 */
@Schema(description = "管理后台 - AI 知识文档")
@Data
@Accessors(chain = true)
public class AiKnowledgeDocumentRespVO {

    @Schema(description = "文档编号")
    private Long id;

    @Schema(description = "知识库编号")
    private Long knowledgeBaseId;

    @Schema(description = "来源幂等键")
    private String sourceKey;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源位置")
    private String sourceRef;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "当前可用版本号（0 表示尚无可用版本）")
    private Integer activeVersionNo;

    @Schema(description = "最新版本号")
    private Integer latestVersionNo;

    @Schema(description = "最近失败原因（脱敏原因码）")
    private String failureReason;

    @Schema(description = "解析提示（例如扫描件需要 OCR）")
    private String parseNote;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private java.time.LocalDateTime createTime;
}
