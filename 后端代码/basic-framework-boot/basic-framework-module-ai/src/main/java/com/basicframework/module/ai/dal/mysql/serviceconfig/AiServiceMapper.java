package com.basicframework.module.ai.dal.mysql.serviceconfig;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiServiceMapper extends BaseMapperX<AiServiceDO> {

    default AiServiceDO selectByAppAndCode(Long appId, String code) {
        return selectOne(new LambdaQueryWrapperX<AiServiceDO>()
                .eq(AiServiceDO::getAppId, appId)
                .eq(AiServiceDO::getCode, code));
    }

    default PageResult<AiServiceDO> selectPage(PageParam pageParam, Long appId, String code, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiServiceDO>()
                        .eqIfPresent(AiServiceDO::getAppId, appId)
                        .likeIfPresent(AiServiceDO::getCode, code)
                        .eqIfPresent(AiServiceDO::getStatus, status)
                        .orderByDesc(AiServiceDO::getId));
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新。 */
    default int updateWithVersion(AiServiceDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiServiceDO>()
                        .eq(AiServiceDO::getId, update.getId())
                        .eq(AiServiceDO::getVersion, expectedVersion));
    }
}
