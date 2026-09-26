package com.basicframework.module.ai.dal.mysql.evaluation;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import org.apache.ibatis.annotations.Mapper;

/** 评测运行 Mapper（Q04）：事实行，只插入与一次性收敛终态。 */
@Mapper
public interface AiEvalRunMapper extends BaseMapperX<AiEvalRunDO> {

    /** 分页（按编号倒序，可按套件过滤）。 */
    default PageResult<AiEvalRunDO> selectPage(PageParam pageParam, Long suiteId) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiEvalRunDO>()
                        .eqIfPresent(AiEvalRunDO::getSuiteId, suiteId)
                        .orderByDesc(AiEvalRunDO::getId));
    }
}
