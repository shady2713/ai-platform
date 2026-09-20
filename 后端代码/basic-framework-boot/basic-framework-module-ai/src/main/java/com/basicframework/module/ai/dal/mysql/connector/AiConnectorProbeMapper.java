package com.basicframework.module.ai.dal.mysql.connector;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 连接器探测 Mapper（D01）：按连接器倒序查询（最新在前）。 */
@Mapper
public interface AiConnectorProbeMapper extends BaseMapperX<AiConnectorProbeDO> {

    /** 某连接器的探测记录（最新在前）。 */
    default List<AiConnectorProbeDO> selectByConnector(Long connectorId) {
        return selectList(new LambdaQueryWrapperX<AiConnectorProbeDO>()
                .eq(AiConnectorProbeDO::getConnectorId, connectorId)
                .orderByDesc(AiConnectorProbeDO::getId));
    }
}
