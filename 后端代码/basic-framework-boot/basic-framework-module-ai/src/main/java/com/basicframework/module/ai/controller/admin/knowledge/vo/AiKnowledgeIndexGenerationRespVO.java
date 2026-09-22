package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 索引代响应（协议层 VO）。 */
@Schema(description = "管理后台 - AI 知识索引代")
@Data
@Accessors(chain = true)
public class AiKnowledgeIndexGenerationRespVO {

    @Schema(description = "索引代编号")
    private Long id;

    @Schema(description = "知识库编号")
    private Long knowledgeBaseId;

    @Schema(description = "索引代序号")
    private Integer generationNo;

    @Schema(description = "嵌入模型标识")
    private String embeddingModel;

    @Schema(description = "向量维度")
    private Integer dimension;

    @Schema(description = "向量集合名")
    private String collectionName;

    @Schema(description = "状态（BUILDING/ACTIVE/RETIRED/FAILED）")
    private String status;

    @Schema(description = "本代切片数")
    private Integer chunkCount;

    @Schema(description = "本代文档数")
    private Integer documentCount;

    @Schema(description = "失败原因（脱敏原因码）")
    private String failureReason;

    @Schema(description = "激活时间")
    private java.time.LocalDateTime activatedAt;

    @Schema(description = "退役时间")
    private java.time.LocalDateTime retiredAt;
}
