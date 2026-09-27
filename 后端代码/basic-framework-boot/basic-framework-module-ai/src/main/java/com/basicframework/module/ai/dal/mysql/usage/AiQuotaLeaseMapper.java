package com.basicframework.module.ai.dal.mysql.usage;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.usage.AiQuotaLeaseDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
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

    /**
     * 当前有效占位数 + 对命中行加锁（Q07 AT-059 修复的判定读）。
     *
     * <p>为什么必须加锁读：REPEATABLE READ 下普通读复用事务首个读建立的快照，而申请流程在取应用行锁之前
     * 已读过占位键，若此处用普通读，后到者会读到"还有空位"的旧快照，即使持有应用行锁也会超发。
     */
    default long countActiveForUpdate(Long applicationId, LocalDateTime now) {
        return selectCount(new LambdaQueryWrapperX<AiQuotaLeaseDO>()
                .eq(AiQuotaLeaseDO::getApplicationId, applicationId)
                .eq(AiQuotaLeaseDO::getState, AiQuotaLeaseDO.STATE_ACTIVE)
                .gt(AiQuotaLeaseDO::getLeaseUntil, now)
                .last("FOR UPDATE"));
    }

    /**
     * 全局闸门行（Q07 AT-059 修复的兜底锁点）：仅当应用行不存在（未注册的应用标识）时使用。
     *
     * <p>该行状态为 RELEASED、租约到期时间在远期，不参与 {@link #countActive} 的占用口径，
     * 只用于给"无应用行可锁"的退化路径提供一个互斥点（`ON DUPLICATE KEY UPDATE` 会持有该行 X 锁到事务结束）。
     */
    @Insert("INSERT INTO ai_quota_lease (lease_key, application_id, service_id, invocation_id, holder_ref, state,"
            + " lease_until, version, creator, create_time, updater, update_time)"
            + " VALUES ('#quota-gate#', 0, NULL, '#quota-gate#', NULL, 'RELEASED', '9999-12-31 23:59:59', 0, '',"
            + " NOW(), '', NOW()) ON DUPLICATE KEY UPDATE id = id")
    int lockQuotaGate();

    /** 乐观锁 CAS（续租/释放）。 */
    default int updateWithVersion(AiQuotaLeaseDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiQuotaLeaseDO>()
                        .eq(AiQuotaLeaseDO::getId, update.getId())
                        .eq(AiQuotaLeaseDO::getVersion, expectedVersion));
    }
}
