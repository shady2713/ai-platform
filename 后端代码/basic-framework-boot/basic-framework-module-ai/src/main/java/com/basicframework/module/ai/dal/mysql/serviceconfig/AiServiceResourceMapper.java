package com.basicframework.module.ai.dal.mysql.serviceconfig;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiServiceResourceMapper extends BaseMapperX<AiServiceResourceDO> {

    /** 服务草稿的绑定（releaseId 为空）。 */
    default List<AiServiceResourceDO> selectDraftBindings(Long serviceId) {
        return selectList(new LambdaQueryWrapperX<AiServiceResourceDO>()
                .eq(AiServiceResourceDO::getServiceId, serviceId)
                .isNull(AiServiceResourceDO::getReleaseId)
                .eq(AiServiceResourceDO::getStatus, AiServiceResourceDO.STATUS_ACTIVE)
                .orderByAsc(AiServiceResourceDO::getId));
    }

    /** 按服务 + 资源定位现有绑定（含草稿与各版本）。 */
    default AiServiceResourceDO selectBinding(Long serviceId, Long releaseId, String resourceType, String resourceKey) {
        return selectOne(new LambdaQueryWrapperX<AiServiceResourceDO>()
                .eq(AiServiceResourceDO::getServiceId, serviceId)
                .eq(AiServiceResourceDO::getResourceType, resourceType)
                .eq(AiServiceResourceDO::getResourceKey, resourceKey)
                .eqIfPresent(AiServiceResourceDO::getReleaseId, releaseId)
                .eq(AiServiceResourceDO::getStatus, AiServiceResourceDO.STATUS_ACTIVE));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiServiceResourceDO update, Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiServiceResourceDO>()
                        .eq(AiServiceResourceDO::getId, update.getId())
                        .eq(AiServiceResourceDO::getVersion, expectedVersion));
    }
}
