package com.basicframework.module.ai.service.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** K05 入库步骤：把流水线结论翻译成 K03 的步骤结论（失败必须带稳定原因码）。 */
class AiKnowledgeIndexingStepTest {

    private final AiKnowledgeIndexingService indexingService = mock(AiKnowledgeIndexingService.class);

    private final AiKnowledgeIndexingStep step = new AiKnowledgeIndexingStep(indexingService);

    private static AiKnowledgeIngestionTaskLeaseDTO lease() {
        return new AiKnowledgeIngestionTaskLeaseDTO(
                91L, 61L, 71L, 81L, "PARSE", "worker-1", 1, LocalDateTime.now().plusSeconds(60));
    }

    @Test
    void successfulPipelineMapsToSucceededOutcome() {
        when(indexingService.index(anyLong(), anyLong()))
                .thenReturn(new AiKnowledgeIndexingService.IndexingOutcome(true, 3, null));

        var outcome = step.process(lease());

        assertThat(step.name()).isEqualTo("knowledge-indexing");
        assertThat(outcome.isSucceeded()).isTrue();
        assertThat(outcome.reasonCode()).isNull();
    }

    @Test
    void failedPipelineKeepsStableReasonCode() {
        when(indexingService.index(anyLong(), anyLong()))
                .thenReturn(new AiKnowledgeIndexingService.IndexingOutcome(false, 0, "embed-upstream_failed"));

        var outcome = step.process(lease());

        assertThat(outcome.isSucceeded()).isFalse();
        assertThat(outcome.isFailed()).isTrue();
        assertThat(outcome.reasonCode()).isEqualTo("embed-upstream_failed");
    }
}
