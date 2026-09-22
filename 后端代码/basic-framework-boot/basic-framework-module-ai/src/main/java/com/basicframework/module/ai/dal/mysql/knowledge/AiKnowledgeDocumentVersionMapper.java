package com.basicframework.module.ai.dal.mysql.knowledge;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 知识文档版本 Mapper（K02）。 */
@Mapper
public interface AiKnowledgeDocumentVersionMapper extends BaseMapperX<AiKnowledgeDocumentVersionDO> {

    /** 按文档与版本号定位（版本号在文档内唯一）。 */
    default AiKnowledgeDocumentVersionDO selectByVersionNo(Long documentId, Integer versionNo) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeDocumentVersionDO>()
                .eq(AiKnowledgeDocumentVersionDO::getDocumentId, documentId)
                .eq(AiKnowledgeDocumentVersionDO::getVersionNo, versionNo));
    }

    /** 某文档的版本（编号倒序）。 */
    default List<AiKnowledgeDocumentVersionDO> selectByDocument(Long documentId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeDocumentVersionDO>()
                .eq(AiKnowledgeDocumentVersionDO::getDocumentId, documentId)
                .orderByDesc(AiKnowledgeDocumentVersionDO::getVersionNo));
    }

    /** 分页（按文档过滤，版本号倒序）。 */
    default PageResult<AiKnowledgeDocumentVersionDO> selectPage(PageParam pageParam, Long documentId) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiKnowledgeDocumentVersionDO>()
                        .eqIfPresent(AiKnowledgeDocumentVersionDO::getDocumentId, documentId)
                        .orderByDesc(AiKnowledgeDocumentVersionDO::getVersionNo));
    }

    /** 某索引代的版本（索引退役清理时按代命中）。 */
    default List<AiKnowledgeDocumentVersionDO> selectByGeneration(Long knowledgeBaseId, Integer generationNo) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeDocumentVersionDO>()
                .eq(AiKnowledgeDocumentVersionDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeDocumentVersionDO::getIndexGeneration, generationNo));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiKnowledgeDocumentVersionDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiKnowledgeDocumentVersionDO>()
                        .eq(AiKnowledgeDocumentVersionDO::getId, update.getId())
                        .eq(AiKnowledgeDocumentVersionDO::getVersion, expectedVersion));
    }
}
