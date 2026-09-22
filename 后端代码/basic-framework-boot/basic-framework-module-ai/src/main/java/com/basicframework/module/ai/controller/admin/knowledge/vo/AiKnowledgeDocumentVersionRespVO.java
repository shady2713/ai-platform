package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 文档版本响应（协议层 VO）：文件只给编号，不给内容与存储地址。 */
@Schema(description = "管理后台 - AI 知识文档版本")
@Data
@Accessors(chain = true)
public class AiKnowledgeDocumentVersionRespVO {

    @Schema(description = "版本编号")
    private Long id;

    @Schema(description = "文档编号")
    private Long documentId;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "私有文件编号")
    private Long fileId;

    @Schema(description = "文件指纹")
    private String contentHash;

    @Schema(description = "来源位置")
    private String sourceRef;

    @Schema(description = "状态（INDEXING/READY/FAILED/SUPERSEDED）")
    private String status;

    @Schema(description = "切片所属索引代")
    private Integer indexGeneration;

    @Schema(description = "切片数")
    private Integer chunkCount;

    @Schema(description = "失败原因（脱敏原因码）")
    private String failureReason;

    @Schema(description = "可用时间")
    private java.time.LocalDateTime readyAt;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private java.time.LocalDateTime createTime;
}
