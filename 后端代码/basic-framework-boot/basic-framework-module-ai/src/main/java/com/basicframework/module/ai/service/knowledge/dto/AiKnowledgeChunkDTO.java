package com.basicframework.module.ai.service.knowledge.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 切片写入项（服务层 DTO）：正文只进向量服务，这里只带可核验的元数据。 */
@Data
@Accessors(chain = true)
public class AiKnowledgeChunkDTO {

    /** 切片序号（版本内从 0 递增） */
    private Integer chunkIndex;

    /** 正文哈希（sha256 hex） */
    private String contentHash;

    /** 正文长度（字符数） */
    private Integer textLength;

    /** 估算 token 数（名称命中敏感字段规则，不进 toString） */
    @ToString.Exclude
    private Integer tokenCount;

    /** 向量点标识（确定性 UUID） */
    private String vectorId;

    /** 来源位置（页码/章节等） */
    private String locationRef;
}
