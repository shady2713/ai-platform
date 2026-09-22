package com.basicframework.module.ai.service.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.document.RestrictedDocumentParser;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** K05 索引流水线：全成功才切 active、失败保留旧版本、重跑幂等、维度不一致拒绝。 */
class AiKnowledgeIndexingServiceTest {

    private static final Long BASE_ID = 61L;

    private static final Long DOCUMENT_ID = 71L;

    private static final Long VERSION_ID = 81L;

    private static final String PARAGRAPH = "华东区域 8 月净额为 740.00 元。";

    /** 重复到超过块上限（80 字符）的长段落：确保切出多块。 */
    private static final String LONG_PARAGRAPH = PARAGRAPH.repeat(4);

    private final AiKnowledgeBaseService baseService = mock(AiKnowledgeBaseService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeIndexGenerationService generationService = mock(AiKnowledgeIndexGenerationService.class);

    private final AiKnowledgeChunkService chunkService = mock(AiKnowledgeChunkService.class);

    private final AiKnowledgeSourceReader sourceReader = mock(AiKnowledgeSourceReader.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<KnowledgeIndexPort> indexPortProvider = mock(ObjectProvider.class);

    private final InMemoryIndexPort indexPort = new InMemoryIndexPort();

    private final AiKnowledgeEmbeddingClient embeddingClient = mock(AiKnowledgeEmbeddingClient.class);

    private final AiKnowledgeIndexingService service = new AiKnowledgeIndexingService(
            baseService,
            documentService,
            generationService,
            chunkService,
            sourceReader,
            new RestrictedDocumentParser(),
            new AiKnowledgeChunker(80, 20),
            embeddingClient,
            indexPortProvider);

    /** 内存向量端口：验证"重跑不重复"不需要真实向量服务。 */
    private static final class InMemoryIndexPort implements KnowledgeIndexPort {

        private final Map<String, Map<String, IndexDocument>> collections = new LinkedHashMap<>();

        @Override
        public CollectionInfo ensureCollection(String collection, int dimension) {
            collections.computeIfAbsent(collection, key -> new LinkedHashMap<>());
            return new CollectionInfo(
                    collection, dimension, collections.get(collection).size());
        }

        @Override
        public void upsert(String collection, List<IndexDocument> documents) {
            Map<String, IndexDocument> points = collections.computeIfAbsent(collection, key -> new LinkedHashMap<>());
            documents.forEach(document -> points.put(document.id(), document));
        }

        @Override
        public List<SearchHit> search(String collection, float[] vector, int topK, KnowledgeFilter filter) {
            return List.of();
        }

        @Override
        public long delete(String collection, KnowledgeFilter filter) {
            return 0;
        }

        @Override
        public void deleteAll(String collection) {
            collections.remove(collection);
        }

        @Override
        public CollectionInfo describe(String collection) {
            Map<String, IndexDocument> points = collections.getOrDefault(collection, Map.of());
            return new CollectionInfo(collection, 0, points.size());
        }

        int pointCount(String collection) {
            return collections.getOrDefault(collection, Map.of()).size();
        }
    }

    private void stubVersionAndBase(String status) {
        when(documentService.getVersion(VERSION_ID))
                .thenReturn(new AiKnowledgeDocumentVersionDO()
                        .setId(VERSION_ID)
                        .setDocumentId(DOCUMENT_ID)
                        .setKnowledgeBaseId(BASE_ID)
                        .setVersionNo(1)
                        .setFileId(501L)
                        .setStatus(status)
                        .setVersion(0));
        when(baseService.requireEnabled(BASE_ID))
                .thenReturn(new AiKnowledgeBaseDO()
                        .setId(BASE_ID)
                        .setCode("handbook")
                        .setEmbeddingModel("text-embedding-3-small")
                        .setEmbeddingDimension(1536)
                        .setStatus(AiKnowledgeBaseDO.STATUS_ENABLED));
        when(indexPortProvider.getIfAvailable()).thenReturn(indexPort);
        when(sourceReader.read(501L))
                .thenReturn(new AiKnowledgeSourceReader.KnowledgeSource(
                        (LONG_PARAGRAPH + "\n\n" + LONG_PARAGRAPH).getBytes(StandardCharsets.UTF_8), "handbook.txt"));
        when(generationService.listGenerations(BASE_ID))
                .thenReturn(List.of(new AiKnowledgeIndexGenerationDO()
                        .setId(102L)
                        .setKnowledgeBaseId(BASE_ID)
                        .setGenerationNo(2)
                        .setCollectionName("kb_handbook_g2")
                        .setDimension(1536)
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_BUILDING)
                        .setVersion(0)));
    }

    private void stubEmbedding(int dimension) {
        when(embeddingClient.embed(eq("text-embedding-3-small"), any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<float[]> vectors = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) {
                float[] vector = new float[dimension];
                vector[0] = index + 1;
                vectors.add(vector);
            }
            return new AiKnowledgeEmbeddingClient.EmbeddingBatch(vectors, "text-embedding-3-small", dimension);
        });
    }

