package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.service.task.AiTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 任务恢复 Job（O03）：每分钟扫描过期租约，把未达重试上限的任务放回待领取队列。
 *
 * <p>幂等：恢复只处理"租约已过期"的行，且用条件更新一次完成（未达上限回 QUEUED、达到上限置 FAILED），
 * 重复执行不会影响仍持有有效租约的任务。进程中断（worker 崩溃、发布重启）后不需要人工干预。
 */
@Slf4j
@Component
public class AiTaskRecoveryJob implements JobHandler {

    private final AiTaskService taskService;

    private final int retryDelaySeconds;

    private final int batchSize;

    /** 构造器注入：配置值与依赖都通过构造器传入，不使用字段注入。 */
    public AiTaskRecoveryJob(
            AiTaskService taskService,
            @Value("${basic-framework.ai.task.recovery.retry-delay-seconds:5}") int retryDelaySeconds,
            @Value("${basic-framework.ai.task.recovery.batch-size:200}") int batchSize) {
        this.taskService = taskService;
        this.retryDelaySeconds = retryDelaySeconds;
        this.batchSize = batchSize;
    }

    @Override
    public String execute(String param) {
        int recovered = taskService.recoverExpiredLeases(retryDelaySeconds, batchSize);
        log.info("[execute][恢复过期租约任务 ({}) 条]", recovered);
        return String.format("恢复过期租约任务 %s 条", recovered);
    }
}
