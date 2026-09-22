package com.basicframework.module.ai.service.knowledge.ingestion.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 入库请求（服务层 DTO）：知识库 + sourceKey + 已上传的私有文件 + 服务端计算的指纹。 */
@Data
@Accessors(chain = true)
public class AiKnowledgeIngestionRequestDTO {

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 来源幂等键 */
    private String sourceKey;

    /** 标题 */
    private String title;

    /** 来源类型（UPLOAD/API_SYNC） */
    private String sourceType;

    /** 来源位置 */
    private String sourceRef;

    /** 私有文件编号（A07 业务文件） */
    private Long fileId;

    /** 文件指纹（sha256 hex，由服务端计算，不信任调用方） */
    private String contentHash;
}
