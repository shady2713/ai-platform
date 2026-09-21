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
}
