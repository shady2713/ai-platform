package com.basicframework.module.infra.dal.mysql.logger;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.infra.dal.dataobject.logger.ApiErrorLogDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * API 错误日志 Mapper（仅保留基础写入和清理方法）
 */
@Mapper
public interface ApiErrorLogMapper extends BaseMapperX<ApiErrorLogDO> {

    /**
     * 物理删除指定时间之前的日志
     */
    @Delete("DELETE FROM infra_api_error_log WHERE create_time < #{createTime} LIMIT #{limit}")
    Integer deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") Integer limit);
}
