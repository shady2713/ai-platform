package com.basicframework.module.ai.service.knowledge;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeChunkDO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import java.util.List;

/**
 * 知识切片（K02）：派生数据，物理清理。
 *
 * <p>写入是**整版本替换**：同一版本重复索引时先删后插，保证切片序号与向量点一一对应
 * （追加写会留下上一次索引的孤儿切片，检索时会命中已删除内容）。
 * 只允许对 INDEXING 版本写入——READY 版本的切片是引用依据，不能再改。
 */
public interface AiKnowledgeChunkService {

    /** 整版本替换切片（返回写入条数；要求版本处于 INDEXING）。 */
    int replaceVersionChunks(Long documentVersionId, Integer generationNo, List<AiKnowledgeChunkDTO> chunks);

    /** 某版本的切片（序号升序）。 */
    List<AiKnowledgeChunkDO> listVersionChunks(Long documentVersionId);

    /** 某索引代的切片数。 */
    long countByGeneration(Long knowledgeBaseId, Integer generationNo);

    /** 物理删除某版本的切片（重索引前或版本回收）。 */
    int deleteVersionChunks(Long documentVersionId);

    /** 物理删除某索引代的切片（索引退役清理）。 */
    int deleteGenerationChunks(Long knowledgeBaseId, Integer generationNo);
}
