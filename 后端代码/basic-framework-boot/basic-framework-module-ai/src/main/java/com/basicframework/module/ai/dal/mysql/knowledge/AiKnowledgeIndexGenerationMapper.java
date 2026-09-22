package com.basicframework.module.ai.dal.mysql.knowledge;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 知识索引代 Mapper（K02）。 */
@Mapper
public interface AiKnowledgeIndexGenerationMapper extends BaseMapperX<AiKnowledgeIndexGenerationDO> {

    /** 按库与代序号定位（库内唯一）。 */
    default AiKnowledgeIndexGenerationDO selectByGenerationNo(Long knowledgeBaseId, Integer generationNo) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeIndexGenerationDO>()
                .eq(AiKnowledgeIndexGenerationDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeIndexGenerationDO::getGenerationNo, generationNo));
    }

    /** 当前生效的一代（同一知识库最多一个 ACTIVE）。 */
    default AiKnowledgeIndexGenerationDO selectActive(Long knowledgeBaseId) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeIndexGenerationDO>()
                .eq(AiKnowledgeIndexGenerationDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeIndexGenerationDO::getStatus, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE));
    }

    /** 构建中的一代（同一知识库最多一个 BUILDING，避免并发换代互相覆盖）。 */
    default AiKnowledgeIndexGenerationDO selectBuilding(Long knowledgeBaseId) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeIndexGenerationDO>()
                .eq(AiKnowledgeIndexGenerationDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeIndexGenerationDO::getStatus, AiKnowledgeIndexGenerationDO.STATUS_BUILDING));
    }

    /** 按库列出一代（编号倒序）。 */
    default List<AiKnowledgeIndexGenerationDO> selectByKnowledgeBase(Long knowledgeBaseId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeIndexGenerationDO>()
                .eq(AiKnowledgeIndexGenerationDO::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(AiKnowledgeIndexGenerationDO::getGenerationNo));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiKnowledgeIndexGenerationDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiKnowledgeIndexGenerationDO>()
                        .eq(AiKnowledgeIndexGenerationDO::getId, update.getId())
                        .eq(AiKnowledgeIndexGenerationDO::getVersion, expectedVersion));
    }
}
