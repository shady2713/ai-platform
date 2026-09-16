package com.basicframework.module.infra.dal.mysql.logger;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * API 访问日志 Mapper（仅保留基础写入和清理方法）
 */
@Mapper
public interface ApiAccessLogMapper extends BaseMapperX<ApiAccessLogDO> {

    /**
     * 物理删除指定时间之前的日志
     */
    @Delete("DELETE FROM infra_api_access_log WHERE create_time < #{createTime} LIMIT #{limit}")
    Integer deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") Integer limit);
}
