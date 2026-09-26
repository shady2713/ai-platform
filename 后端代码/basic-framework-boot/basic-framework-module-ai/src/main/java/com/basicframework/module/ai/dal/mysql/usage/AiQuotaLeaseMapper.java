package com.basicframework.module.ai.dal.mysql.usage;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.usage.AiQuotaLeaseDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

/** 并发配额占位 Mapper（Q02）。 */
@Mapper
public interface AiQuotaLeaseMapper extends BaseMapperX<AiQuotaLeaseDO> {

    /** 按占位键定位（唯一）。 */
    default AiQuotaLeaseDO selectByLeaseKey(String leaseKey) {
        return selectOne(new LambdaQueryWrapperX<AiQuotaLeaseDO>().eq(AiQuotaLeaseDO::getLeaseKey, leaseKey));
    }

    /** 当前有效占位数（未释放且未到期；到期即视为可回收）。 */
    default long countActive(Long applicationId, LocalDateTime now) {
        return selectCount(new LambdaQueryWrapperX<AiQuotaLeaseDO>()
                .eq(AiQuotaLeaseDO::getApplicationId, applicationId)
                .eq(AiQuotaLeaseDO::getState, AiQuotaLeaseDO.STATE_ACTIVE)
                .gt(AiQuotaLeaseDO::getLeaseUntil, now));
    }

    /** 乐观锁 CAS（续租/释放）。 */
    default int updateWithVersion(AiQuotaLeaseDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiQuotaLeaseDO>()
                        .eq(AiQuotaLeaseDO::getId, update.getId())
                        .eq(AiQuotaLeaseDO::getVersion, expectedVersion));
    }
}
