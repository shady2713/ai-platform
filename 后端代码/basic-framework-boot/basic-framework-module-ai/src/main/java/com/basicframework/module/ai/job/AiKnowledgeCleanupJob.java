package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.service.knowledge.lifecycle.AiKnowledgeLifecycleService;
import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeCleanupReportDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识清理 Job（K07）：推进"删除中"文档的资源回收（切片 → 向量 → 文件引用 → 软删除行）。
 *
 * <p>可恢复性来自**状态驱动**而不是进度记录：每轮都从库里捞 `DELETING` 的文档继续做，
 * 每步都幂等，因此进程崩溃、发布重启后重启即继续（AT-028 的"重启继续清理"）。
 */
@Slf4j
@Component
public class AiKnowledgeCleanupJob implements JobHandler {

    private final AiKnowledgeLifecycleService lifecycleService;

    private final int batchSize;

    /** 构造器注入：配置值与依赖都通过构造器传入。 */
    public AiKnowledgeCleanupJob(
            AiKnowledgeLifecycleService lifecycleService,
            @Value("${basic-framework.ai.knowledge.cleanup.batch-size:50}") int batchSize) {
        this.lifecycleService = lifecycleService;
        this.batchSize = batchSize;
    }

    @Override
    public String execute(String param) {
        AiKnowledgeCleanupReportDTO report = lifecycleService.processPendingCleanups(batchSize);
        if (report.getProcessedDocuments() > 0 || report.getPendingDocuments() > 0) {
            log.info(
                    "[execute][知识清理：文档 {} 个，切片 {} 行，向量 {} 点，文件引用 {} 个，待处理 {} 个]",
                    report.getProcessedDocuments(),
                    report.getDeletedChunks(),
                    report.getDeletedVectors(),
                    report.getReleasedFiles(),
                    report.getPendingDocuments());
        }
        return String.format(
                "知识清理：文档 %s 个（切片 %s，向量 %s，文件引用 %s），待处理 %s 个",
                report.getProcessedDocuments(),
                report.getDeletedChunks(),
                report.getDeletedVectors(),
                report.getReleasedFiles(),
                report.getPendingDocuments());
    }
}
