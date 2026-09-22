package com.basicframework.module.ai.service.knowledge.lifecycle;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_DOCUMENT_NOT_FOUND;

import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeChunkMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeDocumentVersionMapper;
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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同步、撤销与清理实现（K07）。
 *
 * <p>删除的顺序是**安全语义**：先撤可见性（DELETING）再清理资源。
 * 检索侧（K06）要求候选版本为 READY 且是文档当前 active 版本，因此 DELETING 一旦落库，
 * 引用与片段立即不可见——即使向量还没删完（AT-028 的"索引残留不可读取"）。
 *
 * <p>清理的每一步都幂等：切片按版本删（删过就是 0 行）、向量按版本过滤删（删过返回 0）、
 * 文件引用按版本释放（A07 会判定"是否最后一个引用"）、行按编号软删（重复软删是空操作）。
 * 因此 Job 崩溃重启后从当前状态继续即可，不需要额外状态机。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeLifecycleServiceImpl implements AiKnowledgeLifecycleService {

    /** 单轮清理的文档数上限（Job 用）。 */
    private static final int MAX_BATCH = 200;

    private final AiKnowledgeBaseService baseService;

    private final AiKnowledgeDocumentService documentService;

    private final AiKnowledgeChunkService chunkService;

    private final AiKnowledgeIndexGenerationService generationService;

    private final AiKnowledgeIngestionService ingestionService;

    private final AiKnowledgeIndexingService indexingService;

    private final AiFileService fileService;

    private final AiKnowledgeDocumentMapper documentMapper;

    private final AiKnowledgeDocumentVersionMapper versionMapper;

    private final AiKnowledgeChunkMapper chunkMapper;

    private final ObjectProvider<KnowledgeIndexPort> indexPortProvider;

    @Override
    public AiKnowledgeSyncResultDTO sync(
            Long knowledgeBaseId, String sourceKey, String title, String sourceRef, Long fileId, String contentHash) {
        AiKnowledgeIngestionResultDTO result = ingestionService.ingest(new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setSourceKey(sourceKey)
                .setTitle(title)
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_API_SYNC)
                .setSourceRef(sourceRef)
                .setFileId(fileId)
                .setContentHash(contentHash));
        return new AiKnowledgeSyncResultDTO()
                .setDocumentId(result.getDocumentId())
                .setVersionId(result.getVersionId())
                .setVersionNo(result.getVersionNo())
                .setTaskId(result.getTaskId())
                .setReused(result.isReused())
                .setCreatedVersion(result.isCreatedVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long documentId) {
        AiKnowledgeDocumentDO document = requireDocument(documentId);
        if (AiKnowledgeDocumentDO.STATUS_DELETING.equals(document.getStatus())) {
            // 幂等：已经撤过可见性就直接返回
            return;
        }
        // 状态机由 K02 的服务把关（终态/删除中一律拒绝）
        documentService.deleteDocument(documentId, document.getVersion());
    }

    @Override
    public AiKnowledgeCleanupReportDTO cleanup(Long documentId) {
        AiKnowledgeDocumentDO document = requireDocument(documentId);
        if (!AiKnowledgeDocumentDO.STATUS_DELETING.equals(document.getStatus())) {
            // 先撤可见性：清理不能绕过两段式
            revoke(documentId);
            document = requireDocument(documentId);
        }
        AiKnowledgeCleanupReportDTO report = new AiKnowledgeCleanupReportDTO();
        List<AiKnowledgeDocumentVersionDO> versions = versionMapper.selectByDocument(documentId);
        int deletedChunks = 0;
        long deletedVectors = 0;
        int releasedFiles = 0;
        for (AiKnowledgeDocumentVersionDO version : versions) {
            deletedChunks += chunkService.deleteVersionChunks(version.getId());
            deletedVectors += deleteVectors(document.getKnowledgeBaseId(), version.getId());
            if (version.getFileId() != null) {
                // 共享文件不误删：A07 只在最后一个引用释放时才真正删文件
                try {
                    fileService.release(version.getFileId());
                    releasedFiles++;
                } catch (RuntimeException releaseFailure) {
                    // 已释放或不属于当前主体：跳过（清理不因单个文件失败而中断）
                    log.debug(
                            "[cleanup][文件引用释放跳过][fileId={}, reason={}]",
                            version.getFileId(),
                            releaseFailure.getClass().getSimpleName());
                }
            }
            // 版本行软删除（保留可追溯性；重复软删是空操作）
            versionMapper.deleteById(version.getId());
        }
        documentMapper.deleteById(documentId);
        report.setProcessedDocuments(1)
                .setDeletedChunks(deletedChunks)
                .setDeletedVectors(deletedVectors)
                .setReleasedFiles(releasedFiles)
                .setPendingDocuments(pendingCleanupCount());
        return report;
    }

    @Override
    public AiKnowledgeCleanupReportDTO processPendingCleanups(int limit) {
        int effectiveLimit = Math.max(1, Math.min(MAX_BATCH, limit));
        List<AiKnowledgeDocumentDO> pending = documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiKnowledgeDocumentDO>()
                        .eq(AiKnowledgeDocumentDO::getStatus, AiKnowledgeDocumentDO.STATUS_DELETING)
                        .orderByAsc(AiKnowledgeDocumentDO::getId)
                        .last("LIMIT " + effectiveLimit));
        AiKnowledgeCleanupReportDTO total = new AiKnowledgeCleanupReportDTO();
        int processed = 0;
        int chunks = 0;
        long vectors = 0;
        int files = 0;
        for (AiKnowledgeDocumentDO document : pending) {
            AiKnowledgeCleanupReportDTO one = cleanup(document.getId());
            processed += one.getProcessedDocuments();
            chunks += one.getDeletedChunks();
            vectors += one.getDeletedVectors();
            files += one.getReleasedFiles();
        }
        total.setProcessedDocuments(processed)
                .setDeletedChunks(chunks)
                .setDeletedVectors(vectors)
                .setReleasedFiles(files)
                .setPendingDocuments(pendingCleanupCount());
        return total;
    }

    @Override
    public int rebuildGeneration(Long knowledgeBaseId) {
        AiKnowledgeBaseDO knowledgeBase = baseService.requireEnabled(knowledgeBaseId);
        // 新一代：旧代与其切片/向量保留（回退窗），激活后旧代自动退役
        generationService.startGeneration(knowledgeBaseId);
        int indexed = 0;
        for (AiKnowledgeDocumentDO document : documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiKnowledgeDocumentDO>()
                        .eq(AiKnowledgeDocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(AiKnowledgeDocumentDO::getStatus, AiKnowledgeDocumentDO.STATUS_READY))) {
            AiKnowledgeDocumentVersionDO active = documentService.getActiveVersion(document.getId());
            if (active == null) {
                continue;
            }
            try {
                if (indexingService.index(document.getId(), active.getId()).indexed()) {
                    indexed++;
                }
            } catch (RuntimeException failure) {
                // 单文档失败不影响其余文档；失败任务可由 K03 的人工重试入口再次排队
                log.warn(
                        "[rebuildGeneration][重建失败][knowledgeBaseId={}, documentId={}, reason={}]",
                        knowledgeBaseId,
                        document.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        log.info(
                "[rebuildGeneration][重建完成][knowledgeBaseId={}, indexed={}, activeGeneration={}]",
                knowledgeBase.getId(),
                indexed,
                knowledgeBase.getActiveGenerationNo());
        return indexed;
    }

    @Override
    public AiKnowledgeCleanupReportDTO auditOrphans(Long knowledgeBaseId, boolean cleanup) {
        AiKnowledgeCleanupReportDTO report = new AiKnowledgeCleanupReportDTO();
        List<String> orphans = new ArrayList<>();
        int chunks = 0;
        long vectors = 0;
        List<AiKnowledgeDocumentVersionDO> versions = versionMapper.selectList(
                new com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX<AiKnowledgeDocumentVersionDO>()
                        .eq(AiKnowledgeDocumentVersionDO::getKnowledgeBaseId, knowledgeBaseId));
        for (AiKnowledgeDocumentVersionDO version : versions) {
            AiKnowledgeDocumentDO document = documentMapper.selectById(version.getDocumentId());
            if (document == null) {
                orphans.add("version-without-document:" + version.getId());
                if (cleanup) {
                    chunks += chunkService.deleteVersionChunks(version.getId());
                    vectors += deleteVectors(knowledgeBaseId, version.getId());
                }
                continue;
            }
            long chunkRows = chunkMapper.countByVersion(version.getId());
            if (chunkRows == 0 && version.getChunkCount() != null && version.getChunkCount() > 0) {
                // 切片行没了但版本声称有切片：索引里可能有残留向量
                orphans.add("vectors-without-chunks:version=" + version.getId());
                if (cleanup) {
                    vectors += deleteVectors(knowledgeBaseId, version.getId());
                }
            }
        }
        List<AiKnowledgeIndexGenerationDO> generations = generationService.listGenerations(knowledgeBaseId);
        for (AiKnowledgeIndexGenerationDO generation : generations) {
            if (AiKnowledgeIndexGenerationDO.STATUS_RETIRED.equals(generation.getStatus())) {
                long remaining = chunkMapper.countByGeneration(knowledgeBaseId, generation.getGenerationNo());
                if (remaining > 0) {
                    orphans.add("retired-generation-vectors:generation=" + generation.getGenerationNo());
                }
            }
        }
        return report.setOrphans(orphans)
                .setDeletedChunks(chunks)
                .setDeletedVectors(vectors)
                .setPendingDocuments(pendingCleanupCount());
    }

    /** 删除某版本在向量服务里的点（按 document_version_id 过滤；幂等）。 */
    private long deleteVectors(Long knowledgeBaseId, Long documentVersionId) {
        KnowledgeIndexPort indexPort = indexPortProvider.getIfAvailable();
        if (indexPort == null) {
            return 0;
        }
        long deleted = 0;
        for (AiKnowledgeIndexGenerationDO generation : generationService.listGenerations(knowledgeBaseId)) {
            if (AiKnowledgeIndexGenerationDO.STATUS_FAILED.equals(generation.getStatus())) {
                continue;
            }
            try {
                deleted += indexPort.delete(
                        generation.getCollectionName(),
                        KnowledgeFilter.of("document_version_id", List.of(String.valueOf(documentVersionId))));
            } catch (RuntimeException failure) {
                log.debug(
                        "[deleteVectors][集合删除跳过][collection={}, reason={}]",
                        generation.getCollectionName(),
                        failure.getClass().getSimpleName());
            }
        }
        return deleted;
    }

    private int pendingCleanupCount() {
        return Math.toIntExact(documentMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiKnowledgeDocumentDO>()
                        .eq(AiKnowledgeDocumentDO::getStatus, AiKnowledgeDocumentDO.STATUS_DELETING)));
    }

    private AiKnowledgeDocumentDO requireDocument(Long documentId) {
        AiKnowledgeDocumentDO document = documentId == null ? null : documentMapper.selectById(documentId);
        if (document == null) {
            throw exception(AI_KNOWLEDGE_DOCUMENT_NOT_FOUND);
        }
        return document;
    }
}
