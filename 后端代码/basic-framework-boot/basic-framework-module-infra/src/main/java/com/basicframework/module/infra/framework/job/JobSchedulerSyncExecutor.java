package com.basicframework.module.infra.framework.job;

import com.baomidou.lock.annotation.Lock4j;
import com.basicframework.module.infra.service.job.JobService;
import lombok.RequiredArgsConstructor;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;

/**
 * 定时任务全量同步的加锁执行器。
 *
 * 独立成 Bean 是为了让 {@link Lock4j} 代理生效（自调用不经过代理）；
 * 多节点并发启动时只有一个节点拿到锁，其余节点由
 * {@link com.basicframework.framework.lock4j.core.DefaultLockFailureStrategy} 抛出异常，调用方按跳过处理。
 */
@Component
@RequiredArgsConstructor
public class JobSchedulerSyncExecutor {

    private final JobService jobService;

    /**
     * 将 infra_job 的期望状态全量同步进 Quartz；复用 {@link JobService#syncJob()} 的原地更新语义，幂等。
     *
     * @throws SchedulerException 同步失败
     */
    @Lock4j(name = "job_scheduler_sync")
    public void syncAllJobs() throws SchedulerException {
        jobService.syncJob();
    }
}
