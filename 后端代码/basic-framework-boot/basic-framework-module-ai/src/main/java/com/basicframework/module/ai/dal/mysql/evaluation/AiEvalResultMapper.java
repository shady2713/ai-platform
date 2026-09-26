package com.basicframework.module.ai.dal.mysql.evaluation;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 评测结果 Mapper（Q04）：事实行，按运行读取；人工复核只改复核字段。 */
@Mapper
public interface AiEvalResultMapper extends BaseMapperX<AiEvalResultDO> {

    /** 运行内全部结果（按编号升序＝冻结的样例顺序）。 */
    default List<AiEvalResultDO> selectByRun(Long runId) {
        return selectList(new LambdaQueryWrapperX<AiEvalResultDO>()
                .eq(AiEvalResultDO::getRunId, runId)
                .orderByAsc(AiEvalResultDO::getId));
    }

    /** 结果分页（可按判定过滤）。 */
    default PageResult<AiEvalResultDO> selectPage(PageParam pageParam, Long runId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiEvalResultDO>()
                        .eqIfPresent(AiEvalResultDO::getRunId, runId)
                        .eqIfPresent(AiEvalResultDO::getStatus, status)
                        .orderByAsc(AiEvalResultDO::getId));
    }

    /** 运行内按样例标识定位（唯一键）。 */
    default AiEvalResultDO selectByRunAndCase(Long runId, String caseKey) {
        return selectOne(new LambdaQueryWrapperX<AiEvalResultDO>()
                .eq(AiEvalResultDO::getRunId, runId)
                .eq(AiEvalResultDO::getCaseKey, caseKey));
    }
}
