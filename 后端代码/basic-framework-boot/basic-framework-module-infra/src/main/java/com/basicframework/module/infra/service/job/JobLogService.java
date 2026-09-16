package com.basicframework.module.infra.service.job;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.quartz.core.service.JobLogFrameworkService;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import com.basicframework.module.infra.dal.mysql.job.JobLogQuery;

/**
 * Job 日志 Service 接口
 *
 */
public interface JobLogService extends JobLogFrameworkService {

    /**
     * 获得定时任务
     *
     * @param id 编号
     * @return 定时任务
     */
    JobLogDO getJobLog(Long id);

    /**
     * 获得定时任务分页
     *
     * @param pageParam 分页参数
     * @param query     查询条件
     * @return 定时任务分页
     */
    PageResult<JobLogDO> getJobLogPage(PageParam pageParam, JobLogQuery query);

    /**
     * 清理 exceedDay 天前的任务日志
     *
     * @param exceedDay   超过多少天就进行清理
     * @param deleteLimit 清理的间隔条数
     */
    Integer cleanJobLog(Integer exceedDay, Integer deleteLimit, Integer maxBatches);
}
