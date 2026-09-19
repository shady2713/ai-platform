package com.basicframework.module.ai.dal.mysql.serviceconfig;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseEvaluationDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiServiceReleaseEvaluationMapper extends BaseMapperX<AiServiceReleaseEvaluationDO> {

    /** 某发布版本的评测记录（按记录倒序，最新在前）。 */
    default List<AiServiceReleaseEvaluationDO> selectByRelease(Long releaseId) {
        return selectList(new LambdaQueryWrapperX<AiServiceReleaseEvaluationDO>()
                .eq(AiServiceReleaseEvaluationDO::getReleaseId, releaseId)
                .orderByDesc(AiServiceReleaseEvaluationDO::getId));
    }

    /**
     * 最新一条评测结论（含未通过）。
     *
     * <p>发布预检查只认这一条：先通过后失败的序列必须以最后一次为准，
     * 不能挑选历史中的"通过"记录来发布。
     */
    default AiServiceReleaseEvaluationDO selectLatest(Long releaseId) {
        return selectOne(new LambdaQueryWrapperX<AiServiceReleaseEvaluationDO>()
                .eq(AiServiceReleaseEvaluationDO::getReleaseId, releaseId)
                .orderByDesc(AiServiceReleaseEvaluationDO::getId)
                .last("LIMIT 1"));
    }
}
