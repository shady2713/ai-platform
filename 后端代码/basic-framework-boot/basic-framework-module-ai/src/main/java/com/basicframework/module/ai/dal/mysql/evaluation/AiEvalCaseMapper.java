package com.basicframework.module.ai.dal.mysql.evaluation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 评测样例 Mapper（Q04）。 */
@Mapper
public interface AiEvalCaseMapper extends BaseMapperX<AiEvalCaseDO> {

    /** 按套件 + 标识定位（套件内唯一）。 */
    default AiEvalCaseDO selectByCaseKey(Long suiteId, String caseKey) {
        return selectOne(new LambdaQueryWrapperX<AiEvalCaseDO>()
                .eq(AiEvalCaseDO::getSuiteId, suiteId)
                .eq(AiEvalCaseDO::getCaseKey, caseKey));
    }

    /** 套件内全部样例（按标识升序：冻结与执行顺序稳定，报告可复现）。 */
    default List<AiEvalCaseDO> selectBySuite(Long suiteId) {
        return selectList(new LambdaQueryWrapperX<AiEvalCaseDO>()
                .eq(AiEvalCaseDO::getSuiteId, suiteId)
                .orderByAsc(AiEvalCaseDO::getCaseKey));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiEvalCaseDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiEvalCaseDO>()
                        .eq(AiEvalCaseDO::getId, update.getId())
                        .eq(AiEvalCaseDO::getVersion, expectedVersion));
    }
}
