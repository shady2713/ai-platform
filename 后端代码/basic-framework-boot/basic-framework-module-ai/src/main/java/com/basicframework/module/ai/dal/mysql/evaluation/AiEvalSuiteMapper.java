package com.basicframework.module.ai.dal.mysql.evaluation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import org.apache.ibatis.annotations.Mapper;

/** 评测套件 Mapper（Q04）：配置行，读取按应用过滤，写入带乐观锁。 */
@Mapper
public interface AiEvalSuiteMapper extends BaseMapperX<AiEvalSuiteDO> {

    /** 按应用 + 标识定位（应用内唯一）。 */
    default AiEvalSuiteDO selectByCode(Long applicationId, String code) {
        return selectOne(new LambdaQueryWrapperX<AiEvalSuiteDO>()
                .eq(AiEvalSuiteDO::getApplicationId, applicationId)
                .eq(AiEvalSuiteDO::getCode, code));
    }

    /** 分页（按编号倒序，可按应用与状态过滤）。 */
    default PageResult<AiEvalSuiteDO> selectPage(PageParam pageParam, Long applicationId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiEvalSuiteDO>()
                        .eqIfPresent(AiEvalSuiteDO::getApplicationId, applicationId)
                        .eqIfPresent(AiEvalSuiteDO::getStatus, status)
                        .orderByDesc(AiEvalSuiteDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiEvalSuiteDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiEvalSuiteDO>()
                        .eq(AiEvalSuiteDO::getId, update.getId())
                        .eq(AiEvalSuiteDO::getVersion, expectedVersion));
    }
}
