package com.basicframework.module.ai.dal.mysql.knowledge;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 知识库 Mapper（K02）。 */
@Mapper
public interface AiKnowledgeBaseMapper extends BaseMapperX<AiKnowledgeBaseDO> {

    /** 按标识定位（全局唯一）。 */
    default AiKnowledgeBaseDO selectByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeBaseDO>().eq(AiKnowledgeBaseDO::getCode, code));
    }

    /** 分页（按编号倒序；可按可见性/所属应用/状态过滤）。 */
    default PageResult<AiKnowledgeBaseDO> selectPage(
            PageParam pageParam, String visibility, Long ownerApplicationId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiKnowledgeBaseDO>()
                        .eqIfPresent(AiKnowledgeBaseDO::getVisibility, visibility)
                        .eqIfPresent(AiKnowledgeBaseDO::getOwnerApplicationId, ownerApplicationId)
                        .eqIfPresent(AiKnowledgeBaseDO::getStatus, status)
                        .orderByDesc(AiKnowledgeBaseDO::getId));
    }

    /** 按所属应用列出（应用删除前的引用检查）。 */
    default List<AiKnowledgeBaseDO> selectByOwnerApplication(Long applicationId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeBaseDO>()
                .eq(AiKnowledgeBaseDO::getOwnerApplicationId, applicationId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiKnowledgeBaseDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiKnowledgeBaseDO>()
                        .eq(AiKnowledgeBaseDO::getId, update.getId())
                        .eq(AiKnowledgeBaseDO::getVersion, expectedVersion));
    }
}
