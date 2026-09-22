package com.basicframework.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.service.knowledge.lifecycle.AiKnowledgeLifecycleService;
import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeCleanupReportDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

/** K07 清理 Job：把报告翻译成可读结论（有待处理项与无待处理项两种形态）。 */
class AiKnowledgeCleanupJobTest {

    private final AiKnowledgeLifecycleService lifecycleService = mock(AiKnowledgeLifecycleService.class);

    private final AiKnowledgeCleanupJob job = new AiKnowledgeCleanupJob(lifecycleService, 50);

    @Test
    void reportsProcessedResourcesAndPendingCount() {
        when(lifecycleService.processPendingCleanups(anyInt()))
                .thenReturn(new AiKnowledgeCleanupReportDTO()
                        .setProcessedDocuments(2)
                        .setDeletedChunks(7)
                        .setDeletedVectors(7L)
                        .setReleasedFiles(2)
                        .setPendingDocuments(3)
                        .setOrphans(List.of("retired-generation-vectors:generation=1")));

        String summary = job.execute(null);

        assertThat(summary)
                .contains("文档 2 个")
                .contains("切片 7")
                .contains("向量 7")
                .contains("文件引用 2")
                .contains("待处理 3 个");
    }

    @Test
    void reportsZeroWhenNothingIsPending() {
        when(lifecycleService.processPendingCleanups(anyInt()))
                .thenReturn(new AiKnowledgeCleanupReportDTO().setPendingDocuments(0));

        String summary = job.execute(null);

        assertThat(summary).contains("文档 0 个").contains("待处理 0 个");
        assertThat(summary).doesNotContain("null");
    }
}
