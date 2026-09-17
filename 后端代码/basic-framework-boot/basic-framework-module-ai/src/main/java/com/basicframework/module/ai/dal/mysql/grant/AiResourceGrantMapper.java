package com.basicframework.module.ai.dal.mysql.grant;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiResourceGrantMapper extends BaseMapperX<AiResourceGrantDO> {

    /** 按主体 + 资源定位授权（唯一）。 */
    default AiResourceGrantDO selectGrant(
            Long applicationId, String subjectType, String externalUserId, String resourceType, String resourceKey) {
        return selectOne(new LambdaQueryWrapperX<AiResourceGrantDO>()
                .eq(AiResourceGrantDO::getApplicationId, applicationId)
                .eq(AiResourceGrantDO::getSubjectType, subjectType)
                .eq(AiResourceGrantDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                .eq(AiResourceGrantDO::getResourceType, resourceType)
                .eq(AiResourceGrantDO::getResourceKey, resourceKey));
    }

    default PageResult<AiResourceGrantDO> selectPage(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId, String resourceType) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiResourceGrantDO>()
                        .eqIfPresent(AiResourceGrantDO::getApplicationId, applicationId)
                        .eqIfPresent(AiResourceGrantDO::getSubjectType, subjectType)
                        .likeIfPresent(AiResourceGrantDO::getExternalUserId, externalUserId)
                        .eqIfPresent(AiResourceGrantDO::getResourceType, resourceType)
                        .orderByDesc(AiResourceGrantDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiResourceGrantDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiResourceGrantDO>()
                        .eq(AiResourceGrantDO::getId, update.getId())
                        .eq(AiResourceGrantDO::getVersion, expectedVersion));
    }
}
