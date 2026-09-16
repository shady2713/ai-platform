package com.basicframework.module.infra.service.job;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.job.JobDO;
import java.util.List;
import org.quartz.SchedulerException;

/**
 * 定时任务 Service 接口
 *
 */
public interface JobService {

    /**
     * 创建定时任务
     *
     * @param job 创建信息
     * @return 编号
     */
    Long createJob(JobDO job) throws SchedulerException;

    /**
     * 更新定时任务
     *
     * @param job 更新信息
     */
    void updateJob(JobDO job) throws SchedulerException;

    /**
     * 更新定时任务的状态
     *
     * @param id     任务编号
     * @param status 状态
     */
    void updateJobStatus(Long id, Integer status) throws SchedulerException;

    /**
     * 触发定时任务
     *
     * @param id 任务编号
     */
    void triggerJob(Long id) throws SchedulerException;

    /**
     * 同步定时任务
     *
     * 目的：自己存储的 Job 信息，强制同步到 Quartz 中
     */
    void syncJob() throws SchedulerException;

    /**
     * 删除定时任务
     *
     * @param id 编号
     */
    void deleteJob(Long id) throws SchedulerException;

    /**
     * 批量删除定时任务
     *
     * @param ids 编号列表
     */
    void deleteJobList(List<Long> ids) throws SchedulerException;

    /**
     * 获得定时任务
     *
     * @param id 编号
     * @return 定时任务
     */
    JobDO getJob(Long id);

    /**
     * 获得定时任务分页
     *
     * @param pageParam   分页参数
     * @param name        任务名称，模糊匹配
     * @param status      任务状态
     * @param handlerName 处理器的名字，模糊匹配
     * @return 定时任务分页
     */
    PageResult<JobDO> getJobPage(PageParam pageParam, String name, Integer status, String handlerName);
}
