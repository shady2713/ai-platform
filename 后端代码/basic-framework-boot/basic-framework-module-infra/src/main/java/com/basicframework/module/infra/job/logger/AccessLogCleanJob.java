package com.basicframework.module.infra.job.logger;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.infra.framework.retention.config.InfraDataRetentionProperties;
import com.basicframework.module.infra.service.logger.ApiAccessLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 物理删除 N 天前的访问日志的 Job
 *
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AccessLogCleanJob implements JobHandler {

    private final ApiAccessLogService apiAccessLogService;

    private final InfraDataRetentionProperties retentionProperties;

    @Override
    public String execute(String param) {
        Integer count = apiAccessLogService.cleanAccessLog(
                retentionProperties.getAccessLogDays(),
                retentionProperties.getBatchSize(),
                retentionProperties.getMaxBatches());
        log.info("[execute][定时执行清理访问日志数量 ({}) 个]", count);
        return String.format("定时执行清理访问日志数量 %s 个", count);
    }
}
