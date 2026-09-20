package com.basicframework.module.ai.dal.mysql.dataset;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 数据集版本 Mapper（D04）。 */
@Mapper
public interface AiDatasetVersionMapper extends BaseMapperX<AiDatasetVersionDO> {

    /** 数据集内最大版本号（无版本时返回 null）。 */
    default AiDatasetVersionDO selectLatest(Long datasetId) {
        return selectOne(new LambdaQueryWrapperX<AiDatasetVersionDO>()
                .eq(AiDatasetVersionDO::getDatasetId, datasetId)
                .orderByDesc(AiDatasetVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 按数据集列出全部版本（版本号倒序）。 */
    default List<AiDatasetVersionDO> selectByDataset(Long datasetId) {
        return selectList(new LambdaQueryWrapperX<AiDatasetVersionDO>()
                .eq(AiDatasetVersionDO::getDatasetId, datasetId)
                .orderByDesc(AiDatasetVersionDO::getVersionNo));
    }

    /** 分页（按版本号倒序）。 */
    default PageResult<AiDatasetVersionDO> selectPage(PageParam pageParam, Long datasetId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiDatasetVersionDO>()
                        .eqIfPresent(AiDatasetVersionDO::getDatasetId, datasetId)
                        .eqIfPresent(AiDatasetVersionDO::getStatus, status)
                        .orderByDesc(AiDatasetVersionDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiDatasetVersionDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiDatasetVersionDO>()
                        .eq(AiDatasetVersionDO::getId, update.getId())
                        .eq(AiDatasetVersionDO::getVersion, expectedVersion));
    }
}
