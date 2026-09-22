package com.basicframework.module.ai.service.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentVersionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeIndexingService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeCleanupReportDTO;
import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeSyncResultDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * K07 生命周期：同步按 sourceKey、删除先撤可见性、清理分步幂等、孤儿检查与重建。
 *
 * <p>重点断言**顺序**：先撤可见性（DELETING）再清理资源——顺序反了会出现"内容已删但引用仍可见"。
 */
class AiKnowledgeLifecycleServiceImplTest {

    private static final Long BASE_ID = 61L;

    private static final Long DOCUMENT_ID = 71L;

    private static final Long VERSION_ID = 81L;

    private final AiKnowledgeBaseService baseService = mock(AiKnowledgeBaseService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeChunkService chunkService = mock(AiKnowledgeChunkService.class);

    private final AiKnowledgeIndexGenerationService generationService = mock(AiKnowledgeIndexGenerationService.class);

    private final AiKnowledgeIngestionService ingestionService = mock(AiKnowledgeIngestionService.class);

    private final AiKnowledgeIndexingService indexingService = mock(AiKnowledgeIndexingService.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiKnowledgeDocumentMapper documentMapper = mock(AiKnowledgeDocumentMapper.class);

    private final AiKnowledgeDocumentVersionMapper versionMapper = mock(AiKnowledgeDocumentVersionMapper.class);

    private final AiKnowledgeChunkMapper chunkMapper = mock(AiKnowledgeChunkMapper.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<KnowledgeIndexPort> indexPortProvider = mock(ObjectProvider.class);

    private final KnowledgeIndexPort indexPort = mock(KnowledgeIndexPort.class);

    private final AiKnowledgeLifecycleServiceImpl service = new AiKnowledgeLifecycleServiceImpl(
            baseService,
            documentService,
            chunkService,
            generationService,
            ingestionService,
            indexingService,
            fileService,
            documentMapper,
            versionMapper,
            chunkMapper,
            indexPortProvider);

    private static AiKnowledgeDocumentDO document(String status, int version) {
        return new AiKnowledgeDocumentDO()
                .setId(DOCUMENT_ID)
                .setKnowledgeBaseId(BASE_ID)
                .setTitle("员工手册")
                .setStatus(status)
                .setActiveVersionNo(1)
                .setLatestVersionNo(1)
                .setVersion(version);
    }

    private static AiKnowledgeDocumentVersionDO version(String status) {
        return new AiKnowledgeDocumentVersionDO()
                .setId(VERSION_ID)
                .setDocumentId(DOCUMENT_ID)
                .setKnowledgeBaseId(BASE_ID)
                .setVersionNo(1)
                .setFileId(501L)
                .setStatus(status)
                .setChunkCount(2)
                .setVersion(0);
    }

    private void stubGenerations() {
        when(generationService.listGenerations(BASE_ID))
                .thenReturn(List.of(new AiKnowledgeIndexGenerationDO()
                        .setKnowledgeBaseId(BASE_ID)
                        .setGenerationNo(1)
                        .setCollectionName("kb_handbook_g1")
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE)));
        when(indexPortProvider.getIfAvailable()).thenReturn(indexPort);
        when(indexPort.delete(eq("kb_handbook_g1"), any())).thenReturn(2L);
    }

    @Test
    void syncDelegatesToIngestionAndRecordsSource() {
        when(ingestionService.ingest(any()))
                .thenReturn(new AiKnowledgeIngestionResultDTO()
                        .setDocumentId(DOCUMENT_ID)
                        .setVersionId(VERSION_ID)
                        .setVersionNo(2)
                        .setTaskId(91L)
                        .setReused(true)
                        .setCreatedVersion(true));

        AiKnowledgeSyncResultDTO result =
                service.sync(BASE_ID, "handbook/v2.pdf", "员工手册", "drive:handbook/v2.pdf", 501L, "a".repeat(64));

        assertThat(result.getTaskId()).isEqualTo(91L);
        assertThat(result.getVersionNo()).isEqualTo(2);
        ArgumentCaptor<AiKnowledgeIngestionRequestDTO> captor =
                ArgumentCaptor.forClass(AiKnowledgeIngestionRequestDTO.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().getSourceType()).as("同步来源记录为 API_SYNC").isEqualTo("API_SYNC");
        assertThat(captor.getValue().getSourceRef()).isEqualTo("drive:handbook/v2.pdf");
    }

    @Test
    void cleanupRevokesVisibilityBeforeDeletingResources() {
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(AiKnowledgeDocumentDO.STATUS_READY, 4));
        when(versionMapper.selectByDocument(DOCUMENT_ID)).thenReturn(List.of(version("READY")));
        when(chunkService.deleteVersionChunks(VERSION_ID)).thenReturn(2);
        stubGenerations();

        AiKnowledgeCleanupReportDTO report = service.cleanup(DOCUMENT_ID);

        InOrder order = inOrder(documentService, chunkService, indexPort, fileService, documentMapper);
        order.verify(documentService).deleteDocument(eq(DOCUMENT_ID), eq(4));
        order.verify(chunkService).deleteVersionChunks(VERSION_ID);
        order.verify(indexPort).delete(eq("kb_handbook_g1"), any());
        order.verify(fileService).release(501L);
        order.verify(documentMapper).deleteById(DOCUMENT_ID);
        assertThat(report.getDeletedChunks()).isEqualTo(2);
        assertThat(report.getDeletedVectors()).isEqualTo(2);
        assertThat(report.getReleasedFiles()).isEqualTo(1);
        assertThat(report.getProcessedDocuments()).isEqualTo(1);
    }

    @Test
    void cleanupIsIdempotentAndToleratesFileReleaseFailures() {
        // 已经撤过可见性：不再重复撤
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(AiKnowledgeDocumentDO.STATUS_DELETING, 5));
        when(versionMapper.selectByDocument(DOCUMENT_ID)).thenReturn(List.of(version("READY")));
        when(chunkService.deleteVersionChunks(VERSION_ID)).thenReturn(0);
        stubGenerations();
        // 没有主体上下文时 A07 的释放会失败：清理必须继续（跳过并记录）
        doThrow(new ServiceException(AiErrorCodeConstants.AI_ACCESS_DENIED))
                .when(fileService)
                .release(501L);

        AiKnowledgeCleanupReportDTO report = service.cleanup(DOCUMENT_ID);

        verify(documentService, never()).deleteDocument(anyLong(), any());
        assertThat(report.getReleasedFiles()).isZero();
        assertThat(report.getProcessedDocuments()).isEqualTo(1);
        verify(documentMapper).deleteById(DOCUMENT_ID);
    }

