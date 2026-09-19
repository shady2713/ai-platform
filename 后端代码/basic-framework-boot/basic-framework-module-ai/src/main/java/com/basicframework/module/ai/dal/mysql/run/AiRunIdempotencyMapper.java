package com.basicframework.module.ai.dal.mysql.run;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.run.AiRunIdempotencyDO;
import org.apache.ibatis.annotations.Mapper;

/** 运行幂等 Mapper（O02）：唯一键兜底并发受理。 */
@Mapper
public interface AiRunIdempotencyMapper extends BaseMapperX<AiRunIdempotencyDO> {

    /** 按主体 + 幂等键定位（唯一键的读取侧）。 */
    default AiRunIdempotencyDO selectByKey(
            Long applicationId, String subjectType, String externalUserId, String idempotencyKey) {
        return selectOne(new LambdaQueryWrapperX<AiRunIdempotencyDO>()
                .eq(AiRunIdempotencyDO::getApplicationId, applicationId)
                .eq(AiRunIdempotencyDO::getSubjectType, subjectType)
                .eq(AiRunIdempotencyDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                .eq(AiRunIdempotencyDO::getIdempotencyKey, idempotencyKey));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiRunIdempotencyDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiRunIdempotencyDO>()
                        .eq(AiRunIdempotencyDO::getId, update.getId())
                        .eq(AiRunIdempotencyDO::getVersion, expectedVersion));
    }
}
