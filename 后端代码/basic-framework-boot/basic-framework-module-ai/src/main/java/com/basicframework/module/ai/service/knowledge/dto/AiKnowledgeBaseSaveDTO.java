package com.basicframework.module.ai.service.knowledge.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 知识库保存请求（服务层 DTO）：标识、可见性、嵌入模型与维度、保留策略。 */
@Data
@Accessors(chain = true)
public class AiKnowledgeBaseSaveDTO {

    /** 知识库编号（修改时必填） */
    private Long id;

    /** 知识库标识（创建后不可修改） */
    private String code;

    /** 名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 可见性（SHARED/APPLICATION） */
    private String visibility;

    /** 所属应用编号（应用专用必填） */
    private Long ownerApplicationId;

    /** 管理者用户编号（可空；仅展示） */
    private Long managerUserId;

    /** 嵌入模型标识（创建后不可修改） */
    private String embeddingModel;

    /** 嵌入维度（创建后不可修改） */
    private Integer embeddingDimension;

    /** 保留策略（天） */
    private Integer retentionDays;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
