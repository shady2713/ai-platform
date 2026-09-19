package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiRetentionCleanupResultDTO;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 保留期清理 Job（O06）：按批次清理终态运行的过期事件/任务/运行与已关闭会话的过期消息/会话。
 *
 * <p>幂等且安全：清理只处理"终态且超过保留期"的行，且每一步都带"仍被引用则不删"的守卫，
 * 重复执行不会删掉仍有用引用；批次与批次数都有上限，不会一次扫全表。
 * 审计信息随 Job 执行日志记录（infra_job_log），摘要字符串给出各类别实际清理条数。
 */
@Slf4j
@Component
public class AiRetentionCleanupJob implements JobHandler {

    private final AiTaskService taskService;

    private final Duration retention;

    private final int batchSize;

    private final int maxBatches;

    /** 构造器注入：配置值与依赖都通过构造器传入，不使用字段注入。 */
    public AiRetentionCleanupJob(
            AiTaskService taskService,
            @Value("${basic-framework.ai.retention.window:P30D}") Duration retention,
            @Value("${basic-framework.ai.retention.batch-size:200}") int batchSize,
            @Value("${basic-framework.ai.retention.max-batches:10}") int maxBatches) {
        this.taskService = taskService;
        this.retention = retention;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    @Override
    public String execute(String param) {
        AiRetentionCleanupResultDTO result = taskService.cleanup(retention, batchSize, maxBatches);
        log.info(
                "[execute][清理运行事件 ({}) 条、任务 ({}) 条、幂等记录 ({}) 条、运行 ({}) 条、消息 ({}) 条、会话 ({}) 条]",
                result.getEvents(),
                result.getTasks(),
                result.getIdempotency(),
                result.getRuns(),
                result.getMessages(),
                result.getConversations());
        return String.format(
                "清理事件 %s、任务 %s、运行 %s、消息 %s、会话 %s",
                result.getEvents(),
                result.getTasks(),
                result.getRuns(),
                result.getMessages(),
                result.getConversations());
    }
}
