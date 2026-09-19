package com.basicframework.module.ai.dal.mysql.run;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import org.apache.ibatis.annotations.Mapper;

/** 运行任务 Mapper（O02）：建立首任务与查询任务状态；领取与租约由 O03 在此表上扩展。 */
@Mapper
public interface AiRunTaskMapper extends BaseMapperX<AiRunTaskDO> {

    /** 运行的首任务（同类型任务唯一）。 */
    default AiRunTaskDO selectByRunAndKind(Long runId, String taskKind) {
        return selectOne(new LambdaQueryWrapperX<AiRunTaskDO>()
                .eq(AiRunTaskDO::getRunId, runId)
                .eq(AiRunTaskDO::getTaskKind, taskKind));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiRunTaskDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiRunTaskDO>()
                        .eq(AiRunTaskDO::getId, update.getId())
                        .eq(AiRunTaskDO::getVersion, expectedVersion));
    }
}
