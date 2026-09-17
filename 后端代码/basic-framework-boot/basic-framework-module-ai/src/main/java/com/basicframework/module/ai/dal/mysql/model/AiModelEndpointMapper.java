package com.basicframework.module.ai.dal.mysql.model;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiModelEndpointMapper extends BaseMapperX<AiModelEndpointDO> {

    default AiModelEndpointDO selectByName(String name) {
        return selectOne(AiModelEndpointDO::getName, name);
    }

    default PageResult<AiModelEndpointDO> selectPage(PageParam pageParam, String name, String provider) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiModelEndpointDO>()
                        .likeIfPresent(AiModelEndpointDO::getName, name)
                        .eqIfPresent(AiModelEndpointDO::getProvider, provider)
                        .orderByDesc(AiModelEndpointDO::getId));
    }

    /**
     * 记录嵌入维度（CAS）：仅当维度仍为空时写入，避免并发首写互相覆盖。
     *
     * @return 受影响行数；0 表示已被其他请求写入
     */
    @org.apache.ibatis.annotations.Update("UPDATE ai_model_endpoint SET embedding_dimension = #{dimension},"
            + " version = version + 1, update_time = NOW()"
            + " WHERE id = #{id} AND embedding_dimension IS NULL AND deleted = 0")
    int updateEmbeddingDimensionIfAbsent(@Param("id") Long id, @Param("dimension") Integer dimension);

    /**
     * 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新（框架未启用乐观锁插件，手工实现）。
     *
     * @return 受影响行数；0 表示版本冲突
     */
    default int updateWithVersion(AiModelEndpointDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiModelEndpointDO>()
                        .eq(AiModelEndpointDO::getId, update.getId())
                        .eq(AiModelEndpointDO::getVersion, expectedVersion));
    }
}
