package com.basicframework.module.infra.dal.mysql.job;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.job.JobDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务 Mapper
 *
 */
@Mapper
public interface JobMapper extends BaseMapperX<JobDO> {

    default JobDO selectByHandlerName(String handlerName) {
        return selectOne(JobDO::getHandlerName, handlerName);
    }

    default PageResult<JobDO> selectPage(PageParam pageParam, String name, Integer status, String handlerName) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<JobDO>()
                        .likeIfPresent(JobDO::getName, name)
                        .eqIfPresent(JobDO::getStatus, status)
                        .likeIfPresent(JobDO::getHandlerName, handlerName)
                        .orderByDesc(JobDO::getId));
    }
}
