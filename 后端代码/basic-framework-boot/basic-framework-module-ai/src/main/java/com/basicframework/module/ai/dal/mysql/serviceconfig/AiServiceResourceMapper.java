package com.basicframework.module.ai.dal.mysql.serviceconfig;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    /** 草稿绑定定位（仅限 releaseId 为空，避免与发布版本快照互相干扰）。 */
    default AiServiceResourceDO selectDraftBinding(Long serviceId, String resourceType, String resourceKey) {
        return selectOne(new LambdaQueryWrapperX<AiServiceResourceDO>()
                .eq(AiServiceResourceDO::getServiceId, serviceId)
                .eq(AiServiceResourceDO::getResourceType, resourceType)
                .eq(AiServiceResourceDO::getResourceKey, resourceKey)
                .isNull(AiServiceResourceDO::getReleaseId)
                .eq(AiServiceResourceDO::getStatus, AiServiceResourceDO.STATUS_ACTIVE));
    }

    /** 某发布版本的绑定快照（含已解除的，供运行前判定"资源被禁用"）。 */
    default List<AiServiceResourceDO> selectReleaseBindings(Long releaseId) {
        return selectList(new LambdaQueryWrapperX<AiServiceResourceDO>()
                .eq(AiServiceResourceDO::getReleaseId, releaseId)
                .orderByAsc(AiServiceResourceDO::getId));
    }

    /** 某发布版本的**生效中**绑定快照。 */
    default List<AiServiceResourceDO> selectActiveReleaseBindings(Long releaseId) {
        return selectList(new LambdaQueryWrapperX<AiServiceResourceDO>()
                .eq(AiServiceResourceDO::getReleaseId, releaseId)
                .eq(AiServiceResourceDO::getStatus, AiServiceResourceDO.STATUS_ACTIVE)
                .orderByAsc(AiServiceResourceDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiServiceResourceDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiServiceResourceDO>()
                        .eq(AiServiceResourceDO::getId, update.getId())
                        .eq(AiServiceResourceDO::getVersion, expectedVersion));
    }
}
