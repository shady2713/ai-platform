package com.basicframework.module.ai.service.knowledge.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 引用（K06）：从**本次检索候选**映射出来的可核验来源。
 *
 * <p>引用只允许由检索候选构建：模型不能自报文档编号（AT-026）。
 * {@code citationId} 是"知识库:版本:切片"的稳定标识，读取片段或原文时**再次鉴权**（同一条业务 ACL）。
 */
@Data
@Accessors(chain = true)
public class AiKnowledgeCitationDTO {

    /** 引用标识（知识库:版本编号:切片序号） */
    private String citationId;

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 文档编号 */
    private Long documentId;

    /** 文档标题 */
    private String title;

    /** 文档版本号 */
    private Integer versionNo;

    /** 切片序号 */
    private Integer chunkIndex;

    /** 来源位置（第 N 页 / 段落 N） */
    private String locationRef;

    /** 片段正文（只来自本次检索候选；受限文档不会出现在这里） */
    private String snippet;
}
