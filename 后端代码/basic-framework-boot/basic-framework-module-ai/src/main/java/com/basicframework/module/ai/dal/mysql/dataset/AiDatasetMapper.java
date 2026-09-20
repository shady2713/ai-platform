package com.basicframework.module.ai.dal.mysql.dataset;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 数据集 Mapper（D04）。 */
@Mapper
public interface AiDatasetMapper extends BaseMapperX<AiDatasetDO> {

    /** 按标识定位（全局唯一）。 */
    default AiDatasetDO selectByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AiDatasetDO>().eq(AiDatasetDO::getCode, code));
    }

    /** 分页（按编号倒序，可按连接器/状态过滤）。 */
    default PageResult<AiDatasetDO> selectPage(PageParam pageParam, Long connectorId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiDatasetDO>()
                        .eqIfPresent(AiDatasetDO::getConnectorId, connectorId)
                        .eqIfPresent(AiDatasetDO::getStatus, status)
                        .orderByDesc(AiDatasetDO::getId));
    }

    /** 按连接器列出数据集（连接器删除前的引用检查）。 */
    default List<AiDatasetDO> selectByConnector(Long connectorId) {
        return selectList(new LambdaQueryWrapperX<AiDatasetDO>().eq(AiDatasetDO::getConnectorId, connectorId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiDatasetDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiDatasetDO>()
                        .eq(AiDatasetDO::getId, update.getId())
                        .eq(AiDatasetDO::getVersion, expectedVersion));
    }
}
