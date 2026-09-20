package com.basicframework.module.ai.dal.mysql.connector;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 连接器操作 Mapper（D02）。 */
@Mapper
public interface AiConnectorOperationMapper extends BaseMapperX<AiConnectorOperationDO> {

    /** 某连接器的操作（按编号升序）。 */
    default List<AiConnectorOperationDO> selectByConnector(Long connectorId) {
        return selectList(new LambdaQueryWrapperX<AiConnectorOperationDO>()
                .eq(AiConnectorOperationDO::getConnectorId, connectorId)
                .orderByAsc(AiConnectorOperationDO::getId));
    }

    /** 按连接器 + 操作标识定位。 */
    default AiConnectorOperationDO selectByKey(Long connectorId, String operationKey) {
        return selectOne(new LambdaQueryWrapperX<AiConnectorOperationDO>()
                .eq(AiConnectorOperationDO::getConnectorId, connectorId)
                .eq(AiConnectorOperationDO::getOperationKey, operationKey));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiConnectorOperationDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiConnectorOperationDO>()
                        .eq(AiConnectorOperationDO::getId, update.getId())
                        .eq(AiConnectorOperationDO::getVersion, expectedVersion));
    }
}
