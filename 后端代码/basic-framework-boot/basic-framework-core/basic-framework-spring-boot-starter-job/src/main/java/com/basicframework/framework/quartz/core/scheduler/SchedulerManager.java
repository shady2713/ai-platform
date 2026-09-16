package com.basicframework.framework.quartz.core.scheduler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.NOT_IMPLEMENTED;
import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception0;

import com.basicframework.framework.quartz.core.enums.JobDataKeyEnum;
import com.basicframework.framework.quartz.core.handler.JobHandlerInvoker;
import org.quartz.*;

/**
 * {@link org.quartz.Scheduler} 的管理器，负责创建任务
 *
 * 考虑到实现的简洁性，我们使用 jobHandlerName 作为唯一标识，即：
 * 1. Job 的 {@link JobDetail#getKey()}
 * 2. Trigger 的 {@link Trigger#getKey()}
 *
 * 另外，jobHandlerName 对应到 Spring Bean 的名字，直接调用。
 *
 * <p>持久化调度采用“停机期间错过的 cron 不补跑”策略，避免应用重启后集中补偿造成重复副作用。
 * {@link JobHandlerInvoker} 只保证同一任务不并发；业务处理器仍须设计为可重入或幂等，因为 Quartz
 * 无法与任意外部副作用组成 exactly-once 事务。
 *
 */
public class SchedulerManager {

    private final Scheduler scheduler;

    public SchedulerManager(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 添加 Job 到 Quartz 中
     *
     * @param jobId 任务编号
     * @param jobHandlerName 任务处理器的名字
     * @param jobHandlerParam 任务处理器的参数
     * @param cronExpression CRON 表达式
     * @param retryCount 重试次数
     * @param retryInterval 重试间隔
     * @throws SchedulerException 添加异常
     */
    public void addJob(
            Long jobId,
            String jobHandlerName,
            String jobHandlerParam,
            String cronExpression,
            Integer retryCount,
            Integer retryInterval)
            throws SchedulerException {
        validateScheduler();
        // 创建 JobDetail 对象
        JobDetail jobDetail = JobBuilder.newJob(JobHandlerInvoker.class)
                .usingJobData(JobDataKeyEnum.JOB_ID.name(), jobId)
                .usingJobData(JobDataKeyEnum.JOB_HANDLER_NAME.name(), jobHandlerName)
                .withIdentity(jobHandlerName)
                .requestRecovery(false)
                .build();
        // 创建 Trigger 对象
        Trigger trigger = this.buildTrigger(jobHandlerName, jobHandlerParam, cronExpression, retryCount, retryInterval);
        // 新增 Job 调度
        scheduler.scheduleJob(jobDetail, trigger);
    }

    /**
     * 更新 Job 到 Quartz
     *
     * @param jobHandlerName 任务处理器的名字
     * @param jobHandlerParam 任务处理器的参数
     * @param cronExpression CRON 表达式
     * @param retryCount 重试次数
     * @param retryInterval 重试间隔
     * @throws SchedulerException 更新异常
     */
    public void updateJob(
            String jobHandlerName,
            String jobHandlerParam,
            String cronExpression,
            Integer retryCount,
            Integer retryInterval)
            throws SchedulerException {
        validateScheduler();
        // 创建新 Trigger 对象
        Trigger newTrigger =
                this.buildTrigger(jobHandlerName, jobHandlerParam, cronExpression, retryCount, retryInterval);
        // 修改调度
        scheduler.rescheduleJob(new TriggerKey(jobHandlerName), newTrigger);
    }

    /**
     * 删除 Quartz 中的 Job
     *
     * @param jobHandlerName 任务处理器的名字
     * @throws SchedulerException 删除异常
     */
    public void deleteJob(String jobHandlerName) throws SchedulerException {
        validateScheduler();
        // 暂停 Trigger 对象
        scheduler.pauseTrigger(new TriggerKey(jobHandlerName));
        // 取消并删除 Job 调度
        scheduler.unscheduleJob(new TriggerKey(jobHandlerName));
        scheduler.deleteJob(new JobKey(jobHandlerName));
    }

    /**
     * 暂停 Quartz 中的 Job
     *
     * @param jobHandlerName 任务处理器的名字
     * @throws SchedulerException 暂停异常
     */
    public void pauseJob(String jobHandlerName) throws SchedulerException {
        validateScheduler();
        scheduler.pauseJob(new JobKey(jobHandlerName));
    }

    /**
     * 启动 Quartz 中的 Job
     *
     * @param jobHandlerName 任务处理器的名字
     * @throws SchedulerException 启动异常
     */
    public void resumeJob(String jobHandlerName) throws SchedulerException {
        validateScheduler();
        scheduler.resumeJob(new JobKey(jobHandlerName));
        scheduler.resumeTrigger(new TriggerKey(jobHandlerName));
    }

    /**
     * 判断 Quartz 中是否存在指定 Job。
     *
     * @param jobHandlerName 任务处理器的名字
     * @return 是否存在
     * @throws SchedulerException 查询异常
     */
    public boolean jobExists(String jobHandlerName) throws SchedulerException {
        validateScheduler();
        return scheduler.checkExists(new JobKey(jobHandlerName));
    }

    /**
     * 立即触发一次 Quartz 中的 Job
     *
     * @param jobId 任务编号
     * @param jobHandlerName 任务处理器的名字
     * @param jobHandlerParam 任务处理器的参数
     * @throws SchedulerException 触发异常
     */
    public void triggerJob(Long jobId, String jobHandlerName, String jobHandlerParam) throws SchedulerException {
        validateScheduler();
        // 触发任务
        JobDataMap data = new JobDataMap(); // 无需重试，所以不设置 retryCount 和 retryInterval
        data.put(JobDataKeyEnum.JOB_ID.name(), jobId);
        data.put(JobDataKeyEnum.JOB_HANDLER_NAME.name(), jobHandlerName);
        data.put(JobDataKeyEnum.JOB_HANDLER_PARAM.name(), jobHandlerParam);
        scheduler.triggerJob(new JobKey(jobHandlerName), data);
    }

    private Trigger buildTrigger(
            String jobHandlerName,
            String jobHandlerParam,
            String cronExpression,
            Integer retryCount,
            Integer retryInterval) {
        return TriggerBuilder.newTrigger()
                .withIdentity(jobHandlerName)
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(cronExpression).withMisfireHandlingInstructionDoNothing())
                .usingJobData(JobDataKeyEnum.JOB_HANDLER_PARAM.name(), jobHandlerParam)
                .usingJobData(JobDataKeyEnum.JOB_RETRY_COUNT.name(), retryCount)
                .usingJobData(JobDataKeyEnum.JOB_RETRY_INTERVAL.name(), retryInterval)
                .build();
    }

    private void validateScheduler() {
        if (scheduler == null) {
            throw exception0(NOT_IMPLEMENTED.getCode(), "定时任务已禁用：Quartz Scheduler 未配置");
        }
    }
}
