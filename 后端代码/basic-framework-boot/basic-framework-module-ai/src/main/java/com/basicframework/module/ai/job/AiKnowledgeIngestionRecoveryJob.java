package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识入库任务恢复 Job（K03）：每分钟把租约过期的任务放回队列（未达上限）或置 FAILED（达上限）。
 *
 * <p>与 O03 的 `AiTaskRecoveryJob` 同一语义：恢复是条件更新，重复执行不影响仍持有有效租约的任务；
 * 进程中断（worker 崩溃、发布重启）后不需要人工干预。
 */
@Slf4j
@Component
public class AiKnowledgeIngestionRecoveryJob implements JobHandler {

    private final AiKnowledgeIngestionService ingestionService;

    private final int retryDelaySeconds;

    private final int batchSize;

    /** 构造器注入：配置值与依赖都通过构造器传入。 */
    public AiKnowledgeIngestionRecoveryJob(
            AiKnowledgeIngestionService ingestionService,
            @Value("${basic-framework.ai.knowledge.ingestion.recovery-delay-seconds:30}") int retryDelaySeconds,
            @Value("${basic-framework.ai.knowledge.ingestion.recovery-batch-size:200}") int batchSize) {
        this.ingestionService = ingestionService;
        this.retryDelaySeconds = retryDelaySeconds;
        this.batchSize = batchSize;
    }

    @Override
    public String execute(String param) {
        int recovered = ingestionService.recoverExpiredLeases(retryDelaySeconds, batchSize);
        if (recovered > 0) {
            log.info("[execute][恢复知识入库任务 ({}) 条]", recovered);
        }
        return String.format("恢复知识入库任务 %s 条", recovered);
    }
}
