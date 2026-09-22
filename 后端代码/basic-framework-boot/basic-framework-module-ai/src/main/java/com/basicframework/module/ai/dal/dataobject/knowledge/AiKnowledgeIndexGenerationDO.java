package com.basicframework.module.ai.dal.dataobject.knowledge;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 知识索引代（K02）：版本化索引，物理清理（生命周期策略 hard-delete）。
 *
 * <p>为什么需要"代"而不是直接覆盖集合：向量集合的维度在创建时固定（K01），
 * 换嵌入模型就必须新建集合；即使同维度换模型，两代向量也不能混在一个集合里检索（AT-029）。
 * 因此索引是**一代一代**推进的：BUILDING 构建 → ACTIVE 生效 → RETIRED 退役，失败置 FAILED；
 * 知识库的 {@code active_generation_no} 指向当前生效的一代，切换即"原子换索引"。
 */
@TableName("ai_knowledge_index_generation")
@KeySequence("ai_knowledge_index_generation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiKnowledgeIndexGenerationDO extends BaseDO {

    /** 状态：构建中。 */
    public static final String STATUS_BUILDING = "BUILDING";

    /** 状态：生效中（同一知识库最多一个）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已退役（可被清理）。 */
    public static final String STATUS_RETIRED = "RETIRED";

    /** 状态：构建失败。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 索引代编号 */
    @TableId
    private Long id;

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 索引代序号（库内递增） */
    private Integer generationNo;

    /** 嵌入模型标识 */
    private String embeddingModel;

    /** 向量维度（必须与知识库声明一致） */
    private Integer dimension;

    /** 向量集合名（物理索引名） */
    private String collectionName;

    /** 状态（BUILDING/ACTIVE/RETIRED/FAILED） */
    private String status;

    /** 本代切片数 */
    private Integer chunkCount;

    /** 本代文档数 */
    private Integer documentCount;

    /** 失败原因（脱敏稳定原因码） */
    private String failureReason;

    /** 激活时间 */
    private java.time.LocalDateTime activatedAt;

    /** 退役时间 */
    private java.time.LocalDateTime retiredAt;

    /** 乐观锁版本 */
    private Integer version;
}