    @Test
    void pendingCleanupsAreResumableAcrossRuns() {
        // 第一轮只处理一个（limit=1），第二轮继续处理剩下的：中断后重跑从当前状态继续
        when(documentMapper.selectList(any()))
                .thenReturn(List.of(document(AiKnowledgeDocumentDO.STATUS_DELETING, 5)))
                .thenReturn(List.of(
                        document(AiKnowledgeDocumentDO.STATUS_DELETING, 5).setId(72L)));
        when(documentMapper.selectById(71L)).thenReturn(document(AiKnowledgeDocumentDO.STATUS_DELETING, 5));
        when(documentMapper.selectById(72L))
                .thenReturn(document(AiKnowledgeDocumentDO.STATUS_DELETING, 5).setId(72L));
        when(versionMapper.selectByDocument(71L)).thenReturn(List.of(version("READY")));
        when(versionMapper.selectByDocument(72L))
                .thenReturn(List.of(version("READY").setId(82L).setDocumentId(72L)));
        when(chunkService.deleteVersionChunks(anyLong())).thenReturn(1);
        stubGenerations();

        AiKnowledgeCleanupReportDTO first = service.processPendingCleanups(1);
        AiKnowledgeCleanupReportDTO second = service.processPendingCleanups(1);

        assertThat(first.getProcessedDocuments()).isEqualTo(1);
        assertThat(second.getProcessedDocuments()).as("第二轮继续处理剩下的文档").isEqualTo(1);
        verify(documentMapper).deleteById(71L);
        verify(documentMapper).deleteById(72L);
    }

