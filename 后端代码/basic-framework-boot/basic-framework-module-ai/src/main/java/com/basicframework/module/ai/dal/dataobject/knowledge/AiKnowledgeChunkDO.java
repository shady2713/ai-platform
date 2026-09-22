package com.basicframework.module.ai.dal.dataobject.knowledge;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 知识切片（K02）：派生数据，物理清理（生命周期策略 hard-delete）。
 *
 * <p>为什么切片行不存正文：正文的唯一载体是向量服务的点载荷（K01 的 upsert/search 已验证），
 * 这里保存**可核验的元数据**——哈希、长度、token 估算、向量点标识、来源位置与所属索引代。
 * 引用（AT-026）据此回到"哪个版本、哪个片段位置"，而不是让模型自报文档编号。
 *
 * <p>没有 {@code deleted} 列：切片随版本换代/文档删除被物理回收（K07），
 * 保留逻辑删除列只会让"已撤销内容仍可被检索"更难判定。
 */
@TableName("ai_knowledge_chunk")
@KeySequence("ai_knowledge_chunk_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiKnowledgeChunkDO extends BaseDO {

    /** 切片编号 */
    @TableId
    private Long id;

    /** 文档版本编号 */
    private Long documentVersionId;

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 切片序号（版本内从 0 递增） */
    private Integer chunkIndex;

    /** 切片正文哈希 */
    private String contentHash;

    /** 正文长度（字符数） */
    private Integer textLength;

    /** 估算 token 数（名称命中敏感字段规则，不进 toString） */
    @ToString.Exclude
    private Integer tokenCount;

    /** 向量点标识（确定性 UUID） */
    private String vectorId;

    /** 所属索引代 */
    private Integer indexGeneration;

    /** 来源位置（页码/章节等） */
    private String locationRef;
}
