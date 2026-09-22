package com.basicframework.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionStep;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionTaskDO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionStepOutcome;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** K03 入库 Job：文件引用校验、状态推进、步骤交接与"不假装成功"。 */
class AiKnowledgeIngestionJobTest {

    private static final Long TASK_ID = 91L;

    private static final Long DOCUMENT_ID = 71L;

    private static final Long VERSION_ID = 81L;

    private static final Long FILE_ID = 501L;

    private final AiKnowledgeIngestionService ingestionService = mock(AiKnowledgeIngestionService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiFileBindingMapper fileBindingMapper = mock(AiFileBindingMapper.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AiKnowledgeIngestionStep> steps = mock(ObjectProvider.class);

    private static AiKnowledgeIngestionTaskLeaseDTO lease() {
        return new AiKnowledgeIngestionTaskLeaseDTO(
                TASK_ID,
                61L,
                DOCUMENT_ID,
                VERSION_ID,
                "PARSE",
                "worker-1",
                1,
                LocalDateTime.now().plusSeconds(60));
    }

    private AiKnowledgeIngestionJob job() {
        return new AiKnowledgeIngestionJob(
                ingestionService, documentService, fileBindingMapper, steps, "test-worker", 5, 60);
    }

    private void stubVersionAndBinding(String bindingStatus) {
        when(documentService.getVersion(VERSION_ID))
                .thenReturn(new AiKnowledgeDocumentVersionDO()
                        .setId(VERSION_ID)
                        .setDocumentId(DOCUMENT_ID)
                        .setVersionNo(1)
                        .setFileId(FILE_ID)
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_INDEXING)
                        .setVersion(0));
        when(documentService.getDocument(DOCUMENT_ID))
                .thenReturn(new AiKnowledgeDocumentDO()
                        .setId(DOCUMENT_ID)
                        .setStatus(AiKnowledgeDocumentDO.STATUS_PENDING)
                        .setVersion(0));
        when(fileBindingMapper.selectActiveByFile(FILE_ID))
                .thenReturn(
                        bindingStatus == null
                                ? List.of()
                                : List.of(new AiFileBindingDO()
                                        .setFileId(FILE_ID)
                                        .setBusinessType("ai_knowledge_document")
                                        .setStatus(bindingStatus)));
    }

    @Test
    void claimsTasksAndReportsCounts() {
        when(ingestionService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        String result = job().execute(null);

        assertThat(result).contains("0 条");
        verify(ingestionService).claim(anyString(), eq(5), eq(60));
    }

    @Test
    void missingFileReferenceFailsTheTaskWithoutFakeSuccess() {
        stubVersionAndBinding(null);
        when(ingestionService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(lease()));
        when(ingestionService.finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("file-unavailable")))
                .thenReturn(true);

        job().execute(null);

        verify(ingestionService).finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("file-unavailable"));
        verify(documentService).markVersionFailed(DOCUMENT_ID, VERSION_ID, "file-unavailable");
        verify(ingestionService, never()).finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED), any());
    }

    @Test
    void withoutAStepImplementationTheTaskFailsWithStableReason() {
        stubVersionAndBinding(AiFileBindingDO.STATUS_ACTIVE);
        when(steps.getIfAvailable()).thenReturn(null);
        when(ingestionService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(lease()));
        when(ingestionService.finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("parser-unavailable")))
                .thenReturn(true);

        job().execute(null);

        verify(documentService).markParsing(DOCUMENT_ID, 0);
        verify(ingestionService).finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("parser-unavailable"));
        verify(documentService).markVersionFailed(DOCUMENT_ID, VERSION_ID, "parser-unavailable");
    }

    @Test
    void successfulStepFinishesTheTaskAndFailedStepMarksTheVersion() {
        stubVersionAndBinding(AiFileBindingDO.STATUS_ACTIVE);
        AiKnowledgeIngestionStep step = mock(AiKnowledgeIngestionStep.class);
        when(steps.getIfAvailable()).thenReturn(step);
        when(step.name()).thenReturn("parse");
        when(ingestionService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(lease()));
        when(ingestionService.finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED), any()))
                .thenReturn(true);
        when(step.process(any())).thenReturn(AiKnowledgeIngestionStepOutcome.succeeded());

        job().execute(null);
        verify(ingestionService).finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED), any());
        verify(documentService, never()).markVersionFailed(any(), any(), anyString());

        when(ingestionService.finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("scan-needs-ocr")))
                .thenReturn(true);
        when(step.process(any())).thenReturn(AiKnowledgeIngestionStepOutcome.failed("scan-needs-ocr"));
        job().execute(null);
        verify(documentService).markVersionFailed(DOCUMENT_ID, VERSION_ID, "scan-needs-ocr");

        when(ingestionService.finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("step-failed")))
                .thenReturn(true);
        when(step.process(any())).thenThrow(new IllegalStateException("原始解析异常正文不应外泄"));
        job().execute(null);
        verify(ingestionService).finish(any(), eq(AiKnowledgeIngestionTaskDO.STATUS_FAILED), eq("step-failed"));
        verify(documentService).markVersionFailed(DOCUMENT_ID, VERSION_ID, "step-failed");
    }

    @Test
    void fenceMissKeepsTheTaskForTheNewWorker() {
        stubVersionAndBinding(AiFileBindingDO.STATUS_ACTIVE);
        AiKnowledgeIngestionStep step = mock(AiKnowledgeIngestionStep.class);
        when(steps.getIfAvailable()).thenReturn(step);
        when(step.name()).thenReturn("parse");
        when(step.process(any())).thenReturn(AiKnowledgeIngestionStepOutcome.succeeded());
        when(ingestionService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(lease()));
        when(ingestionService.finish(any(), anyString(), any())).thenReturn(false);

        String result = job().execute(null);

        assertThat(result).contains("失败 1");
        verify(documentService, never()).markVersionFailed(any(), any(), anyString());
    }
}
