package com.basicframework.module.ai.dal.mysql.run;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import org.apache.ibatis.annotations.Mapper;

/** 运行 Mapper（O02）：读取一律带主体条件，越权与不存在同语义。 */
@Mapper
public interface AiRunMapper extends BaseMapperX<AiRunDO> {

    /** 按编号 + 主体定位：不是本人的运行等同于不存在。 */
    default AiRunDO selectOwned(Long id, Long applicationId, String subjectType, String externalUserId) {
        return selectOne(new LambdaQueryWrapperX<AiRunDO>()
                .eq(AiRunDO::getId, id)
                .eq(AiRunDO::getApplicationId, applicationId)
                .eq(AiRunDO::getSubjectType, subjectType)
                .eq(AiRunDO::getExternalUserId, externalUserId == null ? "" : externalUserId));
    }

    /** 当前主体的运行分页（按编号倒序，翻页稳定）。 */
    default PageResult<AiRunDO> selectPageBySubject(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiRunDO>()
                        .eq(AiRunDO::getApplicationId, applicationId)
                        .eq(AiRunDO::getSubjectType, subjectType)
                        .eq(AiRunDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                        .orderByDesc(AiRunDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiRunDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiRunDO>()
                        .eq(AiRunDO::getId, update.getId())
                        .eq(AiRunDO::getVersion, expectedVersion));
    }
}
