package com.basicframework.module.ai.service.knowledge.indexing;

import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionStep;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionStepOutcome;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 入库步骤实现（K05）：把 K03 的入库任务接到索引流水线上。
 *
 * <p>步骤只做一件事：调用流水线并把结论翻译成 K03 的步骤结论。
 * 失败时流水线**不会**激活索引代，K03 的 Job 据此把版本与文档标失败（旧可用版本不受影响，AT-024）。
 */
@Component
@RequiredArgsConstructor
public class AiKnowledgeIndexingStep implements AiKnowledgeIngestionStep {

    private final AiKnowledgeIndexingService indexingService;

    @Override
    public String name() {
        return "knowledge-indexing";
    }

    @Override
    public AiKnowledgeIngestionStepOutcome process(AiKnowledgeIngestionTaskLeaseDTO lease) {
        AiKnowledgeIndexingService.IndexingOutcome outcome =
                indexingService.index(lease.documentId(), lease.documentVersionId());
        return outcome.indexed()
                ? AiKnowledgeIngestionStepOutcome.succeeded()
                : AiKnowledgeIngestionStepOutcome.failed(outcome.reasonCode());
    }
}
