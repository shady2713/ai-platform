package com.basicframework.module.ai.dal.mysql.action;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 工具动作 Mapper（D09）。 */
@Mapper
public interface AiToolActionMapper extends BaseMapperX<AiToolActionDO> {

    /** 按运行列出动作（编号倒序）。 */
    default List<AiToolActionDO> selectByRun(Long runId) {
        return selectList(new LambdaQueryWrapperX<AiToolActionDO>()
                .eq(AiToolActionDO::getRunId, runId)
                .orderByDesc(AiToolActionDO::getId));
    }

    /** 分页（按主体三元组过滤；越权与不存在同语义）。 */
    default PageResult<AiToolActionDO> selectPage(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId, Long runId) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiToolActionDO>()
                        .eq(AiToolActionDO::getApplicationId, applicationId)
                        .eq(AiToolActionDO::getSubjectType, subjectType)
                        .eq(AiToolActionDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                        .eqIfPresent(AiToolActionDO::getRunId, runId)
                        .orderByDesc(AiToolActionDO::getId));
    }

    /** 乐观锁 CAS（状态机转移的唯一方式）。 */
    default int updateWithVersion(AiToolActionDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiToolActionDO>()
                        .eq(AiToolActionDO::getId, update.getId())
                        .eq(AiToolActionDO::getVersion, expectedVersion));
    }

    /**
     * 按业务幂等键找"可能已产生副作用"的动作（X06）：同一工具 + 同一业务键只允许一条。
     *
     * <p>已确定无副作用的终态（CANCELLED/EXPIRED/FAILED）与 {@code uk_ai_tool_action_business}
     * 同一口径地排除：业务明确失败后可以重新发起，而已执行/未定的动作永远占住这个键。
     */
    default AiToolActionDO selectByBusinessKey(Long toolId, String idempotencyKey) {
        if (toolId == null || idempotencyKey == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiToolActionDO>()
                .eq(AiToolActionDO::getToolId, toolId)
                .eq(AiToolActionDO::getIdempotencyKey, idempotencyKey)
                .notIn(
                        AiToolActionDO::getStatus,
                        AiToolActionDO.STATUS_CANCELLED,
                        AiToolActionDO.STATUS_EXPIRED,
                        AiToolActionDO.STATUS_FAILED)
                .orderByAsc(AiToolActionDO::getId)
                .last("limit 1"));
    }
}
