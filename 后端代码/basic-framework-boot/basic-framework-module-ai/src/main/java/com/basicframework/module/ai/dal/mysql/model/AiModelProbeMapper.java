package com.basicframework.module.ai.dal.mysql.model;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.ai.dal.dataobject.model.AiModelProbeDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiModelProbeMapper extends BaseMapperX<AiModelProbeDO> {

    /**
     * 每种探测类型的最新一条结论。
     *
     * <p>原生 SQL 不走 MyBatis-Plus 的逻辑删除拦截，因此显式带上 {@code deleted = 0}。
     */
    @Select("SELECT p.* FROM ai_model_probe p"
            + " JOIN (SELECT probe_kind, MAX(id) AS max_id FROM ai_model_probe"
            + " WHERE endpoint_id = #{endpointId} AND deleted = 0 GROUP BY probe_kind) latest"
            + " ON p.id = latest.max_id"
            + " WHERE p.deleted = 0"
            + " ORDER BY p.probe_kind")
    List<AiModelProbeDO> selectLatestPerKind(@Param("endpointId") Long endpointId);
}