    @Test
    void orphanAuditDetectsAndReclaimsResidue() {
        when(versionMapper.selectList(any()))
                .thenReturn(
                        List.of(version("READY"), version("READY").setId(82L).setDocumentId(72L)));
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(AiKnowledgeDocumentDO.STATUS_READY, 4));
        // 版本 82 指向的文档已不存在（孤儿版本）
        when(documentMapper.selectById(72L)).thenReturn(null);
        when(chunkMapper.countByVersion(VERSION_ID)).thenReturn(2L);
        when(chunkMapper.countByGeneration(BASE_ID, 1)).thenReturn(3L);
        when(generationService.listGenerations(BASE_ID))
                .thenReturn(List.of(new AiKnowledgeIndexGenerationDO()
                        .setKnowledgeBaseId(BASE_ID)
                        .setGenerationNo(1)
                        .setCollectionName("kb_handbook_g1")
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_RETIRED)));
        when(indexPortProvider.getIfAvailable()).thenReturn(indexPort);
        when(indexPort.delete(eq("kb_handbook_g1"), any())).thenReturn(2L);
        when(chunkService.deleteVersionChunks(82L)).thenReturn(1);

        AiKnowledgeCleanupReportDTO report = service.auditOrphans(BASE_ID, true);

        assertThat(report.getOrphans())
                .anySatisfy(orphan -> assertThat(orphan).contains("version-without-document:82"))
                .anySatisfy(orphan -> assertThat(orphan).contains("retired-generation-vectors:generation=1"));
        assertThat(report.getDeletedVectors()).as("回收孤儿向量").isEqualTo(2);
        assertThat(report.getDeletedChunks()).isEqualTo(1);
        assertThat(report.hasPending()).isTrue();

        AiKnowledgeCleanupReportDTO dryRun = service.auditOrphans(BASE_ID, false);
        assertThat(dryRun.getOrphans()).isNotEmpty();
        assertThat(dryRun.getDeletedVectors()).as("只检查不清理").isZero();
    }

    @Test
    void rebuildIndexesActiveVersionsIntoANewGenerationAndKeepsOldOne() {
        when(baseService.requireEnabled(BASE_ID))
                .thenReturn(new AiKnowledgeBaseDO()
                        .setId(BASE_ID)
                        .setCode("handbook")
                        .setStatus("ENABLED"));
        when(generationService.startGeneration(BASE_ID)).thenReturn(2);
        when(documentMapper.selectList(any()))
                .thenReturn(List.of(
                        document(AiKnowledgeDocumentDO.STATUS_READY, 4),
                        document(AiKnowledgeDocumentDO.STATUS_READY, 4).setId(72L)));
        when(documentService.getActiveVersion(DOCUMENT_ID)).thenReturn(version("READY"));
        when(documentService.getActiveVersion(72L)).thenReturn(version("READY").setId(82L));
        when(indexingService.index(eq(DOCUMENT_ID), eq(VERSION_ID)))
                .thenReturn(new AiKnowledgeIndexingService.IndexingOutcome(true, 3, null));
        when(indexingService.index(eq(72L), eq(82L))).thenThrow(new IllegalStateException("embedding down"));

        int indexed = service.rebuildGeneration(BASE_ID);

        assertThat(indexed).as("单文档失败不影响其余文档").isEqualTo(1);
        verify(generationService).startGeneration(BASE_ID);
        verify(generationService, never()).retire(any(), any());
    }

    @Test
    void missingDocumentIsReportedAsNotFound() {
        when(documentMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.revoke(404L))
                .satisfies(throwable -> assertThat(((ServiceException) throwable).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_KNOWLEDGE_DOCUMENT_NOT_FOUND.getCode()));
    }
}
