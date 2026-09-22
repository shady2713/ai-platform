package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 知识库响应（协议层 VO）：不含任何凭据与文件正文。 */
@Schema(description = "管理后台 - AI 知识库")
@Data
@Accessors(chain = true)
public class AiKnowledgeBaseRespVO {

    @Schema(description = "知识库编号")
    private Long id;

    @Schema(description = "知识库标识（A03 授权目录的资源标识）")
    private String code;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "可见性")
    private String visibility;

    @Schema(description = "所属应用编号")
    private Long ownerApplicationId;

    @Schema(description = "管理者用户编号")
    private Long managerUserId;

    @Schema(description = "嵌入模型标识")
    private String embeddingModel;

    @Schema(description = "嵌入维度")
    private Integer embeddingDimension;

    @Schema(description = "当前生效的索引代（0 表示尚无可用索引）")
    private Integer activeGenerationNo;

    @Schema(description = "保留策略（天）")
    private Integer retentionDays;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private java.time.LocalDateTime createTime;
}