    @Test
    void indexesAllChunksThenActivatesAndSwitchesActiveVersion() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        stubEmbedding(1536);

        AiKnowledgeIndexingService.IndexingOutcome outcome = service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(outcome.indexed()).isTrue();
        assertThat(outcome.chunkCount()).isGreaterThan(1);
        assertThat(indexPort.pointCount("kb_handbook_g2")).isEqualTo(outcome.chunkCount());
        ArgumentCaptor<List<AiKnowledgeChunkDTO>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkService).replaceVersionChunks(eq(VERSION_ID), eq(2), chunks.capture());
        assertThat(chunks.getValue()).hasSize(outcome.chunkCount());
        assertThat(chunks.getValue().get(0).getLocationRef()).isEqualTo("段落 1");
        assertThat(chunks.getValue().get(0).getVectorId())
                .isEqualTo(AiKnowledgeChunker.deterministicVectorId(VERSION_ID, 0));
        verify(generationService).activate(BASE_ID, 2);
        verify(documentService).markVersionReady(DOCUMENT_ID, VERSION_ID, 2);
    }

    @Test
    void rerunningTheSameVersionDoesNotDuplicateVectors() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        stubEmbedding(1536);

        int firstCount = service.index(DOCUMENT_ID, VERSION_ID).chunkCount();
        service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(indexPort.pointCount("kb_handbook_g2"))
                .as("同一版本重跑：点标识相同，覆盖而不是追加")
                .isEqualTo(firstCount);
    }

    @Test
    void embeddingFailureKeepsTheOldVersionAndDoesNotActivate() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        when(embeddingClient.embed(any(), any()))
                .thenThrow(new AiKnowledgeEmbeddingException(
                        AiKnowledgeEmbeddingException.Reason.UPSTREAM_FAILED, "timeout"));

        AiKnowledgeIndexingService.IndexingOutcome outcome = service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(outcome.indexed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("embed-upstream_failed");
        verify(generationService, never()).activate(anyLong(), anyInt());
        verify(documentService, never()).markVersionReady(anyLong(), anyLong(), anyInt());
        assertThat(indexPort.pointCount("kb_handbook_g2")).as("失败不写入向量").isZero();
    }

    @Test
    void dimensionMismatchIsRejectedBeforeWritingAnything() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        stubEmbedding(768);

        AiKnowledgeIndexingService.IndexingOutcome outcome = service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(outcome.indexed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("embed-dimension_mismatch");
        verify(chunkService, never()).replaceVersionChunks(anyLong(), anyInt(), any());
        verify(generationService, never()).activate(anyLong(), anyInt());
    }

    @Test
    void scannedPdfIsReportedAsOcrRequiredWithoutPlaceholderText() throws IOException {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        when(sourceReader.read(501L)).thenReturn(new AiKnowledgeSourceReader.KnowledgeSource(scannedPdf(), "scan.pdf"));

        AiKnowledgeIndexingService.IndexingOutcome outcome = service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(outcome.indexed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("parse-ocr_required");
        verify(chunkService, never()).replaceVersionChunks(anyLong(), anyInt(), any());
    }

    @Test
    void sourceReadFailureIsReportedWithStableCode() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        doThrow(new com.basicframework.module.ai.adapter.knowledge.KnowledgeSourceException(
                        com.basicframework.module.ai.adapter.knowledge.KnowledgeSourceException.Reason.SUBJECT_MISSING,
                        null))
                .when(sourceReader)
                .read(501L);

        AiKnowledgeIndexingService.IndexingOutcome outcome = service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(outcome.reasonCode()).isEqualTo("source-subject_missing");
        verify(generationService, never()).activate(anyLong(), anyInt());
    }

    @Test
    void immutableOrMisstatedVersionsAreRejected() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_READY);

        assertThatThrownBy(() -> service.index(DOCUMENT_ID, VERSION_ID))
                .isInstanceOf(ServiceException.class)
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE.getCode()));

        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_FAILED);
        assertThatThrownBy(() -> service.index(DOCUMENT_ID, VERSION_ID))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID.getCode()));
    }

    @Test
    void missingVectorServiceFailsInsteadOfPretendingSuccess() {
        stubVersionAndBase(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
        when(indexPortProvider.getIfAvailable()).thenReturn(null);

        AiKnowledgeIndexingService.IndexingOutcome outcome = service.index(DOCUMENT_ID, VERSION_ID);

        assertThat(outcome.indexed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("index-service-unavailable");
    }

    private static byte[] scannedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.addRect(50, 700, 100, 50);
                stream.stroke();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
