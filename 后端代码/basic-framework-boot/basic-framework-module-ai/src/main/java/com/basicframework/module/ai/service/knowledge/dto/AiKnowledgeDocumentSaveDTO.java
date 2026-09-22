package com.basicframework.module.ai.service.knowledge.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 文档入库请求（服务层 DTO）：sourceKey 幂等键 + 私有文件 + 指纹。 */
@Data
@Accessors(chain = true)
public class AiKnowledgeDocumentSaveDTO {

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 来源幂等键（库内唯一；必填） */
    private String sourceKey;

    /** 标题 */
    private String title;

    /** 来源类型（UPLOAD/API_SYNC） */
    private String sourceType;

    /** 来源位置（受控标识） */
    private String sourceRef;

    /** 私有文件编号（A07 业务文件；必填） */
    private Long fileId;

    /** 文件指纹（sha256 hex；必填，决定复用还是新建版本） */
    private String contentHash;
}
