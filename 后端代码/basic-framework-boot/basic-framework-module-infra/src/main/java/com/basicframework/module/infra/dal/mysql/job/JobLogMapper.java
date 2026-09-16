package com.basicframework.module.infra.dal.mysql.job;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 任务日志 Mapper
 *
 */
@Mapper
public interface JobLogMapper extends BaseMapperX<JobLogDO> {

    default PageResult<JobLogDO> selectPage(PageParam pageParam, JobLogQuery query) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<JobLogDO>()
                        .eqIfPresent(JobLogDO::getJobId, query.getJobId())
                        .likeIfPresent(JobLogDO::getHandlerName, query.getHandlerName())
                        .geIfPresent(JobLogDO::getBeginTime, query.getBeginTime())
                        .leIfPresent(JobLogDO::getEndTime, query.getEndTime())
                        .eqIfPresent(JobLogDO::getStatus, query.getStatus())
                        .orderByDesc(JobLogDO::getId) // ID 倒序
                );
    }

    /**
     * 物理删除指定时间之前的日志
     *
     * @param createTime 最大时间
     * @param limit      删除条数，防止一次删除太多
     * @return 删除条数
     */
    @Delete("DELETE FROM infra_job_log WHERE create_time < #{createTime} LIMIT #{limit}")
    Integer deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") Integer limit);
}
