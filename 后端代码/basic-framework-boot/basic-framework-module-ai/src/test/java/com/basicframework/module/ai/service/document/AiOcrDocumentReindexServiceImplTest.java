package com.basicframework.module.ai.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OCR 识别稿重索引（X02）：走既有入库语义（同 sourceKey、新版本、任务），
 * 无正文时**不上传、不入库**（旧 active 版本因此不会被替换）。
 */
class AiOcrDocumentReindexServiceImplTest {

    private static final long DOCUMENT_ID = 5L;

    private static final long KNOWLEDGE_BASE_ID = 9L;

    private static final long SOURCE_FILE_ID = 1024L;

    private static final long DERIVED_FILE_ID = 2048L;

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeBaseService baseService = mock(AiKnowledgeBaseService.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiKnowledgeIngestionService ingestionService = mock(AiKnowledgeIngestionService.class);

    private AiOcrDocumentReindexServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiOcrDocumentReindexServiceImpl(
                documentService, baseService, fileService, ingestionService, new AiOcrDocumentSource());
        when(documentService.getDocument(DOCUMENT_ID))
                .thenReturn(new AiKnowledgeDocumentDO()
                        .setId(DOCUMENT_ID)
                        .setKnowledgeBaseId(KNOWLEDGE_BASE_ID)
                        .setSourceKey("scan-0001")
                        .setTitle("扫描合同")
                        .setSourceType("UPLOAD"));
        when(baseService.requireEnabled(KNOWLEDGE_BASE_ID))
                .thenReturn(new AiKnowledgeBaseDO().setId(KNOWLEDGE_BASE_ID).setCode("kb-finance"));
        when(fileService.upload(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiFileUploadResultDTO().setFileId(DERIVED_FILE_ID));
        when(ingestionService.ingest(any()))
                .thenReturn(new AiKnowledgeIngestionResultDTO()
                        .setDocumentId(DOCUMENT_ID)
                        .setVersionId(77L)
                        .setVersionNo(2)
                        .setTaskId(88L)
                        .setReused(false)
                        .setCreatedVersion(true));
    }

    @Test
    void reindexUploadsDerivedSourceAndReusesExistingIngestionSemantics() {
        AiOcrDocumentReindexResultDTO result = service.reindex(request(List.of(
                new AiOcrPage(1, "第一页正文", AiVisionConfidenceSource.UNKNOWN),
                new AiOcrPage(2, "第二页正文", AiVisionConfidenceSource.UNKNOWN))));

        ArgumentCaptor<AiKnowledgeIngestionRequestDTO> captor =
                ArgumentCaptor.forClass(AiKnowledgeIngestionRequestDTO.class);
        verify(ingestionService).ingest(captor.capture());
        AiKnowledgeIngestionRequestDTO sent = captor.getValue();
        // 复用原文档的 sourceKey 与来源类型：这是"同 key 换版本"而不是"新建文档"
        assertThat(sent.getSourceKey()).isEqualTo("scan-0001");
        assertThat(sent.getSourceType()).isEqualTo("UPLOAD");
        assertThat(sent.getTitle()).isEqualTo("扫描合同");
        assertThat(sent.getFileId()).isEqualTo(DERIVED_FILE_ID);
        assertThat(sent.getSourceRef()).isEqualTo("ocr:sourceFileId=" + SOURCE_FILE_ID);
        assertThat(sent.getContentHash()).matches("^[0-9a-f]{64}$");
        // 派生文件是绑定到该知识库的知识文档（A07 归属判定要过）
        verify(fileService)
                .upload(
                        eq(AiFileBusinessType.KNOWLEDGE_DOCUMENT.code()),
                        eq("kb-finance"),
                        eq(AiOcrDocumentSource.FILE_NAME),
                        eq("text/plain"),
                        any());

        assertThat(result.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(result.getVersionId()).isEqualTo(77L);
        assertThat(result.getTaskId()).isEqualTo(88L);
        assertThat(result.isCreatedVersion()).isTrue();
        assertThat(result.getPageCount()).isEqualTo(2);
        assertThat(result.getConfidenceSource()).isEqualTo(AiVisionConfidenceSource.UNKNOWN);
        assertThat(result.isReviewRequired()).isTrue();
        assertThat(result.getDerivedFileId()).isEqualTo(DERIVED_FILE_ID);
    }

    @Test
    void ocrWithoutAnyTextIsRejectedBeforeUploadSoOldVersionStaysActive() {
        assertCode(
                () -> service.reindex(request(List.of(
                        new AiOcrPage(1, " ", AiVisionConfidenceSource.UNKNOWN),
                        new AiOcrPage(2, "", AiVisionConfidenceSource.UNKNOWN)))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);

        verify(fileService, never()).upload(anyString(), anyString(), anyString(), anyString(), any());
        verify(ingestionService, never()).ingest(any());
    }

    @Test
    void invalidRequestShapesAreRejectedBeforeAnyWrite() {
        assertCode(() -> service.reindex(null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.reindex(new AiOcrDocumentReindexRequestDTO()
                        .setSourceFileId(SOURCE_FILE_ID)
                        .setPages(List.of(new AiOcrPage(1, "正文", AiVisionConfidenceSource.UNKNOWN)))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.reindex(new AiOcrDocumentReindexRequestDTO()
                        .setDocumentId(DOCUMENT_ID)
                        .setSourceFileId(0L)
                        .setPages(List.of(new AiOcrPage(1, "正文", AiVisionConfidenceSource.UNKNOWN)))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);

        verify(fileService, never()).upload(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void ingestionFailurePropagatesAndIsNotSwallowed() {
        when(ingestionService.ingest(any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID.getCode(), "文件不合法"));

        assertThatThrownBy(() ->
                        service.reindex(request(List.of(new AiOcrPage(1, "正文", AiVisionConfidenceSource.UNKNOWN)))))
                .isInstanceOf(ServiceException.class);
        // 派生文件的补偿由 K03 入库语义负责（解除引用）；本层不吞异常、不伪造成功
        verify(ingestionService).ingest(any());
    }

    private static AiOcrDocumentReindexRequestDTO request(List<AiOcrPage> pages) {
        return new AiOcrDocumentReindexRequestDTO()
                .setDocumentId(DOCUMENT_ID)
                .setSourceFileId(SOURCE_FILE_ID)
                .setPages(pages);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
