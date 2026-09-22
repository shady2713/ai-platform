package com.basicframework.module.ai.service.knowledge.ingestion;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入库写入器（K03）：版本 + 任务的同一事务。
 *
 * <p>为什么单独成 bean：Spring 的事务是代理生效的，同类内部调用不会开启事务。
 * 把"必须同事务"的写入放在独立 bean 里，事务边界才真的存在（同 D04 的漂移写入器思路）。
 */
@Service
@RequiredArgsConstructor
class AiKnowledgeIngestionWriter {

    /** 任务类型：解析（首期只建解析任务，切分与向量化由 K05 在同一链路上推进）。 */
    private static final String TASK_KIND = AiKnowledgeIngestionTaskDO.KIND_PARSE;

    /** 默认最大尝试次数。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final AiKnowledgeIngestionTaskMapper taskMapper;

    private final AiKnowledgeDocumentService documentService;

    /** 创建/复用版本，并在同一事务内保证任务存在。 */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeIngestionResultDTO createVersionAndTask(
            AiKnowledgeIngestionRequestDTO request, AiKnowledgeBaseDO knowledgeBase) {
        AiKnowledgeDocumentUpsertResultDTO upsert = documentService.upsert(new AiKnowledgeDocumentSaveDTO()
                .setKnowledgeBaseId(knowledgeBase.getId())
                .setSourceKey(request.getSourceKey())
                .setTitle(request.getTitle())
                .setSourceType(request.getSourceType())
                .setSourceRef(request.getSourceRef())
                .setFileId(request.getFileId())
                .setContentHash(request.getContentHash()));
        Long taskId = ensureTask(knowledgeBase.getId(), upsert.getDocumentId(), upsert.getVersionId());
        return new AiKnowledgeIngestionResultDTO()
                .setDocumentId(upsert.getDocumentId())
                .setVersionId(upsert.getVersionId())
                .setVersionNo(upsert.getVersionNo())
                .setTaskId(taskId)
                .setReused(upsert.isReused())
                .setCreatedVersion(upsert.isCreatedVersion());
    }

    /** 同版本同类型只保留一条任务：重复入库复用版本时也复用任务（不产生重复处理）。 */
    private Long ensureTask(Long knowledgeBaseId, Long documentId, Long documentVersionId) {
        AiKnowledgeIngestionTaskDO existing = taskMapper.selectByVersion(documentVersionId, TASK_KIND);
        if (existing != null) {
            return existing.getId();
        }
        AiKnowledgeIngestionTaskDO task = new AiKnowledgeIngestionTaskDO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setDocumentId(documentId)
                .setDocumentVersionId(documentVersionId)
                .setTaskKind(TASK_KIND)
                .setStatus(AiKnowledgeIngestionTaskDO.STATUS_QUEUED)
                .setAttemptCount(0)
                .setMaxAttempts(DEFAULT_MAX_ATTEMPTS)
                // 立刻可领取：按秒取整（datetime 列为秒级精度，取整只会更早，不会"比现在晚"）
                .setNextAttemptTime(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .setClaimedEpoch(0)
                .setVersion(0);
        taskMapper.insert(task);
        return task.getId();
    }
}
