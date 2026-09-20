package com.basicframework.module.ai.dal.mysql.connector;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import org.apache.ibatis.annotations.Mapper;

/** 连接器 Mapper（D01）。 */
@Mapper
public interface AiConnectorMapper extends BaseMapperX<AiConnectorDO> {

    /** 按标识定位（全局唯一）。 */
    default AiConnectorDO selectByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AiConnectorDO>().eq(AiConnectorDO::getCode, code));
    }

    /** 分页（按编号倒序，可按类型/状态过滤）。 */
    default PageResult<AiConnectorDO> selectPage(PageParam pageParam, String connectorType, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiConnectorDO>()
                        .eqIfPresent(AiConnectorDO::getConnectorType, connectorType)
                        .eqIfPresent(AiConnectorDO::getStatus, status)
                        .orderByDesc(AiConnectorDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiConnectorDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiConnectorDO>()
                        .eq(AiConnectorDO::getId, update.getId())
                        .eq(AiConnectorDO::getVersion, expectedVersion));
    }
}
