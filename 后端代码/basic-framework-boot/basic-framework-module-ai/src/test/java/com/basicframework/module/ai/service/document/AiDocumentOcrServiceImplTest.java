package com.basicframework.module.ai.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.document.dto.AiDocumentOcrRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import com.basicframework.module.ai.service.vision.AiVisionLimits;
import com.basicframework.module.ai.service.vision.AiVisionService;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 扫描件 OCR 重索引编排（X02）：先授权后外发、逐页页码、失败不落半成品。
 */
class AiDocumentOcrServiceImplTest {

    private static final long DOCUMENT_ID = 5L;

    private static final long KNOWLEDGE_BASE_ID = 9L;

    private static final long ENDPOINT_ID = 7L;

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeBaseService baseService = mock(AiKnowledgeBaseService.class);

    private final AiVisionService visionService = mock(AiVisionService.class);

    private final AiOcrDocumentReindexService reindexService = mock(AiOcrDocumentReindexService.class);

    private AiDocumentOcrServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiDocumentOcrServiceImpl(
                subjectResolver, authorizationService, documentService, baseService, visionService, reindexService);
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(11L, AiSubjectType.USER, "alice")));
        when(documentService.getDocument(DOCUMENT_ID))
                .thenReturn(new AiKnowledgeDocumentDO()
                        .setId(DOCUMENT_ID)
                        .setKnowledgeBaseId(KNOWLEDGE_BASE_ID)
                        .setSourceKey("scan-0001")
                        .setTitle("扫描合同"));
        when(baseService.requireEnabled(KNOWLEDGE_BASE_ID))
                .thenReturn(new AiKnowledgeBaseDO().setId(KNOWLEDGE_BASE_ID).setCode("kb-finance"));
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
        when(visionService.recognizeText(any())).thenReturn(ocrResult("识别文本", AiVisionConfidenceSource.UNKNOWN));
        when(reindexService.reindex(any()))
                .thenReturn(new AiOcrDocumentReindexResultDTO()
                        .setDocumentId(DOCUMENT_ID)
                        .setVersionId(77L)
                        .setVersionNo(2)
                        .setTaskId(88L)
                        .setCreatedVersion(true)
                        .setPageCount(2)
                        .setReviewRequired(true));
    }

    @Test
    void pagesAreOcrEdInOrderAndReindexedWithTheirPageNumbers() {
        AiOcrDocumentReindexResultDTO result = service.recognizeAndReindex(request(2));

        ArgumentCaptor<AiVisionOcrRequestDTO> ocrCaptor = ArgumentCaptor.forClass(AiVisionOcrRequestDTO.class);
        verify(visionService, org.mockito.Mockito.times(2)).recognizeText(ocrCaptor.capture());
        List<AiVisionOcrRequestDTO> ocrRequests = ocrCaptor.getAllValues();
        assertThat(ocrRequests.get(0).getEndpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(ocrRequests.get(0).getImage().getFileId()).isEqualTo(101L);
        assertThat(ocrRequests.get(1).getImage().getFileId()).isEqualTo(102L);

        ArgumentCaptor<AiOcrDocumentReindexRequestDTO> reindexCaptor =
                ArgumentCaptor.forClass(AiOcrDocumentReindexRequestDTO.class);
        verify(reindexService).reindex(reindexCaptor.capture());
        AiOcrDocumentReindexRequestDTO sent = reindexCaptor.getValue();
        assertThat(sent.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(sent.getSourceFileId()).isEqualTo(101L);
        assertThat(sent.getPages())
                .extracting(AiOcrPage::page)
                .as("页码来自调用方给出的页序，不是返回顺序猜测")
                .containsExactly(1, 2);
        assertThat(sent.getPages())
                .allSatisfy(page -> assertThat(page.confidenceSource()).isEqualTo(AiVisionConfidenceSource.UNKNOWN));

        assertThat(result.getVersionId()).isEqualTo(77L);
        assertThat(result.getPageCount()).isEqualTo(2);
    }

    @Test
    void unauthorizedSubjectIsRejectedBeforeAnyModelCall() {
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));

        assertCode(() -> service.recognizeAndReindex(request(1)), AiErrorCodeConstants.AI_ACCESS_DENIED);

        verify(visionService, never()).recognizeText(any());
        verify(reindexService, never()).reindex(any());
    }

    @Test
    void unauthenticatedSubjectIsRejectedBeforeReadingDocuments() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());

        assertCode(() -> service.recognizeAndReindex(request(1)), AiErrorCodeConstants.AI_ACCESS_DENIED);

        verify(visionService, never()).recognizeText(any());
        verify(documentService, never()).getDocument(anyLong());
    }

    @Test
    void pageCountIsBoundedBeforeAnyModelCall() {
        List<AiVisionImageRefDTO> tooMany = new ArrayList<>();
        for (int page = 1; page <= AiVisionLimits.MAX_DOCUMENT_PAGES + 1; page++) {
            tooMany.add(imageRef(100L + page));
        }

        assertCode(
                () -> service.recognizeAndReindex(new AiDocumentOcrRequestDTO()
                        .setDocumentId(DOCUMENT_ID)
                        .setEndpointId(ENDPOINT_ID)
                        .setPageImages(tooMany)),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);

        verify(visionService, never()).recognizeText(any());
    }

    @Test
    void failureOnAnyPageStopsTheRunWithoutReindexing() {
        when(visionService.recognizeText(any()))
                .thenReturn(ocrResult("第一页文本", AiVisionConfidenceSource.UNKNOWN))
                .thenThrow(new ServiceException(
                        AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED.getCode(), "端点未开通能力"));

        assertCode(() -> service.recognizeAndReindex(request(2)), AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED);

        verify(reindexService, never()).reindex(any());
    }

    private static AiDocumentOcrRequestDTO request(int pages) {
        List<AiVisionImageRefDTO> images = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            images.add(imageRef(100L + page));
        }
        return new AiDocumentOcrRequestDTO()
                .setDocumentId(DOCUMENT_ID)
                .setEndpointId(ENDPOINT_ID)
                .setPageImages(images)
                .setLanguageHint("zh-CN");
    }

    private static AiVisionImageRefDTO imageRef(long fileId) {
        return new AiVisionImageRefDTO().setFileId(fileId).setMime("image/png").setSize(2048L);
    }

    private static AiVisionOcrResultDTO ocrResult(String text, AiVisionConfidenceSource confidenceSource) {
        return new AiVisionOcrResultDTO()
                .setPage(1)
                .setText(text)
                .setConfidenceSource(confidenceSource)
                .setReviewRequired(true);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
