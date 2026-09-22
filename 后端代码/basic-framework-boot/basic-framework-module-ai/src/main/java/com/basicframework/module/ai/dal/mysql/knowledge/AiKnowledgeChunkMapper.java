package com.basicframework.module.ai.dal.mysql.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeChunkDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 知识切片 Mapper（K02）：派生数据，物理清理。 */
@Mapper
public interface AiKnowledgeChunkMapper extends BaseMapperX<AiKnowledgeChunkDO> {

    /** 某版本的切片（序号升序，引用顺序稳定）。 */
    default List<AiKnowledgeChunkDO> selectByVersion(Long documentVersionId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeChunkDO>()
                .eq(AiKnowledgeChunkDO::getDocumentVersionId, documentVersionId)
                .orderByAsc(AiKnowledgeChunkDO::getChunkIndex));
    }

    /** 某版本切片数。 */
    default long countByVersion(Long documentVersionId) {
        return selectCount(new LambdaQueryWrapper<AiKnowledgeChunkDO>()
                .eq(AiKnowledgeChunkDO::getDocumentVersionId, documentVersionId));
    }

    /** 某索引代的切片数（激活索引代时统计）。 */
    default long countByGeneration(Long knowledgeBaseId, Integer generationNo) {
        return selectCount(new LambdaQueryWrapper<AiKnowledgeChunkDO>()
                .eq(AiKnowledgeChunkDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeChunkDO::getIndexGeneration, generationNo));
    }

    /** 某索引代的版本数（激活索引代时统计）。 */
    @Select("SELECT COUNT(DISTINCT document_version_id) FROM ai_knowledge_chunk"
            + " WHERE knowledge_base_id = #{knowledgeBaseId} AND index_generation = #{generationNo}")
    long countDistinctVersions(
            @Param("knowledgeBaseId") Long knowledgeBaseId, @Param("generationNo") Integer generationNo);

    /** 物理删除某版本的切片（重索引或版本回收时调用）。 */
    @Delete("DELETE FROM ai_knowledge_chunk WHERE document_version_id = #{documentVersionId}")
    int deleteByVersion(@Param("documentVersionId") Long documentVersionId);

    /** 物理删除某索引代的切片（索引退役清理）。 */
    @Delete("DELETE FROM ai_knowledge_chunk WHERE knowledge_base_id = #{knowledgeBaseId}"
            + " AND index_generation = #{generationNo}")
    int deleteByGeneration(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("generationNo") Integer generationNo);
}
