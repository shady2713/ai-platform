package com.basicframework.module.ai.dal.mysql.media;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 媒体产物 Mapper（X03）：按任务列出（序号升序，展示顺序就是落库顺序）。 */
@Mapper
public interface AiMediaAssetMapper extends BaseMapperX<AiMediaAssetDO> {

    /** 某任务的产物（序号升序）。 */
    default List<AiMediaAssetDO> selectByTask(Long taskId) {
        return selectList(new LambdaQueryWrapperX<AiMediaAssetDO>()
                .eq(AiMediaAssetDO::getTaskId, taskId)
                .orderByAsc(AiMediaAssetDO::getOrdinal));
    }

    /** 某任务的产物数量（幂等重放时核对既有事实）。 */
    default long countByTask(Long taskId) {
        return selectCount(new LambdaQueryWrapperX<AiMediaAssetDO>().eq(AiMediaAssetDO::getTaskId, taskId));
    }
}
