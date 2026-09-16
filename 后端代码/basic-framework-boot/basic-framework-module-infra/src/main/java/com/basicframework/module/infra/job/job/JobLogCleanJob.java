package com.basicframework.module.infra.job.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.infra.framework.retention.config.InfraDataRetentionProperties;
import com.basicframework.module.infra.service.job.JobLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 物理删除 N 天前的任务日志的 Job
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLogCleanJob implements JobHandler {

    private final JobLogService jobLogService;

    private final InfraDataRetentionProperties retentionProperties;

    @Override
    public String execute(String param) {
        Integer count = jobLogService.cleanJobLog(
                retentionProperties.getJobLogDays(),
                retentionProperties.getBatchSize(),
                retentionProperties.getMaxBatches());
        log.info("[execute][定时执行清理定时任务日志数量 ({}) 个]", count);
        return String.format("定时执行清理定时任务日志数量 %s 个", count);
    }
}
