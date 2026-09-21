package com.basicframework.module.ai.dal.mysql.tool;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 工具 Mapper（D08）。 */
@Mapper
public interface AiToolMapper extends BaseMapperX<AiToolDO> {

    /** 按标识定位（全局唯一）。 */
    default AiToolDO selectByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AiToolDO>().eq(AiToolDO::getCode, code));
    }

    /** 分页（按编号倒序，可按连接器/状态过滤）。 */
    default PageResult<AiToolDO> selectPage(PageParam pageParam, Long connectorId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiToolDO>()
                        .eqIfPresent(AiToolDO::getConnectorId, connectorId)
                        .eqIfPresent(AiToolDO::getStatus, status)
                        .orderByDesc(AiToolDO::getId));
    }

    /** 按连接器列出工具（连接器删除前的引用检查）。 */
    default List<AiToolDO> selectByConnector(Long connectorId) {
        return selectList(new LambdaQueryWrapperX<AiToolDO>().eq(AiToolDO::getConnectorId, connectorId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiToolDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiToolDO>()
                        .eq(AiToolDO::getId, update.getId())
                        .eq(AiToolDO::getVersion, expectedVersion));
    }
}
