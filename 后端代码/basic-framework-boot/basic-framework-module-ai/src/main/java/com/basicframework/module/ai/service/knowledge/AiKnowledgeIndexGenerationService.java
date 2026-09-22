package com.basicframework.module.ai.service.knowledge;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import java.util.List;

/**
 * 知识索引代（K02）：版本化索引的状态机。
 *
 * <p>索引代是"原子换索引"的载体：BUILDING → ACTIVE → RETIRED，失败置 FAILED。
 * 同一知识库同时只允许一个 BUILDING 与一个 ACTIVE；维度与嵌入模型来自知识库声明，
 * 不接受调用方自报（否则会出现"同集合混入不同模型向量"，AT-029 禁止）。
 */
public interface AiKnowledgeIndexGenerationService {

    /** 开始新一代（使用知识库声明的模型与维度；已有构建中的一代则拒绝）。 */
    Integer startGeneration(Long knowledgeBaseId);

    /** 激活：BUILDING → ACTIVE，统计切片与文档数，退役上一代，并切换知识库的生效指针。 */
    void activate(Long knowledgeBaseId, Integer generationNo);

    /** 标记失败：BUILDING → FAILED。 */
    void fail(Long knowledgeBaseId, Integer generationNo, String reason);

    /** 退役：ACTIVE → RETIRED（知识库生效指针指向它时一并清空）。 */
    void retire(Long knowledgeBaseId, Integer generationNo);

    /** 查询某一代（不存在抛 404）。 */
    AiKnowledgeIndexGenerationDO getGeneration(Long knowledgeBaseId, Integer generationNo);

    /** 当前生效的一代（没有返回 null）。 */
    AiKnowledgeIndexGenerationDO getActiveGeneration(Long knowledgeBaseId);

    /** 某知识库的全部索引代（代序号倒序）。 */
    List<AiKnowledgeIndexGenerationDO> listGenerations(Long knowledgeBaseId);
}
