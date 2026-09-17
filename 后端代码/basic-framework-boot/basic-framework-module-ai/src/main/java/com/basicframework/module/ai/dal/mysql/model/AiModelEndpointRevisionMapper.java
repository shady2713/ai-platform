package com.basicframework.module.ai.dal.mysql.model;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiModelEndpointRevisionMapper extends BaseMapperX<AiModelEndpointRevisionDO> {

    default List<AiModelEndpointRevisionDO> selectListByEndpointId(Long endpointId) {
        return selectList(new LambdaQueryWrapperX<AiModelEndpointRevisionDO>()
                .eq(AiModelEndpointRevisionDO::getEndpointId, endpointId)
                .orderByDesc(AiModelEndpointRevisionDO::getRevision));
    }

    default AiModelEndpointRevisionDO selectByEndpointIdAndRevision(Long endpointId, Integer revision) {
        return selectOne(new LambdaQueryWrapperX<AiModelEndpointRevisionDO>()
                .eq(AiModelEndpointRevisionDO::getEndpointId, endpointId)
                .eq(AiModelEndpointRevisionDO::getRevision, revision));
    }

    /** 下一版本号 = 现有版本数 + 1；版本行不可删除，因此该计算稳定。 */
    default int selectNextRevision(Long endpointId) {
        return selectCount(AiModelEndpointRevisionDO::getEndpointId, endpointId).intValue() + 1;
    }
}
