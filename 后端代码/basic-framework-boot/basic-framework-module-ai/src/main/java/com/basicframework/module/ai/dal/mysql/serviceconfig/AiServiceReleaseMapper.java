package com.basicframework.module.ai.dal.mysql.serviceconfig;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiServiceReleaseMapper extends BaseMapperX<AiServiceReleaseDO> {

    /** 服务的发布版本（倒序）。 */
    default List<AiServiceReleaseDO> selectByService(Long serviceId) {
        return selectList(new LambdaQueryWrapperX<AiServiceReleaseDO>()
                .eq(AiServiceReleaseDO::getServiceId, serviceId)
                .orderByDesc(AiServiceReleaseDO::getReleaseVersion));
    }

    /** 当前生效版本（同一服务最多一条 ACTIVE）。 */
    default AiServiceReleaseDO selectActiveByService(Long serviceId) {
        return selectOne(new LambdaQueryWrapperX<AiServiceReleaseDO>()
                .eq(AiServiceReleaseDO::getServiceId, serviceId)
                .eq(AiServiceReleaseDO::getStatus, AiServiceReleaseDO.STATUS_ACTIVE));
    }

    /** 已用的最大发布版本号（无历史时为 null）。 */
    default Integer selectMaxReleaseVersion(Long serviceId) {
        AiServiceReleaseDO latest = selectOne(new LambdaQueryWrapperX<AiServiceReleaseDO>()
                .eq(AiServiceReleaseDO::getServiceId, serviceId)
                .orderByDesc(AiServiceReleaseDO::getReleaseVersion)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getReleaseVersion();
    }

    /**
     * 退役当前生效版本（别名切换的第一步，仅命中 ACTIVE 行）。
     *
     * <p>退役只推进状态与乐观锁版本，**不触碰内容列**：历史版本因此保持只读。
     */
    default int retireActive(Long serviceId) {
        return update(
                null,
                new LambdaUpdateWrapper<AiServiceReleaseDO>()
                        .eq(AiServiceReleaseDO::getServiceId, serviceId)
                        .eq(AiServiceReleaseDO::getStatus, AiServiceReleaseDO.STATUS_ACTIVE)
                        .set(AiServiceReleaseDO::getStatus, AiServiceReleaseDO.STATUS_RETIRED)
                        .setSql("version = version + 1"));
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新。 */
    default int updateWithVersion(AiServiceReleaseDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiServiceReleaseDO>()
                        .eq(AiServiceReleaseDO::getId, update.getId())
                        .eq(AiServiceReleaseDO::getVersion, expectedVersion));
    }
}
