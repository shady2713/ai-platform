package com.basicframework.module.system.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.system.service.retention.SystemDataRetentionService;
import com.basicframework.module.system.service.retention.SystemDataRetentionService.CleanupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 清理 system 模块到期事件记录和用户会话，执行结果由 Quartz 任务日志持久化审计。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemDataRetentionCleanJob implements JobHandler {

    private final SystemDataRetentionService retentionService;

    @Override
    public String execute(String param) {
        CleanupResult result = retentionService.cleanExpiredData();
        String summary = result.summary();
        log.info("[execute][system 到期记录清理完成：{}]", summary);
        return summary;
    }
}
