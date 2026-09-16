package com.basicframework.module.infra.framework.job;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 定时任务启动注册器：应用就绪后，把 infra_job 中的任务全量注册进 Quartz。
 *
 * 背景：种子任务（日志清理、数据保留、完整性审计）此前只在数据库中存在，
 * 全新部署后除非管理员手动调用 /infra/job/sync，否则永远不会被调度。
 *
 * 注册失败不阻断应用启动：锁被其它节点持有、Quartz 未启用（local 环境排除 QuartzAutoConfiguration）
 * 或同步异常时只记录日志，可事后通过 /infra/job/sync 手动补偿。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JobStartupRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private final JobSchedulerSyncExecutor jobSchedulerSyncExecutor;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            jobSchedulerSyncExecutor.syncAllJobs();
            log.info("[onApplicationEvent][定时任务已同步至 Quartz]");
        } catch (ServiceException ex) {
            if (GlobalErrorCodeConstants.LOCKED.getCode().equals(ex.getCode())) {
                log.warn("[onApplicationEvent][其它节点正在同步定时任务，本节点跳过，code({})]", ex.getCode());
            } else {
                log.error("[onApplicationEvent][定时任务启动注册失败，请通过 /infra/job/sync 手动补偿，stackTrace({})]", format(ex));
            }
        } catch (Exception ex) {
            log.error("[onApplicationEvent][定时任务启动注册失败，请通过 /infra/job/sync 手动补偿，stackTrace({})]", format(ex));
        }
    }
}
