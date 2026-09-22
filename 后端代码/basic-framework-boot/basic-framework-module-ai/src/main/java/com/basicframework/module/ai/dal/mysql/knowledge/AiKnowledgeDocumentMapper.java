package com.basicframework.module.ai.dal.mysql.knowledge;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 知识文档 Mapper（K02）。 */
@Mapper
public interface AiKnowledgeDocumentMapper extends BaseMapperX<AiKnowledgeDocumentDO> {

    /** 按 sourceKey 定位（库内唯一；幂等入库的入口）。 */
    default AiKnowledgeDocumentDO selectBySourceKey(Long knowledgeBaseId, String sourceKey) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeDocumentDO>()
                .eq(AiKnowledgeDocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeDocumentDO::getSourceKey, sourceKey));
    }

    /** 分页（按编号倒序；可按库与状态过滤）。 */
    default PageResult<AiKnowledgeDocumentDO> selectPage(PageParam pageParam, Long knowledgeBaseId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiKnowledgeDocumentDO>()
                        .eqIfPresent(AiKnowledgeDocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                        .eqIfPresent(AiKnowledgeDocumentDO::getStatus, status)
                        .orderByDesc(AiKnowledgeDocumentDO::getId));
    }

    /** 某知识库的存活文档（知识库删除前的引用检查）。 */
    default List<AiKnowledgeDocumentDO> selectByKnowledgeBase(Long knowledgeBaseId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeDocumentDO>()
                .eq(AiKnowledgeDocumentDO::getKnowledgeBaseId, knowledgeBaseId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiKnowledgeDocumentDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiKnowledgeDocumentDO>()
                        .eq(AiKnowledgeDocumentDO::getId, update.getId())
                        .eq(AiKnowledgeDocumentDO::getVersion, expectedVersion));
    }
}
