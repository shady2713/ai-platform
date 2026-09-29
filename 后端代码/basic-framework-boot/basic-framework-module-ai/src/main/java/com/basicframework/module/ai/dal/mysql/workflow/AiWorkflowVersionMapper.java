package com.basicframework.module.ai.dal.mysql.workflow;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 流程版本 Mapper（X08）：版本序号唯一、单开草稿、手工乐观锁 CAS。 */
@Mapper
public interface AiWorkflowVersionMapper extends BaseMapperX<AiWorkflowVersionDO> {

    /** 当前打开的草稿（同一流程最多一个；废弃后不再返回）。 */
    default AiWorkflowVersionDO selectOpenDraft(Long workflowId) {
        return selectOne(new LambdaQueryWrapperX<AiWorkflowVersionDO>()
                .eq(AiWorkflowVersionDO::getWorkflowId, workflowId)
                .eq(AiWorkflowVersionDO::getStatus, AiWorkflowVersionDO.STATUS_DRAFT));
    }

    /** 最新已发布版本（按版本序号倒序的第一个；运行受理固定到它）。 */
    default Optional<AiWorkflowVersionDO> selectLatestPublished(Long workflowId) {
        return selectList(new LambdaQueryWrapperX<AiWorkflowVersionDO>()
                        .eq(AiWorkflowVersionDO::getWorkflowId, workflowId)
                        .eq(AiWorkflowVersionDO::getStatus, AiWorkflowVersionDO.STATUS_PUBLISHED)
                        .orderByDesc(AiWorkflowVersionDO::getVersionNo))
                .stream()
                .findFirst();
    }

    /** 分页（按流程过滤，状态精确；按版本序号倒序）。 */
    default PageResult<AiWorkflowVersionDO> selectPage(PageParam pageParam, Long workflowId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiWorkflowVersionDO>()
                        .eqIfPresent(AiWorkflowVersionDO::getWorkflowId, workflowId)
                        .eqIfPresent(AiWorkflowVersionDO::getStatus, status)
                        .orderByDesc(AiWorkflowVersionDO::getVersionNo));
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新（发布与废弃的并发收敛点）。 */
    default int updateWithVersion(AiWorkflowVersionDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiWorkflowVersionDO>()
                        .eq(AiWorkflowVersionDO::getId, update.getId())
                        .eq(AiWorkflowVersionDO::getVersion, expectedVersion));
    }
}
