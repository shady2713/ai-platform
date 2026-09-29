package com.basicframework.module.ai.dal.mysql.workflow;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 流程运行节点留痕 Mapper（X08）：按运行读节点事实（步骤可视化与失败定位）。 */
@Mapper
public interface AiWorkflowRunNodeMapper extends BaseMapperX<AiWorkflowRunNodeDO> {

    /** 运行的节点留痕（按插入顺序 = 执行顺序）。 */
    default List<AiWorkflowRunNodeDO> selectByRunId(Long runId) {
        return selectList(new LambdaQueryWrapperX<AiWorkflowRunNodeDO>()
                .eq(AiWorkflowRunNodeDO::getRunId, runId)
                .orderByAsc(AiWorkflowRunNodeDO::getId));
    }
}
