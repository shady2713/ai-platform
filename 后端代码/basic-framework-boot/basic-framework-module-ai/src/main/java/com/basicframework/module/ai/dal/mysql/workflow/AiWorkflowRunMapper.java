package com.basicframework.module.ai.dal.mysql.workflow;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 流程运行 Mapper（X08）：幂等受理唯一、按流程与状态分页、终态 CAS。 */
@Mapper
public interface AiWorkflowRunMapper extends BaseMapperX<AiWorkflowRunDO> {

    /** 按流程 + 幂等键定位（受理幂等的复用入口）。 */
    default AiWorkflowRunDO selectByIdempotencyKey(Long workflowId, String idempotencyKey) {
        return selectOne(new LambdaQueryWrapperX<AiWorkflowRunDO>()
                .eq(AiWorkflowRunDO::getWorkflowId, workflowId)
                .eq(AiWorkflowRunDO::getIdempotencyKey, idempotencyKey));
    }

    /** 分页（按流程过滤，状态精确；按编号倒序）。 */
    default PageResult<AiWorkflowRunDO> selectPage(PageParam pageParam, Long workflowId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiWorkflowRunDO>()
                        .eqIfPresent(AiWorkflowRunDO::getWorkflowId, workflowId)
                        .eqIfPresent(AiWorkflowRunDO::getStatus, status)
                        .orderByDesc(AiWorkflowRunDO::getId));
    }

    /** 终态 CAS：仅当运行仍为 RUNNING 时写入（并发/重放只有一个赢家）。 */
    default int finishWithCas(AiWorkflowRunDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiWorkflowRunDO>()
                        .eq(AiWorkflowRunDO::getId, update.getId())
                        .eq(AiWorkflowRunDO::getStatus, AiWorkflowRunDO.STATUS_RUNNING)
                        .eq(AiWorkflowRunDO::getVersion, expectedVersion));
    }

    /** 乐观锁 CAS：推进当前节点键等中间状态（单写者，防御并发）。 */
    default int updateWithVersion(AiWorkflowRunDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiWorkflowRunDO>()
                        .eq(AiWorkflowRunDO::getId, update.getId())
                        .eq(AiWorkflowRunDO::getVersion, expectedVersion));
    }
}
