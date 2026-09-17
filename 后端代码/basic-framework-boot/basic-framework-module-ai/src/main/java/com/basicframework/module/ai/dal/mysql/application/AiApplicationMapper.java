package com.basicframework.module.ai.dal.mysql.application;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiApplicationMapper extends BaseMapperX<AiApplicationDO> {

    default AiApplicationDO selectByAppCode(String appCode) {
        return selectOne(AiApplicationDO::getAppCode, appCode);
    }

    default PageResult<AiApplicationDO> selectPage(PageParam pageParam, String appCode, Boolean enabled) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiApplicationDO>()
                        .likeIfPresent(AiApplicationDO::getAppCode, appCode)
                        .eqIfPresent(AiApplicationDO::getEnabled, enabled)
                        .orderByDesc(AiApplicationDO::getId));
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新（框架未启用乐观锁插件，手工实现）。 */
    default int updateWithVersion(AiApplicationDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiApplicationDO>()
                        .eq(AiApplicationDO::getId, update.getId())
                        .eq(AiApplicationDO::getVersion, expectedVersion));
    }
}
